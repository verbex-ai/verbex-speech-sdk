"""Speech-to-text: models (:mod:`.models`), file jobs (:mod:`.jobs`), live sessions (:mod:`.stream`) and
webhook verification (:mod:`.webhook`). :class:`Listen` and :class:`AsyncListen` are what ``client.listen`` is."""
from __future__ import annotations

from typing import TYPE_CHECKING, Any, List, Mapping, Optional

from . import jobs, models, stream
from .jobs import Job, JobError, Segment, SubmitReceipt, UploadError, UploadStarted, WaitTimeoutError
from .models import Feature, ListenModel, ModelMode
from .stream import AsyncLiveSession, ListenEvent, LiveSession
from .webhook import WebhookVerificationError, parse_webhook, signing_key, verify_webhook

if TYPE_CHECKING:
    from ..client import AsyncVerbexSpeech, VerbexSpeech

__all__ = [
    "Listen", "AsyncListen",
    "Feature", "ModelMode", "ListenModel",
    "SubmitReceipt", "UploadStarted", "Segment", "JobError", "Job", "UploadError", "WaitTimeoutError",
    "ListenEvent", "LiveSession", "AsyncLiveSession",
    "signing_key", "verify_webhook", "parse_webhook", "WebhookVerificationError",
]


class Listen:
    """Speech-to-text: models, file jobs, and live sessions."""

    def __init__(self, client: "VerbexSpeech"):
        self._client = client

    def models(self) -> List[ListenModel]:
        return models.list_models(self._client)

    def submit(self, source: Any = None, *, model: str, language: str, url: Optional[str] = None,
               size: Optional[int] = None, filename: Optional[str] = None, hotwords: Optional[List[str]] = None,
               metadata: Optional[Mapping[str, Any]] = None, webhook_url: Optional[str] = None,
               tags: Optional[List[str]] = None, idempotency_key: Optional[str] = None) -> SubmitReceipt:
        """Queues a file for transcription. Up to 1 MiB goes in the request, larger through storage.

        Sent with an Idempotency-Key, the same for each retry: ``idempotency_key`` if given, else a new UUID.
        """
        return jobs.submit(self._client, source, model=model, language=language, url=url, size=size,
                           filename=filename, hotwords=hotwords, metadata=metadata, webhook_url=webhook_url,
                           tags=tags, idempotency_key=idempotency_key)

    def start_upload(self, size: int, filename: Optional[str] = None) -> UploadStarted:
        """Step 1 of the upload flow, for callers that put the file themselves."""
        return jobs.start_upload(self._client, size, filename)

    def get(self, job_id: str) -> Job:
        return jobs.get(self._client, job_id)

    def wait(self, job_id: str, *, poll_interval: float = 2.0, timeout: Optional[float] = None) -> Job:
        """Polls until the job is completed, failed or cancelled, and returns it (it does not raise for failed)."""
        return jobs.wait(self._client, job_id, poll_interval=poll_interval, timeout=timeout)

    def transcribe(self, source: Any = None, *, model: str, language: str, poll_interval: float = 2.0,
                   wait_timeout: Optional[float] = None, **options: Any) -> Job:
        """Submits and waits: the finished job, with its transcript."""
        return jobs.transcribe(self._client, source, model=model, language=language, poll_interval=poll_interval,
                               wait_timeout=wait_timeout, **options)

    def stream(self, *, model: str, language: str, hotwords: Optional[List[str]] = None,
               tags: Optional[List[str]] = None) -> LiveSession:
        """A live session: send raw PCM (16 kHz mono s16le), read segments as they settle."""
        return stream.live_session(self._client, model=model, language=language, hotwords=hotwords, tags=tags)

    def transcribe_stream(self, pcm_chunks: Any, *, model: str, language: str,
                          hotwords: Optional[List[str]] = None, tags: Optional[List[str]] = None) -> List[Any]:
        """Sends every chunk, finishes, and returns the segments in order. The caller paces the chunks."""
        return stream.transcribe_stream(self.stream(model=model, language=language, hotwords=hotwords, tags=tags),
                                        pcm_chunks)


class AsyncListen:
    """Speech-to-text: models, file jobs, and live sessions."""

    def __init__(self, client: "AsyncVerbexSpeech"):
        self._client = client

    async def models(self) -> List[ListenModel]:
        return await models.list_models_async(self._client)

    async def submit(self, source: Any = None, *, model: str, language: str, url: Optional[str] = None,
                     size: Optional[int] = None, filename: Optional[str] = None,
                     hotwords: Optional[List[str]] = None, metadata: Optional[Mapping[str, Any]] = None,
                     webhook_url: Optional[str] = None, tags: Optional[List[str]] = None,
                     idempotency_key: Optional[str] = None) -> SubmitReceipt:
        """Queues a file for transcription. Up to 1 MiB goes in the request, larger through storage.

        Sent with an Idempotency-Key, the same for each retry: ``idempotency_key`` if given, else a new UUID.
        """
        return await jobs.submit_async(self._client, source, model=model, language=language, url=url, size=size,
                                       filename=filename, hotwords=hotwords, metadata=metadata,
                                       webhook_url=webhook_url, tags=tags, idempotency_key=idempotency_key)

    async def start_upload(self, size: int, filename: Optional[str] = None) -> UploadStarted:
        """Step 1 of the upload flow, for callers that put the file themselves."""
        return await jobs.start_upload_async(self._client, size, filename)

    async def get(self, job_id: str) -> Job:
        return await jobs.get_async(self._client, job_id)

    async def wait(self, job_id: str, *, poll_interval: float = 2.0, timeout: Optional[float] = None) -> Job:
        """Polls until the job is completed, failed or cancelled, and returns it (it does not raise for failed)."""
        return await jobs.wait_async(self._client, job_id, poll_interval=poll_interval, timeout=timeout)

    async def transcribe(self, source: Any = None, *, model: str, language: str, poll_interval: float = 2.0,
                         wait_timeout: Optional[float] = None, **options: Any) -> Job:
        """Submits and waits: the finished job, with its transcript."""
        return await jobs.transcribe_async(self._client, source, model=model, language=language,
                                           poll_interval=poll_interval, wait_timeout=wait_timeout, **options)

    def stream(self, *, model: str, language: str, hotwords: Optional[List[str]] = None,
               tags: Optional[List[str]] = None) -> AsyncLiveSession:
        """A live session: send raw PCM (16 kHz mono s16le), read segments as they settle."""
        return stream.live_session_async(self._client, model=model, language=language, hotwords=hotwords,
                                         tags=tags)

    async def transcribe_stream(self, pcm_chunks: Any, *, model: str, language: str,
                                hotwords: Optional[List[str]] = None, tags: Optional[List[str]] = None) -> List[Any]:
        """Sends every chunk (an iterable or async iterable), finishes, and returns the segments in order."""
        return await stream.transcribe_stream_async(
            self.stream(model=model, language=language, hotwords=hotwords, tags=tags), pcm_chunks)
