"""What the clients and products share: configuration, request shapes, audio sources."""
from __future__ import annotations

import io
import json
import os
import re
import uuid
from dataclasses import dataclass
from typing import Any, BinaryIO, Dict, Iterable, List, Mapping, Optional, Tuple, Union
from urllib.parse import quote, urlencode, urlsplit

from .. import __version__
from ..errors import ConfigurationError, FieldError, ValidationError

DIRECT_MAX_BYTES = 1_048_576
UPLOAD_MAX_BYTES = 104_857_600
MAX_FRAME_BYTES = 65_536
SUBMIT_TIMEOUT = 330.0
SPEAK_TIMEOUT = 360.0
CONNECT_TIMEOUT = 10.0
SPEAK_FIELDS = frozenset({"model", "voice", "text", "request_id"})
IDEMPOTENCY_HEADER = "Idempotency-Key"
_IDEMPOTENCY_KEY = re.compile(r"[\x21-\x7e]{1,255}")

Source = Union[bytes, bytearray, memoryview, str, "os.PathLike[str]", BinaryIO]


@dataclass(frozen=True)
class Config:
    api_key: str
    base_url: str
    timeout: float
    max_retries: int
    user_agent: str

    def __repr__(self) -> str:
        return f"Config(base_url={self.base_url!r}, timeout={self.timeout!r}, max_retries={self.max_retries!r})"


def resolve_config(api_key: Optional[str], base_url: Optional[str], timeout: float, max_retries: int,
                   user_agent: Optional[str]) -> Config:
    key = api_key if api_key is not None else os.environ.get("VERBEX_API_KEY")
    url = base_url if base_url is not None else os.environ.get("VERBEX_BASE_URL")
    if not key:
        raise ConfigurationError("No API key: pass api_key or set VERBEX_API_KEY.")
    if not key.startswith("vbx_"):
        raise ConfigurationError("The API key is not a Verbex key: it must start with vbx_.")
    if not url:
        raise ConfigurationError("No base URL: pass base_url or set VERBEX_BASE_URL.")
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise ConfigurationError("The base URL must be an http or https address.")
    if max_retries < 0:
        raise ConfigurationError("max_retries must be 0 or more.")
    agent = f"verbex-speech-python/{__version__}" + (f" {user_agent}" if user_agent else "")
    return Config(key, url.rstrip("/"), float(timeout), int(max_retries), agent)


def api_headers(config: Config, trace_id: str) -> Dict[str, str]:
    return {"Authorization": f"Token {config.api_key}", "User-Agent": config.user_agent, "X-Trace-Id": trace_id}


def new_trace_id() -> str:
    return str(uuid.uuid4())


def tag_params(tags: Optional[Iterable[str]]) -> List[Tuple[str, str]]:
    return [("tag", t) for t in tags or []]


def ws_url(config: Config, path: str, params: List[Tuple[str, str]]) -> str:
    base = config.base_url
    base = "wss" + base[5:] if base.startswith("https") else "ws" + base[4:]
    query = urlencode(params, quote_via=quote)
    return f"{base}{path}" + (f"?{query}" if query else "")


def local_error(message: str, field: Optional[str] = None, code: str = "validation_failed") -> ValidationError:
    return ValidationError(code, message, [FieldError(field, message)] if field else [])


@dataclass
class Audio:
    """An audio source with a known size, readable once (or re-readable when held in memory)."""

    size: int
    filename: str
    data: Optional[bytes] = None
    path: Optional[str] = None
    stream: Optional[BinaryIO] = None

    def read_all(self) -> bytes:
        if self.data is not None:
            return self.data
        if self.path is not None:
            with open(self.path, "rb") as f:
                return f.read()
        assert self.stream is not None
        return self.stream.read()

    def chunks(self, size: int = 1024 * 1024) -> Iterable[bytes]:
        if self.data is not None:
            for i in range(0, len(self.data), size):
                yield self.data[i:i + size]
            return
        handle = open(self.path, "rb") if self.path is not None else self.stream
        assert handle is not None
        try:
            remaining = self.size
            while remaining > 0:
                chunk = handle.read(min(size, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk
        finally:
            if self.path is not None:
                handle.close()


def open_audio(source: Any, size: Optional[int], filename: Optional[str]) -> Audio:
    if isinstance(source, (bytes, bytearray, memoryview)):
        data = bytes(source)
        audio = Audio(len(data), filename or "audio.wav", data=data)
    elif isinstance(source, (str, os.PathLike)):
        path = os.fspath(source)
        audio = Audio(os.path.getsize(path), filename or os.path.basename(path), path=path)
    elif hasattr(source, "read"):
        known = size
        if known is None:
            try:
                start = source.tell()
                source.seek(0, io.SEEK_END)
                known = source.tell() - start
                source.seek(start)
            except (AttributeError, OSError, ValueError):
                raise local_error("A stream needs its size: pass size=.", "size") from None
        name = getattr(source, "name", None)
        audio = Audio(int(known), filename or (os.path.basename(name) if isinstance(name, str) else "audio.wav"),
                      stream=source)
    else:
        raise local_error("The source must be bytes, a path or a readable stream.", "source")
    if audio.size <= 0:
        raise local_error("The audio is empty.", "source")
    if audio.size > UPLOAD_MAX_BYTES:
        raise local_error(f"The audio is {audio.size} bytes; at most {UPLOAD_MAX_BYTES} bytes can be sent.",
                          "source", "audio_too_large")
    return audio


def content_type_for(filename: str) -> str:
    lower = filename.lower()
    if lower.endswith(".mp3"):
        return "audio/mpeg"
    if lower.endswith(".wav"):
        return "audio/wav"
    return "application/octet-stream"


def submit_options(model: str, language: str, hotwords: Optional[List[str]], metadata: Optional[Mapping[str, Any]],
                   webhook_url: Optional[str]) -> None:
    if not model:
        raise local_error("model is required.", "model")
    if not language:
        raise local_error("language is required.", "language")
    if hotwords is not None and (isinstance(hotwords, str) or not all(isinstance(t, str) for t in hotwords)):
        raise local_error("hotwords must be a list of strings.", "hotwords")
    if metadata is not None and not isinstance(metadata, Mapping):
        raise local_error("metadata must be a JSON object.", "metadata")


def multipart_form(model: str, language: str, hotwords: Optional[List[str]], metadata: Optional[Mapping[str, Any]],
                   webhook_url: Optional[str]) -> Dict[str, Any]:
    form: Dict[str, Any] = {"model": model, "language": language}
    if hotwords:
        form["hotwords"] = list(hotwords)
    if metadata is not None:
        form["metadata"] = json.dumps(metadata, ensure_ascii=False)
    if webhook_url:
        form["webhook_url"] = webhook_url
    return form


def json_submit(base: Dict[str, Any], model: str, language: str, hotwords: Optional[List[str]],
                metadata: Optional[Mapping[str, Any]], webhook_url: Optional[str]) -> Dict[str, Any]:
    body = dict(base)
    body.update({"model": model, "language": language})
    if hotwords:
        body["hotwords"] = list(hotwords)
    if metadata is not None:
        body["metadata"] = dict(metadata)
    if webhook_url:
        body["webhook_url"] = webhook_url
    return body


def speak_body(model: str, voice: str, text: str, request_id: Optional[str],
               settings: Optional[Mapping[str, Any]]) -> Dict[str, Any]:
    clash = SPEAK_FIELDS.intersection(settings or {})
    if clash:
        raise local_error(f"settings cannot set {sorted(clash)[0]}.", sorted(clash)[0])
    body: Dict[str, Any] = {"model": model, "voice": voice, "text": text}
    if request_id is not None:
        body["request_id"] = request_id
    body.update(settings or {})
    return body


def idempotency_headers(idempotency_key: Optional[str]) -> Dict[str, str]:
    """The Idempotency-Key header a submit or a speak sends with every attempt: the caller's key, else a
    new UUID for this call, the same for each of its retries."""
    if idempotency_key is None:
        return {IDEMPOTENCY_HEADER: str(uuid.uuid4())}
    if not isinstance(idempotency_key, str) or not _IDEMPOTENCY_KEY.fullmatch(idempotency_key):
        raise local_error("idempotency_key must be 1 to 255 visible ASCII characters, without spaces.",
                          "idempotency_key")
    return {IDEMPOTENCY_HEADER: idempotency_key}


def replayed(headers: Mapping[str, str]) -> bool:
    """True when the answer came from an earlier request with the same Idempotency-Key."""
    return headers.get("Idempotent-Replayed", "").strip().lower() == "true"


def speak_params(format: str, tags: Optional[Iterable[str]]) -> List[Tuple[str, str]]:
    if format not in ("pcm", "wav"):
        raise local_error("format must be pcm or wav.", "format")
    return [("format", format)] + tag_params(tags)


def listen_stream_params(model: str, language: str, hotwords: Optional[List[str]],
                         tags: Optional[Iterable[str]]) -> List[Tuple[str, str]]:
    submit_options(model, language, hotwords, None, None)
    return [("model", model), ("language", language)] + [("hotwords", t) for t in hotwords or []] + tag_params(tags)


def frames(pcm: bytes) -> List[bytes]:
    data = bytes(pcm)
    return [data[i:i + MAX_FRAME_BYTES] for i in range(0, len(data), MAX_FRAME_BYTES)] or []
