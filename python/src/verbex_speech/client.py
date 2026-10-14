"""The entry points: hold the configuration and the HTTP client, and expose ``.listen`` and ``.speak``."""
from __future__ import annotations

import asyncio
import time
from typing import Any, Dict, List, Optional

import httpx

from ._transport import common as c
from ._transport import http_client as http
from .listen import AsyncListen, Listen
from .speak import AsyncSpeak, Speak


class VerbexSpeech:
    """Client for the Verbex Speech API.

    >>> client = VerbexSpeech(api_key="vbx_...", base_url="https://speech-api-stage.verbex.ai")
    >>> job = client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
    """

    def __init__(self, api_key: Optional[str] = None, base_url: Optional[str] = None, *, timeout: float = 30.0,
                 max_retries: int = 2, user_agent: Optional[str] = None,
                 transport: Optional[httpx.BaseTransport] = None):
        self._config = c.resolve_config(api_key, base_url, timeout, max_retries, user_agent)
        self._http = httpx.Client(transport=transport, follow_redirects=False,
                                  timeout=httpx.Timeout(self._config.timeout, connect=c.CONNECT_TIMEOUT))
        self._sleep = time.sleep
        self._monotonic = time.monotonic
        self.listen = Listen(self)
        self.speak = Speak(self)

    @property
    def base_url(self) -> str:
        return self._config.base_url

    def __repr__(self) -> str:
        return f"VerbexSpeech(base_url={self.base_url!r})"

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "VerbexSpeech":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def _request(self, call: str, method: str, path: str, *, params: Optional[List[Any]] = None,
                 json: Any = None, data: Optional[Dict[str, Any]] = None, files: Any = None,
                 timeout: Optional[float] = None, stream: bool = False,
                 headers: Optional[Dict[str, str]] = None) -> httpx.Response:
        # self._sleep is read per call so tests (and callers) can replace it.
        return http.request(self._http, self._config, self._sleep, call, method, path, params=params, json=json,
                            data=data, files=files, timeout=timeout, stream=stream, headers=headers)


class AsyncVerbexSpeech:
    """Asynchronous client for the Verbex Speech API.

    >>> async with AsyncVerbexSpeech(api_key="vbx_...", base_url="https://speech-api-stage.verbex.ai") as client:
    ...     job = await client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
    """

    def __init__(self, api_key: Optional[str] = None, base_url: Optional[str] = None, *, timeout: float = 30.0,
                 max_retries: int = 2, user_agent: Optional[str] = None,
                 transport: Optional[httpx.AsyncBaseTransport] = None):
        self._config = c.resolve_config(api_key, base_url, timeout, max_retries, user_agent)
        self._http = httpx.AsyncClient(transport=transport, follow_redirects=False,
                                       timeout=httpx.Timeout(self._config.timeout, connect=c.CONNECT_TIMEOUT))
        self._sleep = asyncio.sleep
        self._monotonic = time.monotonic
        self.listen = AsyncListen(self)
        self.speak = AsyncSpeak(self)

    @property
    def base_url(self) -> str:
        return self._config.base_url

    def __repr__(self) -> str:
        return f"AsyncVerbexSpeech(base_url={self.base_url!r})"

    async def aclose(self) -> None:
        await self._http.aclose()

    async def __aenter__(self) -> "AsyncVerbexSpeech":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    async def _request(self, call: str, method: str, path: str, *, params: Optional[List[Any]] = None,
                       json: Any = None, data: Optional[Dict[str, Any]] = None, files: Any = None,
                       timeout: Optional[float] = None, stream: bool = False,
                       headers: Optional[Dict[str, str]] = None) -> httpx.Response:
        return await http.request_async(self._http, self._config, self._sleep, call, method, path, params=params,
                                        json=json, data=data, files=files, timeout=timeout, stream=stream,
                                        headers=headers)
