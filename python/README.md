# verbex-speech-sdk (Python)

Python SDK for the Verbex Speech API: speech-to-text (files and live audio) and text-to-speech.
Python 3.9 or later. Synchronous (`VerbexSpeech`) and asynchronous (`AsyncVerbexSpeech`) clients
with the same calls.

## Installation

```sh
pip install verbex-speech-sdk
# or
poetry add verbex-speech-sdk
# or
uv add verbex-speech-sdk
```

The package is installed as `verbex-speech-sdk` and imported as `verbex_speech`:

```python
from verbex_speech import VerbexSpeech
```

From a checkout of this repository instead: `pip install ./python` (or `pip install -e ./python` to
edit the SDK as you use it).

## Quick start

1. Create an API key in the customer console (it starts with `vbx_`).
2. Tell the SDK the key and the API address:

   ```
   export VERBEX_API_KEY=vbx_...
   export VERBEX_BASE_URL=https://speech-api-stage.verbex.ai
   ```

3. Pick a model and a voice your workspace can use:

   ```python
   from verbex_speech import VerbexSpeech

   with VerbexSpeech() as client:
       for m in client.listen.models():
           print("stt", m.model, m.language, [mode.mode for mode in m.modes])
       for v in client.speak.voices():
           print("tts", v.model, v.code, v.language)
   ```

4. Transcribe a file and speak a reply, in one script:

   ```python
   from verbex_speech import APIError, VerbexSpeech

   with VerbexSpeech() as client:
       try:
           job = client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
       except APIError as error:
           raise SystemExit(f"{error.code}: {error.message} (request {error.request_id})")
       if not job.completed:
           raise SystemExit(f"job {job.job_id} {job.status}: {job.error}")
       print(job.text)

       # Voices are listed by language; take one that speaks the text's language.
       voice = next(v for v in client.speak.voices() if v.language == "ja")
       audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="ありがとうございました。",
                                       format="wav")
       with open("reply.wav", "wb") as f:
           f.write(audio.data)
   ```

Ready-to-run scripts are in `examples/`: `transcribe_file.py call.wav`, `live_from_wav.py call.wav`
(16 kHz mono 16-bit WAV) and `speak_to_wav.py out.wav`. Each reads the key and address from the
environment. The sections below cover every call and option.

## Set up

Create an API key in the customer console. The SDK reads the key and the API address from the
environment, or from the constructor:

```
export VERBEX_API_KEY=vbx_...
export VERBEX_BASE_URL=https://speech-api-stage.verbex.ai
```

```python
from verbex_speech import VerbexSpeech

client = VerbexSpeech()                       # or VerbexSpeech(api_key=..., base_url=...)
```

Keep the key secret: it is never logged by the SDK and never sent anywhere but the API.

## Transcribe a file

```python
job = client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
print(job.status, job.text)
for segment in job.segments:
    print(segment.start_sec, segment.end_sec, segment.text)
```

WAV or MP3, up to 100 MiB. A file of up to 1 MiB is sent in the request; a larger one goes straight
to storage through the upload flow, without passing through the API. Either way the call returns
the finished job. It does not raise for a failed job: check `job.completed` or `job.status`, and
`job.error`.

Options: `hotwords=[...]`, `metadata={...}`, `webhook_url=...`, `tags=[...]`, `filename=`,
`size=` (for a stream). A `bytes` object, a path or an open binary file all work. To audio already
in your storage, pass `url="https://..."` instead of a file.

Without waiting:

```python
receipt = client.listen.submit("call.wav", model="verbex_jp_stt_x1", language="ja",
                               webhook_url="https://example.com/stt/done")
job = client.listen.get(receipt.job_id)           # or client.listen.wait(receipt.job_id)
```

A finished job is kept for 10 minutes.

### Submitting safely more than once

Every submit carries an `Idempotency-Key` header, so a submit whose answer was lost (a timeout, a
dropped connection) is retried without making a second job: the API answers the retry with the
first request's receipt, and `receipt.replayed` is `True`. The SDK makes a new key for each
`submit` or `transcribe` call and reuses it for that call's retries.

If your process may submit the same work again after a restart, pass your own key, saved with the
work, so the API recognises it for up to 24 hours:

```python
receipt = client.listen.submit("call.wav", model="verbex_jp_stt_x1", language="ja",
                               idempotency_key=f"call-{call_id}")
```

A key is 1 to 255 visible ASCII characters. Reusing a key for a different request raises
`BadRequestError` with code `idempotency_key_reused` (422).

### Verify a webhook

```python
from verbex_speech import parse_webhook, WebhookVerificationError

def handle(raw_body: bytes, headers: dict):
    try:
        job = parse_webhook(API_KEY, raw_body, headers)   # the key that submitted the job
    except WebhookVerificationError:
        return 401
    ...
```

Pass the body exactly as received. `verify_webhook(api_key, raw_body, timestamp, signature)` returns
a bool if you only want the check.

## Live transcription

```python
with client.listen.stream(model="verbex_jp_stt_x1", language="ja") as session:
    # send raw PCM: 16 kHz, mono, signed 16-bit little-endian, at the pace it is produced
    session.send(pcm_chunk)
    ...
    session.finish()
    for event in session:
        if event.type == "segment":
            print(event.text)
```

Send from one thread and read in another, as `examples/live_from_wav.py` does. Events are
`segment`, `configured` (after `session.configure(hotwords)`), `done` and `error`. Leaving the
`with` block without `finish()` sends the end of audio for you and waits briefly for the last
segments. A session the server ends raises `StreamError`; when `error.retryable` is true, reconnect
and resume sending audio.

## Text-to-speech

```python
voice = next(v for v in client.speak.voices() if v.language == "ja")
audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="こんにちは", format="wav")
open("hello.wav", "wb").write(audio.data)
```

The default format is raw PCM (16 kHz, mono, 16-bit). To start playback before the utterance is
complete:

```python
with client.speak.synthesize_stream(model=voice.model, voice=voice.code, text="...") as stream:
    for chunk in stream:
        player.write(chunk)
```

Many utterances over one connection, one at a time:

```python
with client.speak.stream() as session:
    for line in lines:
        utterance = session.say(model=voice.model, voice=voice.code, text=line)
        for chunk in utterance:
            player.write(chunk)
        print(utterance.done.audio_seconds)
```

A failed utterance raises `SpeakError`; the session stays open for the next one.

## Async

```python
from verbex_speech import AsyncVerbexSpeech

async with AsyncVerbexSpeech() as client:
    job = await client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
    async with client.listen.stream(model="verbex_jp_stt_x1", language="ja") as session:
        await session.send(pcm_chunk)
        await session.finish()
        async for event in session:
            ...
```

## Errors

Every error is a `VerbexError`.

| Error | When |
|---|---|
| `ConfigurationError` | No or malformed API key or base URL |
| `ValidationError` (also a `ValueError`) | Refused before sending: over 100 MiB, no size for a stream, a bad format |
| `BadRequestError` | 400, 405, 406, 409, 413, 415, 422 |
| `AuthenticationError` | 401 |
| `PaymentRequiredError` | 402: no plan, not enough credit, an overdue invoice |
| `PermissionDeniedError` | 403: the key lacks the permission |
| `NotFoundError` | 404 |
| `RateLimitError` | 429 (`retry_after` in seconds for the rate limits) |
| `ServerError` | 5xx |
| `APIConnectionError`, `APITimeoutError` | No answer |
| `UploadError` | The storage upload failed |
| `StreamError` | A WebSocket session ended by an error (`code`, `close_code`, `retryable`) |
| `SpeakError` | One utterance failed (`code`, `request_id`, `fields`) |
| `WaitTimeoutError` | `wait(..., timeout=)` ran out; `error.job` is the last state |

API errors carry `status`, `code` (branch on this), `message`, `request_id` (quote it to support)
and `fields` for `validation_failed`.

## Retries

Up to `max_retries` (default 2) automatic retries with exponential backoff, honouring
`Retry-After` up to 60 seconds:

- reads, upload starts, speech and submits: on connection errors, timeouts, 429 rate limits, 502,
  503, 504. A submit is safe to retry because it carries its idempotency key (above); it is also
  retried on `409 request_in_progress`, while the first request with its key still runs;
- the storage upload and WebSocket handshakes: never.

`429 concurrency_limit` is not retried: wait for some of your own running work to finish.

## Modules

Every public name can be imported from `verbex_speech` directly. The package is split by
responsibility, the same way as the Java SDK:

```
verbex_speech/
  client.py            VerbexSpeech, AsyncVerbexSpeech: configuration, HTTP client, .listen and .speak
  errors.py            the errors every product shares (VerbexError, APIError and its subclasses,
                       ConfigurationError, ValidationError, APIConnectionError, APITimeoutError, StreamError)
  listen/              Listen, AsyncListen (client.listen)
    models.py          ListenModel, ModelMode, Feature; listing models
    jobs.py            Job, SubmitReceipt, UploadStarted, Segment, JobError, UploadError, WaitTimeoutError;
                       submit, the upload flow, get, wait, transcribe
    stream.py          ListenEvent, LiveSession, AsyncLiveSession; transcribe_stream
    webhook.py         verify_webhook, parse_webhook, signing_key, WebhookVerificationError
  speak/               Speak, AsyncSpeak (client.speak), SpeakError
    voices.py          Voice; listing voices
    synthesis.py       SpeechAudio, SpeechStream, AsyncSpeechStream; synthesize, synthesize_stream
    stream.py          SpeakSession, AsyncSpeakSession, Utterance, AsyncUtterance, SpeakDone
  _transport/          private: the HTTP request and retry loop, retry rules, WebSocket opening,
                       configuration and request-shape helpers
```

## Development

```
pip install -e '.[dev]'
pytest                      # the live tests run only with VERBEX_API_KEY and VERBEX_BASE_URL set
```

The tests load the shared fixtures in `../conformance/`, which the Node.js and Java SDKs test
against too. The behaviour all three share is specified in `../SPEC.md`.
