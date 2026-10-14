"""One API call over HTTP, retried by the rules of :mod:`.retry` (spec section 8)."""
from __future__ import annotations

from typing import Any, Awaitable, Callable, Dict, List, Optional

import httpx

from ..errors import APIConnectionError, APIError, APITimeoutError, from_response
from . import common as c
from .retry import backoff, should_retry


def _build(http: Any, config: c.Config, trace_id: str, method: str, path: str, params: Optional[List[Any]],
           json: Any, data: Optional[Dict[str, Any]], files: Any, timeout: Optional[float]) -> httpx.Request:
    return http.build_request(
        method, config.base_url + path, params=params, json=json, data=data, files=files,
        headers=c.api_headers(config, trace_id),
        timeout=httpx.Timeout(timeout or config.timeout, connect=c.CONNECT_TIMEOUT))


def _retry_wait(config: c.Config, call: str, attempt: int, error: Exception) -> Optional[float]:
    """Seconds to wait before trying again, or None when the error is to be raised."""
    status = error.status if isinstance(error, APIError) else None
    code = error.code if isinstance(error, APIError) else None
    if attempt < config.max_retries and should_retry(
            call, status=status, code=code, error=None if isinstance(error, APIError) else error):
        return backoff(attempt, error.retry_after if isinstance(error, APIError) else None)
    return None


def request(http: httpx.Client, config: c.Config, sleep: Callable[[float], Any], call: str, method: str,
            path: str, *, params: Optional[List[Any]] = None, json: Any = None,
            data: Optional[Dict[str, Any]] = None, files: Any = None, timeout: Optional[float] = None,
            stream: bool = False) -> httpx.Response:
    """``call`` is ``get``, ``upload_start``, ``speak`` or ``submit``: it decides what is retried."""
    trace_id = c.new_trace_id()
    attempt = 0
    while True:
        error: Exception
        try:
            response = http.send(_build(http, config, trace_id, method, path, params, json, data, files, timeout),
                                 stream=stream)
        except httpx.TimeoutException:
            error = APITimeoutError(f"No answer from the API to {method} {path} in time.")
        except httpx.TransportError as exc:
            error = APIConnectionError(f"Could not reach the API for {method} {path}: {type(exc).__name__}.")
        else:
            if response.status_code < 400:
                return response
            body = response.read()
            response.close()
            error = from_response(response.status_code, response.headers, body)
        wait = _retry_wait(config, call, attempt, error)
        if wait is None:
            raise error from None
        sleep(wait)
        attempt += 1


async def request_async(http: httpx.AsyncClient, config: c.Config, sleep: Callable[[float], Awaitable[Any]],
                        call: str, method: str, path: str, *, params: Optional[List[Any]] = None, json: Any = None,
                        data: Optional[Dict[str, Any]] = None, files: Any = None, timeout: Optional[float] = None,
                        stream: bool = False) -> httpx.Response:
    """The awaited twin of :func:`request`."""
    trace_id = c.new_trace_id()
    attempt = 0
    while True:
        error: Exception
        try:
            response = await http.send(
                _build(http, config, trace_id, method, path, params, json, data, files, timeout), stream=stream)
        except httpx.TimeoutException:
            error = APITimeoutError(f"No answer from the API to {method} {path} in time.")
        except httpx.TransportError as exc:
            error = APIConnectionError(f"Could not reach the API for {method} {path}: {type(exc).__name__}.")
        else:
            if response.status_code < 400:
                return response
            body = await response.aread()
            await response.aclose()
            error = from_response(response.status_code, response.headers, body)
        wait = _retry_wait(config, call, attempt, error)
        if wait is None:
            raise error from None
        await sleep(wait)
        attempt += 1
