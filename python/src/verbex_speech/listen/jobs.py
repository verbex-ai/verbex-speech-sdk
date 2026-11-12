"""File transcription jobs: submit (in the request or through the upload flow), get, wait, transcribe."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, AsyncIterator, Dict, Iterable, List, Mapping, Optional

import httpx

from .._transport import common as c
from ..errors import BadRequestError, VerbexError

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech

FINISHED = frozenset({"completed", "failed", "cancelled"})


@dataclass(frozen=True)
class SubmitReceipt:
    """The answer to a submit: the job to poll.

    ``replayed`` is true when the job was made by an earlier request with the same idempotency key,
    and nothing was submitted now.
    """

    job_id: str
    status: str
    queue_position: Optional[int] = None
    replayed: bool = False

    @classmethod
    def from_dict(cls, d: Mapping[str, Any], replayed: bool = False) -> "SubmitReceipt":
        return cls(d.get("job_id", ""), d.get("status", ""), d.get("queue_position"), replayed)

    @classmethod
    def from_response(cls, response: httpx.Response) -> "SubmitReceipt":
        return cls.from_dict(response.json(), c.replayed(response.headers))


@dataclass(frozen=True)
class UploadStarted:
    upload_id: str
    # A credential for one write: kept out of repr, and so out of logs and tracebacks.
    upload_url: str = field(repr=False)
    method: str = "PUT"
    headers: Dict[str, str] = field(default_factory=dict)
    upload_url_expires_in_seconds: Optional[int] = None
    submit_window_seconds: Optional[int] = None

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "UploadStarted":
        headers = {str(k): str(v) for k, v in (d.get("headers") or {}).items() if isinstance(v, str)}
        return cls(d.get("upload_id", ""), d.get("upload_url", ""), d.get("method", "PUT"), headers,
                   d.get("upload_url_expires_in_seconds"), d.get("submit_window_seconds"))


@dataclass(frozen=True)
class Segment:
    index: int
    start_sec: float
    end_sec: float
    text: str

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Segment":
        return cls(int(d.get("index", 0)), float(d.get("start_sec", 0.0)), float(d.get("end_sec", 0.0)),
                   d.get("text") or "")


@dataclass(frozen=True)
class JobError:
    code: str
    message: str


@dataclass(frozen=True)
class Job:
    """A transcription job as it stands. ``wait`` returns one whose status is finished."""

    job_id: str
    status: str
    model: Optional[str] = None
    language: Optional[str] = None
    audio_duration_sec: Optional[float] = None
    progress: Optional[float] = None
    segment_count: Optional[int] = None
    failed_segment_count: Optional[int] = None
    text: Optional[str] = None
    segments: List[Segment] = field(default_factory=list)
    error: Optional[JobError] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def finished(self) -> bool:
        return self.status in FINISHED

    @property
    def completed(self) -> bool:
        return self.status == "completed"

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Job":
        error = d.get("error")
        return cls(
            job_id=d.get("job_id", ""), status=d.get("status", ""), model=d.get("model"),
            language=d.get("language"), audio_duration_sec=d.get("audio_duration_sec"),
            progress=d.get("progress"), segment_count=d.get("segment_count"),
            failed_segment_count=d.get("failed_segment_count"), text=d.get("text"),
            segments=[Segment.from_dict(s) for s in d.get("segments") or []],
            error=JobError(str(error.get("code", "")), str(error.get("message", ""))) if isinstance(error, dict) else None,
            metadata=d.get("metadata") or {})


class UploadError(VerbexError):
    """The storage PUT of the upload flow failed. ``status`` is the storage's, ``None`` when it never answered."""

    def __init__(self, status: Optional[int], message: str):
        super().__init__(message)
        self.status = status

    def __repr__(self) -> str:
        return f"UploadError(status={self.status!r})"


class WaitTimeoutError(VerbexError):
    """``wait`` ran past its timeout. ``job`` is the last state seen."""

    def __init__(self, job: Job):
        super().__init__(f"job {job.job_id} still {job.status} when the wait timed out")
        self.job = job


# Shared by both clients.

def _check_submit(source: Any, url: Optional[str], model: str, language: str, hotwords: Optional[List[str]],
                  metadata: Optional[Mapping[str, Any]], webhook_url: Optional[str],
                  idempotency_key: Optional[str]) -> Dict[str, str]:
    """The submit's checks, and the Idempotency-Key header it sends with every attempt."""
    if (source is None) == (url is None):
        raise c.local_error("Pass exactly one of source and url.", "source")
    c.submit_options(model, language, hotwords, metadata, webhook_url)
    return c.idempotency_headers(idempotency_key)


def _direct_files(audio: c.Audio) -> Dict[str, Any]:
    return {"file": (audio.filename, audio.read_all(), c.content_type_for(audio.filename))}


def _upload_body(size: int, filename: Optional[str]) -> Dict[str, Any]:
    body: Dict[str, Any] = {"size": size}
    if filename:
        body["filename"] = filename
    return body


def _put_headers(started: UploadStarted, audio: c.Audio) -> Dict[str, str]:
    headers = dict(started.headers)
    headers["Content-Length"] = str(audio.size)
    return headers


def _put_refused(status: int) -> UploadError:
    return UploadError(status, f"Storage refused the upload ({status})."
                       + (" The upload URL was already used." if status == 412 else ""))


# Synchronous.

def submit(client: "VerbexSpeech", source: Any = None, *, model: str, language: str, url: Optional[str] = None,
           size: Optional[int] = None, filename: Optional[str] = None, hotwords: Optional[List[str]] = None,
           metadata: Optional[Mapping[str, Any]] = None, webhook_url: Optional[str] = None,
           tags: Optional[List[str]] = None, idempotency_key: Optional[str] = None) -> SubmitReceipt:
    """Queues a file for transcription. Up to 1 MiB goes in the request, larger through storage."""
    headers = _check_submit(source, url, model, language, hotwords, metadata, webhook_url, idempotency_key)
    params = c.tag_params(tags)
    if url is not None:
        body = c.json_submit({"url": url}, model, language, hotwords, metadata, webhook_url)
        return submit_json(client, body, params, headers)
    audio = c.open_audio(source, size, filename)
    if audio.size <= c.DIRECT_MAX_BYTES:
        response = client._request(
            "submit", "POST", "/v1/listen", params=params,
            data=c.multipart_form(model, language, hotwords, metadata, webhook_url),
            files=_direct_files(audio), timeout=c.SUBMIT_TIMEOUT, headers=headers)
        return SubmitReceipt.from_response(response)
    started = start_upload(client, audio.size, audio.filename)
    put_upload(client, started, audio)
    body = c.json_submit({"upload_id": started.upload_id}, model, language, hotwords, metadata, webhook_url)
    return submit_json(client, body, params, headers)


def start_upload(client: "VerbexSpeech", size: int, filename: Optional[str] = None) -> UploadStarted:
    """Step 1 of the upload flow, for callers that put the file themselves."""
    return UploadStarted.from_dict(
        client._request("upload_start", "POST", "/v1/listen/uploads", json=_upload_body(size, filename)).json())


def put_upload(client: "VerbexSpeech", started: UploadStarted, audio: c.Audio) -> None:
    """Step 2: the storage PUT, with only the upload's headers. Never retried."""
    try:
        response = client._http.request(started.method or "PUT", started.upload_url,
                                        headers=_put_headers(started, audio),
                                        content=audio.data if audio.data is not None else audio.chunks(),
                                        timeout=httpx.Timeout(None, connect=c.CONNECT_TIMEOUT))
    except httpx.HTTPError as exc:
        raise UploadError(None, f"The upload to storage failed: {type(exc).__name__}.") from None
    if response.status_code >= 300:
        raise _put_refused(response.status_code)


def submit_json(client: "VerbexSpeech", body: Dict[str, Any], params: List[Any],
                headers: Dict[str, str]) -> SubmitReceipt:
    attempt = 0
    while True:
        try:
            response = client._request("submit", "POST", "/v1/listen", params=params, json=body,
                                       timeout=c.SUBMIT_TIMEOUT, headers=headers)
            return SubmitReceipt.from_response(response)
        except BadRequestError as error:
            # The file is still arriving at storage: submitting again is what the API asks for.
            if error.code != "upload_incomplete" or attempt >= client._config.max_retries:
                raise
            attempt += 1
            client._sleep(1.0)


def get(client: "VerbexSpeech", job_id: str) -> Job:
    return Job.from_dict(client._request("get", "GET", f"/v1/listen/{job_id}").json())


def wait(client: "VerbexSpeech", job_id: str, *, poll_interval: float = 2.0, timeout: Optional[float] = None) -> Job:
    """Polls until the job is completed, failed or cancelled, and returns it (it does not raise for failed)."""
    interval = max(0.5, poll_interval)
    started = client._monotonic()
    while True:
        job = get(client, job_id)
        if job.finished:
            return job
        if timeout is not None and client._monotonic() - started >= timeout:
            raise WaitTimeoutError(job)
        client._sleep(interval)


def transcribe(client: "VerbexSpeech", source: Any = None, *, model: str, language: str, poll_interval: float = 2.0,
               wait_timeout: Optional[float] = None, **options: Any) -> Job:
    """Submits and waits: the finished job, with its transcript."""
    receipt = submit(client, source, model=model, language=language, **options)
    return wait(client, receipt.job_id, poll_interval=poll_interval, timeout=wait_timeout)


# Asynchronous: the same flow, awaited.

async def submit_async(client: "AsyncVerbexSpeech", source: Any = None, *, model: str, language: str,
                       url: Optional[str] = None, size: Optional[int] = None, filename: Optional[str] = None,
                       hotwords: Optional[List[str]] = None, metadata: Optional[Mapping[str, Any]] = None,
                       webhook_url: Optional[str] = None, tags: Optional[List[str]] = None,
                       idempotency_key: Optional[str] = None) -> SubmitReceipt:
    headers = _check_submit(source, url, model, language, hotwords, metadata, webhook_url, idempotency_key)
    params = c.tag_params(tags)
    if url is not None:
        body = c.json_submit({"url": url}, model, language, hotwords, metadata, webhook_url)
        return await submit_json_async(client, body, params, headers)
    audio = c.open_audio(source, size, filename)
    if audio.size <= c.DIRECT_MAX_BYTES:
        response = await client._request(
            "submit", "POST", "/v1/listen", params=params,
            data=c.multipart_form(model, language, hotwords, metadata, webhook_url),
            files=_direct_files(audio), timeout=c.SUBMIT_TIMEOUT, headers=headers)
        return SubmitReceipt.from_response(response)
    started = await start_upload_async(client, audio.size, audio.filename)
    await put_upload_async(client, started, audio)
    body = c.json_submit({"upload_id": started.upload_id}, model, language, hotwords, metadata, webhook_url)
    return await submit_json_async(client, body, params, headers)


async def start_upload_async(client: "AsyncVerbexSpeech", size: int, filename: Optional[str] = None) -> UploadStarted:
    return UploadStarted.from_dict((await client._request(
        "upload_start", "POST", "/v1/listen/uploads", json=_upload_body(size, filename))).json())


async def put_upload_async(client: "AsyncVerbexSpeech", started: UploadStarted, audio: c.Audio) -> None:
    try:
        response = await client._http.request(
            started.method or "PUT", started.upload_url, headers=_put_headers(started, audio),
            content=audio.data if audio.data is not None else _aiter(audio.chunks()),
            timeout=httpx.Timeout(None, connect=c.CONNECT_TIMEOUT))
    except httpx.HTTPError as exc:
        raise UploadError(None, f"The upload to storage failed: {type(exc).__name__}.") from None
    if response.status_code >= 300:
        raise _put_refused(response.status_code)


async def submit_json_async(client: "AsyncVerbexSpeech", body: Dict[str, Any], params: List[Any],
                            headers: Dict[str, str]) -> SubmitReceipt:
    attempt = 0
    while True:
        try:
            response = await client._request("submit", "POST", "/v1/listen", params=params, json=body,
                                             timeout=c.SUBMIT_TIMEOUT, headers=headers)
            return SubmitReceipt.from_response(response)
        except BadRequestError as error:
            if error.code != "upload_incomplete" or attempt >= client._config.max_retries:
                raise
            attempt += 1
            await client._sleep(1.0)


async def get_async(client: "AsyncVerbexSpeech", job_id: str) -> Job:
    return Job.from_dict((await client._request("get", "GET", f"/v1/listen/{job_id}")).json())


async def wait_async(client: "AsyncVerbexSpeech", job_id: str, *, poll_interval: float = 2.0,
                     timeout: Optional[float] = None) -> Job:
    interval = max(0.5, poll_interval)
    started = client._monotonic()
    while True:
        job = await get_async(client, job_id)
        if job.finished:
            return job
        if timeout is not None and client._monotonic() - started >= timeout:
            raise WaitTimeoutError(job)
        await client._sleep(interval)


async def transcribe_async(client: "AsyncVerbexSpeech", source: Any = None, *, model: str, language: str,
                           poll_interval: float = 2.0, wait_timeout: Optional[float] = None, **options: Any) -> Job:
    receipt = await submit_async(client, source, model=model, language=language, **options)
    return await wait_async(client, receipt.job_id, poll_interval=poll_interval, timeout=wait_timeout)


async def _aiter(chunks: Iterable[bytes]) -> AsyncIterator[bytes]:
    for chunk in chunks:
        yield chunk
