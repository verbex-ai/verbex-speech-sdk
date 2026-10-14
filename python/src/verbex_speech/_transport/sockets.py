"""Opening a WebSocket with the key header, and reading its frames; a refused handshake is the HTTP error."""
from __future__ import annotations

import asyncio
import json
from typing import Any, List, Optional

from websockets.asyncio.client import ClientConnection as AsyncClientConnection
from websockets.asyncio.client import connect as connect_async
from websockets.exceptions import ConnectionClosed, InvalidStatus, WebSocketException
from websockets.sync.client import ClientConnection, connect

from ..errors import APIConnectionError, APITimeoutError, from_response
from . import common as c


def open_socket(config: c.Config, path: str, params: List[Any]) -> ClientConnection:
    """Opens the socket with the key header; a refused handshake is the HTTP error it answered."""
    try:
        # Entered as a context manager: websockets 13 returns the connection itself, later versions a
        # factory whose __enter__ opens it; the session closes the connection it is handed.
        return connect(c.ws_url(config, path, params), additional_headers=c.api_headers(config, c.new_trace_id()),
                       max_size=1024 * 1024, open_timeout=config.timeout, user_agent_header=None).__enter__()
    except InvalidStatus as refused:
        response = refused.response
        raise from_response(response.status_code, {k.lower(): v for k, v in response.headers.raw_items()},
                            response.body or b"") from None
    except TimeoutError:
        raise APITimeoutError(f"The socket {path} did not open in time.") from None
    except (OSError, WebSocketException) as exc:
        raise APIConnectionError(f"Could not open the socket {path}: {type(exc).__name__}.") from None


async def open_socket_async(config: c.Config, path: str, params: List[Any]) -> AsyncClientConnection:
    """The awaited twin of :func:`open_socket`."""
    try:
        return await connect_async(c.ws_url(config, path, params),
                                   additional_headers=c.api_headers(config, c.new_trace_id()),
                                   max_size=1024 * 1024, open_timeout=config.timeout, user_agent_header=None)
    except InvalidStatus as refused:
        response = refused.response
        raise from_response(response.status_code, {k.lower(): v for k, v in response.headers.raw_items()},
                            response.body or b"") from None
    except (asyncio.TimeoutError, TimeoutError):
        raise APITimeoutError(f"The socket {path} did not open in time.") from None
    except (OSError, WebSocketException) as exc:
        raise APIConnectionError(f"Could not open the socket {path}: {type(exc).__name__}.") from None


def close_code(closed: ConnectionClosed) -> int:
    return closed.rcvd.code if closed.rcvd is not None else 1006


def parse(message: Any) -> Optional[dict]:
    """A text frame's JSON object; None for binary frames and anything that is not a JSON object."""
    if not isinstance(message, str):
        return None
    try:
        frame = json.loads(message)
    except ValueError:
        return None
    return frame if isinstance(frame, dict) else None
