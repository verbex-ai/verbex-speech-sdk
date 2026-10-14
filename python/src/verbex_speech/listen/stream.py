"""Live transcription over a WebSocket: events, the sync and async sessions, and transcribe_stream."""
from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, AsyncIterator, Awaitable, Callable, Dict, Iterator, List, Mapping, Optional

from websockets.asyncio.client import ClientConnection as AsyncClientConnection
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import ClientConnection

from .._transport import common as c
from .._transport.sockets import close_code, open_socket, open_socket_async, parse
from ..errors import StreamError

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech

FINISH_WAIT = 10.0
PATH = "/v1/listen/stream"


@dataclass(frozen=True)
class ListenEvent:
    """One frame of a live session: ``segment``, ``configured``, ``done`` or ``error``."""

    type: str
    index: Optional[int] = None
    start_sec: Optional[float] = None
    end_sec: Optional[float] = None
    text: Optional[str] = None
    hotwords: Optional[List[str]] = None
    total_segments: Optional[int] = None
    code: Optional[str] = None
    message: Optional[str] = None
    raw: Dict[str, Any] = field(default_factory=dict, repr=False)

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "ListenEvent":
        return cls(type=str(d.get("type", "")), index=d.get("index"), start_sec=d.get("start_sec"),
                   end_sec=d.get("end_sec"), text=d.get("text"), hotwords=d.get("hotwords"),
                   total_segments=d.get("total_segments"), code=d.get("code"), message=d.get("message"),
                   raw=dict(d))


class LiveSession:
    """A live transcription session. Use ``with``; iterate it for events.

    Leaving the ``with`` block without :meth:`finish` sends ``eof`` and waits briefly for ``done``,
    so the last words are not lost.
    """

    def __init__(self, opener: Callable[[], ClientConnection]):
        self._opener = opener
        self._socket: Optional[ClientConnection] = None
        self.session_id: Optional[str] = None
        self.ready: Optional[ListenEvent] = None
        self._finished = False
        self._ended = False
        self._last_error: Optional[ListenEvent] = None

    def __enter__(self) -> "LiveSession":
        self._socket = self._opener()
        try:
            first = self._next_frame()
        except BaseException:
            self._socket.close()
            raise
        if first is None or first.get("type") != "ready":
            self._socket.close()
            raise StreamError("stream_closed", "The session ended before it was ready.")
        self.ready = ListenEvent.from_dict(first)
        self.session_id = first.get("session_id")
        return self

    def _next_frame(self) -> Optional[dict]:
        """The next JSON frame; raises StreamError when the server ends the session other than after done."""
        assert self._socket is not None
        while True:
            try:
                message = self._socket.recv()
            except ConnectionClosed as closed:
                self._ended = True
                code = close_code(closed)
                if self._last_error is not None:
                    raise StreamError(self._last_error.code or "error", self._last_error.message or "", code) from None
                raise StreamError("stream_closed", "The session closed before done.", code) from None
            frame = parse(message)
            if frame is None:
                continue
            if frame.get("type") == "error" and self.ready is None:
                # Refused before ready: the close that follows carries the code.
                self._last_error = ListenEvent.from_dict(frame)
                continue
            return frame

    def __iter__(self) -> Iterator[ListenEvent]:
        while not self._ended:
            frame = self._next_frame()
            if frame is None:
                return
            event = ListenEvent.from_dict(frame)
            if event.type == "ready":
                continue
            if event.type == "error":
                self._last_error = event
            yield event
            if event.type == "done":
                self._ended = True
                self._drain_close()
                return

    def _drain_close(self) -> None:
        assert self._socket is not None
        try:
            while True:
                self._socket.recv(timeout=FINISH_WAIT)
        except (ConnectionClosed, TimeoutError):
            pass

    def send(self, pcm: bytes) -> None:
        """Raw PCM, 16 kHz mono signed 16-bit little-endian. Frames over 64 KiB are split."""
        if self._finished:
            raise RuntimeError("The session was finished: no more audio can be sent.")
        assert self._socket is not None
        for frame in c.frames(pcm):
            self._socket.send(frame)

    def configure(self, hotwords: List[str]) -> None:
        """Replaces the hotwords; the answer is a ``configured`` event (or an ``error`` event)."""
        assert self._socket is not None
        self._socket.send(json.dumps({"type": "configure", "hotwords": list(hotwords)}, ensure_ascii=False))

    def finish(self) -> None:
        """The audio has ended: the remaining segments follow, then ``done``."""
        if self._finished or self._ended:
            return
        assert self._socket is not None
        self._finished = True
        self._socket.send(json.dumps({"type": "eof"}))

    def close(self) -> None:
        if self._socket is None:
            return
        if not self._ended:
            try:
                self.finish()
                deadline = time.monotonic() + FINISH_WAIT
                while time.monotonic() < deadline:
                    frame = parse(self._socket.recv(timeout=max(0.0, deadline - time.monotonic())))
                    if frame is not None and frame.get("type") == "done":
                        break
            except (ConnectionClosed, TimeoutError):
                pass
        self._socket.close()

    def __exit__(self, exc_type: Any, *exc: Any) -> None:
        if exc_type is not None:
            if self._socket is not None:
                self._socket.close()
            return
        self.close()


class AsyncLiveSession:
    """``async with client.listen.stream(...) as session``; ``async for event in session``."""

    def __init__(self, opener: Callable[[], Awaitable[AsyncClientConnection]]):
        self._opener = opener
        self._socket: Optional[AsyncClientConnection] = None
        self.session_id: Optional[str] = None
        self.ready: Optional[ListenEvent] = None
        self._finished = False
        self._ended = False
        self._last_error: Optional[ListenEvent] = None

    async def __aenter__(self) -> "AsyncLiveSession":
        self._socket = await self._opener()
        try:
            first = await self._next_frame()
        except BaseException:
            await self._socket.close()
            raise
        if first is None or first.get("type") != "ready":
            await self._socket.close()
            raise StreamError("stream_closed", "The session ended before it was ready.")
        self.ready = ListenEvent.from_dict(first)
        self.session_id = first.get("session_id")
        return self

    async def _next_frame(self) -> Optional[dict]:
        assert self._socket is not None
        while True:
            try:
                message = await self._socket.recv()
            except ConnectionClosed as closed:
                self._ended = True
                code = close_code(closed)
                if self._last_error is not None:
                    raise StreamError(self._last_error.code or "error", self._last_error.message or "", code) from None
                raise StreamError("stream_closed", "The session closed before done.", code) from None
            frame = parse(message)
            if frame is None:
                continue
            if frame.get("type") == "error" and self.ready is None:
                self._last_error = ListenEvent.from_dict(frame)
                continue
            return frame

    def __aiter__(self) -> AsyncIterator[ListenEvent]:
        return self._events()

    async def _events(self) -> AsyncIterator[ListenEvent]:
        while not self._ended:
            frame = await self._next_frame()
            if frame is None:
                return
            event = ListenEvent.from_dict(frame)
            if event.type == "ready":
                continue
            if event.type == "error":
                self._last_error = event
            yield event
            if event.type == "done":
                self._ended = True
                await self._drain_close()
                return

    async def _drain_close(self) -> None:
        assert self._socket is not None
        try:
            while True:
                await asyncio.wait_for(self._socket.recv(), FINISH_WAIT)
        except (ConnectionClosed, asyncio.TimeoutError, TimeoutError):
            pass

    async def send(self, pcm: bytes) -> None:
        if self._finished:
            raise RuntimeError("The session was finished: no more audio can be sent.")
        assert self._socket is not None
        for frame in c.frames(pcm):
            await self._socket.send(frame)

    async def configure(self, hotwords: List[str]) -> None:
        assert self._socket is not None
        await self._socket.send(json.dumps({"type": "configure", "hotwords": list(hotwords)}, ensure_ascii=False))

    async def finish(self) -> None:
        if self._finished or self._ended:
            return
        assert self._socket is not None
        self._finished = True
        await self._socket.send(json.dumps({"type": "eof"}))

    async def aclose(self) -> None:
        if self._socket is None:
            return
        if not self._ended:
            try:
                await self.finish()

                async def until_done() -> None:
                    while True:
                        frame = parse(await self._socket.recv())  # type: ignore[union-attr]
                        if frame is not None and frame.get("type") == "done":
                            return

                await asyncio.wait_for(until_done(), FINISH_WAIT)
            except (ConnectionClosed, asyncio.TimeoutError, TimeoutError):
                pass
        await self._socket.close()

    async def __aexit__(self, exc_type: Any, *exc: Any) -> None:
        if exc_type is not None:
            if self._socket is not None:
                await self._socket.close()
            return
        await self.aclose()


def live_session(client: "VerbexSpeech", *, model: str, language: str, hotwords: Optional[List[str]] = None,
                 tags: Optional[List[str]] = None) -> LiveSession:
    """A session, not yet open: the arguments are checked now, the socket opens on ``with``."""
    params = c.listen_stream_params(model, language, hotwords, tags)
    return LiveSession(lambda: open_socket(client._config, PATH, params))


def live_session_async(client: "AsyncVerbexSpeech", *, model: str, language: str,
                       hotwords: Optional[List[str]] = None, tags: Optional[List[str]] = None) -> AsyncLiveSession:
    params = c.listen_stream_params(model, language, hotwords, tags)
    return AsyncLiveSession(lambda: open_socket_async(client._config, PATH, params))


def transcribe_stream(session: LiveSession, pcm_chunks: Any) -> List[Any]:
    """Sends every chunk, finishes, and returns the segments in order. The caller paces the chunks."""
    with session:
        for chunk in pcm_chunks:
            session.send(chunk)
        session.finish()
        return [event for event in session if event.type == "segment"]


async def transcribe_stream_async(session: AsyncLiveSession, pcm_chunks: Any) -> List[Any]:
    """Sends every chunk (an iterable or async iterable), finishes, and returns the segments in order."""
    async with session:
        if hasattr(pcm_chunks, "__aiter__"):
            async for chunk in pcm_chunks:
                await session.send(chunk)
        else:
            for chunk in pcm_chunks:
                await session.send(chunk)
        await session.finish()
        return [event async for event in session if event.type == "segment"]
