"""Verbex Speech API: speech-to-text and text-to-speech.

Modules: :mod:`.client` (the entry points), :mod:`.errors` (the shared errors), :mod:`.listen`
(speech-to-text) and :mod:`.speak` (text-to-speech). Every public name is also importable from here.
"""
__version__ = "0.1.0"

from .client import AsyncVerbexSpeech, VerbexSpeech  # noqa: E402
from .errors import (APIConnectionError, APIError, APITimeoutError, AuthenticationError,  # noqa: E402
                     BadRequestError, ConfigurationError, FieldError, NotFoundError, PaymentRequiredError,
                     PermissionDeniedError, RateLimitError, ServerError, StreamError, ValidationError, VerbexError)
from .listen import (Job, JobError, ListenEvent, ListenModel, Segment, SubmitReceipt,  # noqa: E402
                     UploadError, UploadStarted, WaitTimeoutError, WebhookVerificationError, parse_webhook,
                     verify_webhook)
from .speak import SpeakDone, SpeakError, SpeechAudio, Voice  # noqa: E402

__all__ = [
    "VerbexSpeech", "AsyncVerbexSpeech", "verify_webhook", "parse_webhook",
    "Job", "JobError", "ListenEvent", "ListenModel", "Segment", "SpeakDone", "SpeechAudio", "SubmitReceipt",
    "UploadStarted", "Voice",
    "VerbexError", "ConfigurationError", "APIError", "BadRequestError", "AuthenticationError",
    "PaymentRequiredError", "PermissionDeniedError", "NotFoundError", "RateLimitError", "ServerError",
    "APIConnectionError", "APITimeoutError", "UploadError", "StreamError", "SpeakError", "WaitTimeoutError",
    "WebhookVerificationError", "FieldError", "ValidationError",
]
