"""Many utterances over one WebSocket, strictly one at a time: the sync and async speak sessions."""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, AsyncIterator, Awaitable, Callable, Iterator, List, Mapping, Optional

from websockets.asyncio.client import ClientConnection as AsyncClientConnection
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import ClientConnection

from .._transport import common as c
from .._transport.sockets import close_code, open_socket, open_socket_async, parse
from ..errors import FieldError, StreamError
from . import SpeakError

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech

PATH = "/v1/speak/stream"


@dataclass(frozen=True)
class SpeakDone:
    request_id: Optional[str]
    audio_seconds: Optional[float]


class Utterance:
    """The audio of one utterance on a speak socket, as it arrives. ``done`` is set once it has all arrived."""

    def __init__(self, session: "SpeakSession", request_id: str):
        self._session = session
        self.request_id = request_id
        self.done: Optional[SpeakDone] = None
        self._over = False

    def __iter__(self) -> Iterator[bytes]:
        while not self._over:
            message = self._session._recv()
            if isinstance(message, (bytes, bytearray)):
                yield bytes(message)
                continue
            frame = parse(message)
            if frame is None:
                continue
            if frame.get("event") == "done":
                self._over = True
                self._session._current = None
                self.done = SpeakDone(frame.get("request_id"), frame.get("audio_seconds"))
                return
            if frame.get("event") == "error":
                self._over = True
                self._session._current = None
                fields = [FieldError(str(f.get("name", "")), str(f.get("reason", "")))
                          for f in frame.get("fields") or [] if isinstance(f, dict)]
                raise SpeakError(str(frame.get("code", "error")), str(frame.get("message", "")),
                                 frame.get("request_id"), fields)

    def read(self) -> bytes:
        return b"".join(self)


class SpeakSession:
    """Many utterances over one socket, strictly one at a time. Use ``with``."""

    def __init__(self, opener: Callable[[], ClientConnection]):
        self._opener = opener
        self._socket: Optional[ClientConnection] = None
        self._current: Optional[Utterance] = None
        self._error: Optional[dict] = None

    def __enter__(self) -> "SpeakSession":
        self._socket = self._opener()
        return self

    def _recv(self) -> Any:
        assert self._socket is not None
        while True:
            try:
                message = self._socket.recv()
            except ConnectionClosed as closed:
                error = self._error or {}
                raise StreamError(str(error.get("code", "stream_closed")),
                                  str(error.get("message", "The speak socket closed.")), close_code(closed)) from None
            frame = parse(message)
            if frame is not None and frame.get("event") == "error" and frame.get("request_id") is None \
                    and self._current is None:
                self._error = frame
                continue
            return message

    def say(self, *, model: str, voice: str, text: str, request_id: Optional[str] = None,
            settings: Optional[Mapping[str, Any]] = None) -> Utterance:
        """Sends one utterance and returns its audio, to iterate (or ``read()``) before the next ``say``."""
        if self._current is not None:
            raise RuntimeError("One utterance at a time: read the previous one to its end first.")
        assert self._socket is not None
        rid = request_id or str(uuid.uuid4())
        self._socket.send(json.dumps(c.speak_body(model, voice, text, rid, settings), ensure_ascii=False))
        self._current = Utterance(self, rid)
        return self._current

    def close(self) -> None:
        if self._socket is not None:
            self._socket.close()

    def __exit__(self, *exc: Any) -> None:
        self.close()


class AsyncUtterance:
    def __init__(self, session: "AsyncSpeakSession", request_id: str):
        self._session = session
        self.request_id = request_id
        self.done: Optional[SpeakDone] = None
        self._over = False

    def __aiter__(self) -> AsyncIterator[bytes]:
        return self._chunks()

    async def _chunks(self) -> AsyncIterator[bytes]:
        while not self._over:
            message = await self._session._recv()
            if isinstance(message, (bytes, bytearray)):
                yield bytes(message)
                continue
            frame = parse(message)
            if frame is None:
                continue
            if frame.get("event") == "done":
                self._over = True
                self._session._current = None
                self.done = SpeakDone(frame.get("request_id"), frame.get("audio_seconds"))
                return
            if frame.get("event") == "error":
                self._over = True
                self._session._current = None
                fields = [FieldError(str(f.get("name", "")), str(f.get("reason", "")))
                          for f in frame.get("fields") or [] if isinstance(f, dict)]
                raise SpeakError(str(frame.get("code", "error")), str(frame.get("message", "")),
                                 frame.get("request_id"), fields)

    async def read(self) -> bytes:
        return b"".join([chunk async for chunk in self])


class AsyncSpeakSession:
    def __init__(self, opener: Callable[[], Awaitable[AsyncClientConnection]]):
        self._opener = opener
        self._socket: Optional[AsyncClientConnection] = None
        self._current: Optional[AsyncUtterance] = None
        self._error: Optional[dict] = None

    async def __aenter__(self) -> "AsyncSpeakSession":
        self._socket = await self._opener()
        return self

    async def _recv(self) -> Any:
        assert self._socket is not None
        while True:
            try:
                message = await self._socket.recv()
            except ConnectionClosed as closed:
                error = self._error or {}
                raise StreamError(str(error.get("code", "stream_closed")),
                                  str(error.get("message", "The speak socket closed.")), close_code(closed)) from None
            frame = parse(message)
            if frame is not None and frame.get("event") == "error" and frame.get("request_id") is None \
                    and self._current is None:
                self._error = frame
                continue
            return message

    async def say(self, *, model: str, voice: str, text: str, request_id: Optional[str] = None,
                  settings: Optional[Mapping[str, Any]] = None) -> AsyncUtterance:
        if self._current is not None:
            raise RuntimeError("One utterance at a time: read the previous one to its end first.")
        assert self._socket is not None
        rid = request_id or str(uuid.uuid4())
        await self._socket.send(json.dumps(c.speak_body(model, voice, text, rid, settings), ensure_ascii=False))
        self._current = AsyncUtterance(self, rid)
        return self._current

    async def aclose(self) -> None:
        if self._socket is not None:
            await self._socket.close()

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()


def speak_session(client: "VerbexSpeech", *, tags: Optional[List[str]] = None) -> SpeakSession:
    """A session, not yet open: the socket opens on ``with``."""
    params = c.tag_params(tags)
    return SpeakSession(lambda: open_socket(client._config, PATH, params))


def speak_session_async(client: "AsyncVerbexSpeech", *,
                        tags: Optional[List[str]] = None) -> AsyncSpeakSession:
    params = c.tag_params(tags)
    return AsyncSpeakSession(lambda: open_socket_async(client._config, PATH, params))
