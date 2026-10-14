"""The errors every product shares. Every SDK error is a :class:`VerbexError`; none carries the API key or an
upload URL. Product-specific errors live with their product: ``UploadError`` and ``WaitTimeoutError`` in
:mod:`verbex_speech.listen.jobs`, ``WebhookVerificationError`` in :mod:`verbex_speech.listen.webhook`,
``SpeakError`` in :mod:`verbex_speech.speak`.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, List, Mapping, Optional


class VerbexError(Exception):
    """Base of every error the SDK raises."""


class ConfigurationError(VerbexError):
    """The client cannot be built: a missing or malformed API key or base URL."""


@dataclass(frozen=True)
class FieldError:
    """One offending field of a ``validation_failed`` answer."""

    name: str
    reason: str


class APIError(VerbexError):
    """An error answer from the API (or a request the SDK refused before sending it, ``status`` None)."""

    def __init__(self, status: Optional[int], code: str, message: str, request_id: Optional[str] = None,
                 fields: Optional[List[FieldError]] = None, retry_after: Optional[int] = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.request_id = request_id
        self.fields = fields or []
        self.retry_after = retry_after

    def __str__(self) -> str:
        where = f"{self.status} " if self.status is not None else ""
        trace = f" (request_id {self.request_id})" if self.request_id else ""
        return f"{where}{self.code}: {self.message}{trace}"

    def __repr__(self) -> str:
        return f"{type(self).__name__}(status={self.status!r}, code={self.code!r}, request_id={self.request_id!r})"


class BadRequestError(APIError):
    """400, 405, 406, 409, 413, 415: the request must change before it can succeed."""


class AuthenticationError(APIError):
    """401: the API key is missing or not valid."""


class PaymentRequiredError(APIError):
    """402: the workspace cannot be charged (``no_subscription``, ``insufficient_credit``, ``invoice_overdue``)."""


class PermissionDeniedError(APIError):
    """403: the key lacks the permission the endpoint needs."""


class NotFoundError(APIError):
    """404: no such route, job, upload, model or voice."""


class RateLimitError(APIError):
    """429: a rate or concurrency limit. ``retry_after`` is set for the rate limits."""


class ServerError(APIError):
    """5xx: the platform could not answer. Usually worth retrying with backoff."""


class ValidationError(VerbexError, ValueError):
    """An argument refused before anything was sent (over 100 MiB, no size for a stream, a bad format).

    Also a ``ValueError``. ``code`` is the API's code for the same refusal; ``fields`` names the argument.
    """

    def __init__(self, code: str, message: str, fields: Optional[List[FieldError]] = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.fields = fields or []


class APIConnectionError(VerbexError):
    """No answer: DNS, connect, TLS or a dropped connection."""


class APITimeoutError(VerbexError):
    """No answer within the timeout."""


RETRYABLE_STREAM_CODES = frozenset({"stream_failed", "service_unavailable", "credit_unavailable",
                                    "engine_unavailable", "stream_closed"})


class StreamError(VerbexError):
    """A WebSocket session ended by an error frame or an unexpected close."""

    def __init__(self, code: str, message: str, close_code: Optional[int] = None):
        super().__init__(f"{code}: {message}" + (f" (close {close_code})" if close_code is not None else ""))
        self.code = code
        self.message = message
        self.close_code = close_code

    @property
    def retryable(self) -> bool:
        """True when reconnecting and resending the audio makes sense."""
        return self.close_code in (1006, 1011, 1013) or self.code in RETRYABLE_STREAM_CODES


def _fields(raw: Any) -> List[FieldError]:
    if not isinstance(raw, list):
        return []
    return [FieldError(str(f.get("name", "")), str(f.get("reason", ""))) for f in raw if isinstance(f, dict)]


def _retry_after(headers: Mapping[str, str]) -> Optional[int]:
    value = headers.get("retry-after")
    if value is None:
        return None
    try:
        return max(0, int(value.strip()))
    except ValueError:
        return None


def error_type(status: int) -> type:
    if status == 401:
        return AuthenticationError
    if status == 402:
        return PaymentRequiredError
    if status == 403:
        return PermissionDeniedError
    if status == 404:
        return NotFoundError
    if status == 429:
        return RateLimitError
    if status >= 500:
        return ServerError
    return BadRequestError


def from_response(status: int, headers: Mapping[str, str], body: bytes) -> APIError:
    """The error for an HTTP error answer, from the platform's envelope when there is one."""
    trace = headers.get("x-trace-id")
    code, message, request_id, fields = f"http_{status}", f"The API answered {status}.", trace, []
    try:
        envelope = json.loads(body.decode("utf-8"))
        error = envelope.get("error") if isinstance(envelope, dict) else None
        if isinstance(error, dict) and error.get("code"):
            code = str(error["code"])
            message = str(error.get("message") or message)
            request_id = error.get("request_id") or trace
            fields = _fields(error.get("fields"))
    except (ValueError, UnicodeDecodeError):
        pass
    return error_type(status)(status, code, message, request_id, fields, _retry_after(headers))
