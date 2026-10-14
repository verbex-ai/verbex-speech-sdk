"""Verifying the signature of a transcription webhook (spec section 4.5)."""
from __future__ import annotations

import hashlib
import hmac
import json
import time
from typing import Mapping, Optional, Union

from ..errors import VerbexError
from .jobs import Job

LABEL = b"verbex-webhook-v1"
TIMESTAMP_HEADER = "X-Verbex-Timestamp"
SIGNATURE_HEADER = "X-Verbex-Signature"


class WebhookVerificationError(VerbexError):
    """A webhook whose signature does not verify."""


def signing_key(api_key: str) -> str:
    """hex(HMAC-SHA256(api_key, "verbex-webhook-v1")): what the platform signs this key's webhooks with."""
    return hmac.new(api_key.encode("utf-8"), LABEL, hashlib.sha256).hexdigest()


def verify_webhook(api_key: str, raw_body: Union[bytes, str], timestamp: Optional[str], signature: Optional[str],
                   tolerance: float = 300, now: Optional[float] = None) -> bool:
    """True when ``signature`` is this key's signature of ``timestamp`` and the exact body, and is recent.

    Never raises for bad input. Pass the body exactly as received, before any JSON parsing.
    """
    if not isinstance(signature, str) or not signature.startswith("v1=") or not isinstance(timestamp, str):
        return False
    try:
        sent_at = int(timestamp)
    except ValueError:
        return False
    current = time.time() if now is None else now
    if abs(current - sent_at) > tolerance:
        return False
    body = raw_body.encode("utf-8") if isinstance(raw_body, str) else bytes(raw_body)
    expected = hmac.new(signing_key(api_key).encode("utf-8"), timestamp.encode("utf-8") + b"." + body,
                        hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature[3:])


def _header(headers: Mapping[str, str], name: str) -> Optional[str]:
    wanted = name.lower()
    for key, value in headers.items():
        if key.lower() == wanted:
            return value
    return None


def parse_webhook(api_key: str, raw_body: Union[bytes, str], headers: Mapping[str, str], tolerance: float = 300,
                  now: Optional[float] = None) -> Job:
    """The job a webhook carries, once its signature verifies; :class:`WebhookVerificationError` otherwise."""
    if not verify_webhook(api_key, raw_body, _header(headers, TIMESTAMP_HEADER), _header(headers, SIGNATURE_HEADER),
                          tolerance, now):
        raise WebhookVerificationError("The webhook's signature does not verify.")
    body = raw_body.decode("utf-8") if isinstance(raw_body, bytes) else raw_body
    return Job.from_dict(json.loads(body))
