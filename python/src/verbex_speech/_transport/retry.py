"""Which failed calls are retried automatically, and how long to wait (spec section 8)."""
from __future__ import annotations

import random
from typing import Optional

from ..errors import APIConnectionError, APITimeoutError

RATE_LIMITS = frozenset({"rate_limited", "workspace_rate_limited"})
MAX_RETRY_AFTER = 60


def should_retry(call: str, status: Optional[int] = None, code: Optional[str] = None,
                 error: Optional[BaseException] = None) -> bool:
    """``call`` is ``get``, ``upload_start``, ``speak`` or ``submit``.

    A submit is retried as a read is: it carries its Idempotency-Key, so a retry after a lost answer
    gets the first request's receipt rather than a second job. It is also retried while the first
    request with its key still runs (409 ``request_in_progress``).
    """
    if error is not None:
        return isinstance(error, (APIConnectionError, APITimeoutError))
    if status == 429:
        return code in RATE_LIMITS
    if call == "submit" and status == 409:
        return code == "request_in_progress"
    return status in (502, 503, 504)


def backoff(attempt: int, retry_after: Optional[int] = None) -> Optional[float]:
    """Seconds before retry number ``attempt + 1``; None when the server asks for longer than is waited."""
    if retry_after is not None:
        return float(retry_after) if retry_after <= MAX_RETRY_AFTER else None
    return random.uniform(0, min(8.0, 0.5 * (2 ** attempt)))
