"""One utterance over HTTP: whole (:func:`synthesize`) or as it arrives (:func:`synthesize_stream`)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, AsyncIterator, Iterator, List, Mapping, Optional

import httpx

from .._transport import common as c
from . import SpeakError

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech


@dataclass(frozen=True)
class SpeechAudio:
    """One synthesised utterance: raw PCM (16 kHz mono s16le) or WAV."""

    data: bytes = field(repr=False)
    content_type: Optional[str]
    request_id: Optional[str]


class SpeechStream:
    """The audio of one utterance as it arrives. Use as a context manager and iterate the chunks."""

    def __init__(self, response: httpx.Response):
        self._response = response
        self.request_id: Optional[str] = response.headers.get("x-request-id")
        self.content_type: Optional[str] = response.headers.get("content-type")

    def __iter__(self) -> Iterator[bytes]:
        try:
            for chunk in self._response.iter_bytes():
                yield chunk
        except httpx.HTTPError:
            raise SpeakError("audio_incomplete", "The audio ended early: the utterance failed.",
                             self.request_id) from None

    def close(self) -> None:
        self._response.close()

    def __enter__(self) -> "SpeechStream":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()


class AsyncSpeechStream:
    """The audio of one utterance as it arrives. Use ``async with`` and ``async for`` the chunks."""

    def __init__(self, response: httpx.Response):
        self._response = response
        self.request_id: Optional[str] = response.headers.get("x-request-id")
        self.content_type: Optional[str] = response.headers.get("content-type")

    def __aiter__(self) -> AsyncIterator[bytes]:
        return self._chunks()

    async def _chunks(self) -> AsyncIterator[bytes]:
        try:
            async for chunk in self._response.aiter_bytes():
                yield chunk
        except httpx.HTTPError:
            raise SpeakError("audio_incomplete", "The audio ended early: the utterance failed.",
                             self.request_id) from None

    async def aclose(self) -> None:
        await self._response.aclose()

    async def __aenter__(self) -> "AsyncSpeechStream":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()


def synthesize_stream(client: "VerbexSpeech", *, model: str, voice: str, text: str, format: str = "pcm",
                      request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                      tags: Optional[List[str]] = None) -> SpeechStream:
    body = c.speak_body(model, voice, text, request_id, settings)
    params = c.speak_params(format, tags)
    return SpeechStream(client._request("speak", "POST", "/v1/speak", params=params, json=body,
                                        timeout=c.SPEAK_TIMEOUT, stream=True))


def synthesize(client: "VerbexSpeech", *, model: str, voice: str, text: str, format: str = "pcm",
               request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
               tags: Optional[List[str]] = None) -> SpeechAudio:
    """The whole utterance: raw PCM 16 kHz mono s16le, or WAV with format="wav"."""
    with synthesize_stream(client, model=model, voice=voice, text=text, format=format, request_id=request_id,
                           settings=settings, tags=tags) as stream:
        data = b"".join(stream)
        return SpeechAudio(data, stream.content_type, stream.request_id)


async def synthesize_stream_async(client: "AsyncVerbexSpeech", *, model: str, voice: str, text: str,
                                  format: str = "pcm", request_id: Optional[str] = None,
                                  settings: Optional[Mapping[str, Any]] = None,
                                  tags: Optional[List[str]] = None) -> AsyncSpeechStream:
    body = c.speak_body(model, voice, text, request_id, settings)
    params = c.speak_params(format, tags)
    return AsyncSpeechStream(await client._request("speak", "POST", "/v1/speak", params=params, json=body,
                                                   timeout=c.SPEAK_TIMEOUT, stream=True))


async def synthesize_async(client: "AsyncVerbexSpeech", *, model: str, voice: str, text: str, format: str = "pcm",
                           request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                           tags: Optional[List[str]] = None) -> SpeechAudio:
    async with await synthesize_stream_async(client, model=model, voice=voice, text=text, format=format,
                                             request_id=request_id, settings=settings, tags=tags) as stream:
        data = b"".join([chunk async for chunk in stream])
        return SpeechAudio(data, stream.content_type, stream.request_id)
