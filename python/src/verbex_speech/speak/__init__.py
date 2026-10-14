"""Text-to-speech: voices (:mod:`.voices`), one utterance over HTTP (:mod:`.synthesis`) and many over one
socket (:mod:`.stream`). :class:`Speak` and :class:`AsyncSpeak` are what ``client.speak`` is."""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, List, Mapping, Optional

from ..errors import FieldError, VerbexError


class SpeakError(VerbexError):
    """One utterance on a speak socket failed. The socket stays open for the next one."""

    def __init__(self, code: str, message: str, request_id: Optional[str] = None,
                 fields: Optional[List[FieldError]] = None):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.request_id = request_id
        self.fields = fields or []


# Below SpeakError, which the submodules import from this package.
from . import stream, synthesis, voices  # noqa: E402
from .stream import AsyncSpeakSession, AsyncUtterance, SpeakDone, SpeakSession, Utterance  # noqa: E402
from .synthesis import AsyncSpeechStream, SpeechAudio, SpeechStream  # noqa: E402
from .voices import Voice  # noqa: E402

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech

__all__ = [
    "Speak", "AsyncSpeak", "SpeakError",
    "Voice",
    "SpeechAudio", "SpeechStream", "AsyncSpeechStream",
    "SpeakDone", "Utterance", "SpeakSession", "AsyncUtterance", "AsyncSpeakSession",
]


class Speak:
    """Text-to-speech: voices, one utterance over HTTP, and many over one socket."""

    def __init__(self, client: "VerbexSpeech"):
        self._client = client

    def voices(self) -> List[Voice]:
        return voices.list_voices(self._client)

    def synthesize_stream(self, *, model: str, voice: str, text: str, format: str = "pcm",
                          request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                          tags: Optional[List[str]] = None) -> SpeechStream:
        return synthesis.synthesize_stream(self._client, model=model, voice=voice, text=text, format=format,
                                           request_id=request_id, settings=settings, tags=tags)

    def synthesize(self, *, model: str, voice: str, text: str, format: str = "pcm",
                   request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                   tags: Optional[List[str]] = None) -> SpeechAudio:
        """The whole utterance: raw PCM 16 kHz mono s16le, or WAV with format="wav"."""
        return synthesis.synthesize(self._client, model=model, voice=voice, text=text, format=format,
                                    request_id=request_id, settings=settings, tags=tags)

    def stream(self, *, tags: Optional[List[str]] = None) -> SpeakSession:
        """Many utterances, one at a time, over one socket."""
        return stream.speak_session(self._client, tags=tags)


class AsyncSpeak:
    """Text-to-speech: voices, one utterance over HTTP, and many over one socket."""

    def __init__(self, client: "AsyncVerbexSpeech"):
        self._client = client

    async def voices(self) -> List[Voice]:
        return await voices.list_voices_async(self._client)

    async def synthesize_stream(self, *, model: str, voice: str, text: str, format: str = "pcm",
                                request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                                tags: Optional[List[str]] = None) -> AsyncSpeechStream:
        return await synthesis.synthesize_stream_async(self._client, model=model, voice=voice, text=text,
                                                       format=format, request_id=request_id, settings=settings,
                                                       tags=tags)

    async def synthesize(self, *, model: str, voice: str, text: str, format: str = "pcm",
                         request_id: Optional[str] = None, settings: Optional[Mapping[str, Any]] = None,
                         tags: Optional[List[str]] = None) -> SpeechAudio:
        """The whole utterance: raw PCM 16 kHz mono s16le, or WAV with format="wav"."""
        return await synthesis.synthesize_async(self._client, model=model, voice=voice, text=text, format=format,
                                                request_id=request_id, settings=settings, tags=tags)

    def stream(self, *, tags: Optional[List[str]] = None) -> AsyncSpeakSession:
        """Many utterances, one at a time, over one socket."""
        return stream.speak_session_async(self._client, tags=tags)
