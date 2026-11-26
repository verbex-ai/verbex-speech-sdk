# Python: verbex-speech-sdk

```sh
pip install verbex-speech-sdk        # or: poetry add / uv add verbex-speech-sdk
```

Imported as `verbex_speech`. Every public name can be imported from `verbex_speech` directly.

## Client

```python
from verbex_speech import VerbexSpeech

with VerbexSpeech() as client:       # reads VERBEX_API_KEY, and VERBEX_BASE_URL if set
    ...
# or VerbexSpeech(api_key=..., base_url=..., max_retries=2)
```

Async: `AsyncVerbexSpeech`, the same calls with `await`, `async with` and `async for`.

## Models and voices

```python
for m in client.listen.models():
    print(m.model, m.language, [mode.mode for mode in m.modes])
for v in client.speak.voices():
    print(v.model, v.code, v.language)
```

## Transcribe a file or a URL

```python
job = client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
if job.completed:
    print(job.text)
    for s in job.segments:
        print(s.start_sec, s.end_sec, s.text)
else:
    print(job.status, job.error)       # failed or cancelled: not an exception

job = client.listen.transcribe(url="https://storage.example.com/call.wav",
                               model="verbex_jp_stt_x1", language="ja")
```

The source can be a path, `bytes`, or an open binary file (give `size=` for a stream). WAV or MP3.
Options: `hotwords=[...]`, `metadata={...}` (returned with the job), `webhook_url=...`,
`tags=[...]`, `filename=...`, `idempotency_key=...`.

Without waiting:

```python
receipt = client.listen.submit("call.wav", model="verbex_jp_stt_x1", language="ja",
                               webhook_url="https://example.com/stt/done",
                               idempotency_key=f"call-{call_id}")   # optional; saved with the work
job = client.listen.wait(receipt.job_id)        # or client.listen.get(receipt.job_id) once
```

`receipt.replayed` is `True` when an earlier request with the same key already made the job. A
finished job is kept for 10 minutes.

## Webhook

```python
from verbex_speech import parse_webhook, WebhookVerificationError

def handle(raw_body: bytes, headers: dict):
    try:
        job = parse_webhook(API_KEY, raw_body, headers)   # the key that submitted the job
    except WebhookVerificationError:
        return 401
    ...
```

`verify_webhook(api_key, raw_body, timestamp, signature)` returns a bool.

## Live transcription

```python
with client.listen.stream(model="verbex_jp_stt_x1", language="ja") as session:
    session.send(pcm_chunk)            # 16 kHz mono s16le, at the pace it is produced
    ...
    session.finish()
    for event in session:
        if event.type == "segment":
            print(event.text)
```

Send from one thread and read in another. Events: `segment`, `configured` (after
`session.configure(hotwords)`), `done`, `error`. A session the server ends raises `StreamError`;
when `error.retryable` is true, reconnect and resume.

## Text-to-speech

```python
voice = next(v for v in client.speak.voices() if v.language == "ja")
audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="こんにちは", format="wav")
with open("hello.wav", "wb") as f:
    f.write(audio.data)
```

Default `format` is `"pcm"`. Also `request_id=`, `settings={...}`, `tags=[...]`,
`idempotency_key=` (one per utterance: a repeat with the same key is spoken again without charge,
up to 3 times in 24 hours).

Play while it is synthesised:

```python
with client.speak.synthesize_stream(model=voice.model, voice=voice.code, text="...") as stream:
    for chunk in stream:
        player.write(chunk)
```

Many utterances on one connection, one at a time:

```python
with client.speak.stream() as session:
    for line in lines:
        utterance = session.say(model=voice.model, voice=voice.code, text=line)
        for chunk in utterance:
            player.write(chunk)
```

A failed utterance raises `SpeakError`; the session stays open.

## Errors

```python
from verbex_speech import APIError, PaymentRequiredError, RateLimitError

try:
    job = client.listen.transcribe("call.wav", model=model, language=language)
except PaymentRequiredError as e:      # 402: no plan, not enough credit, overdue invoice
    ...
except RateLimitError as e:            # 429 after the SDK's own retries; e.retry_after
    ...
except APIError as e:                  # any HTTP error: status, code, request_id
    log.error("verbex %s %s request_id=%s", e.status, e.code, e.request_id)
    raise
```

All extend `VerbexError`; HTTP errors extend `APIError`. Types: `ConfigurationError`, `ValidationError` (refused before sending), `BadRequestError` (400,
405, 406, 409, 413, 415, 422), `AuthenticationError` (401), `PaymentRequiredError` (402),
`PermissionDeniedError` (403), `NotFoundError` (404), `RateLimitError` (429), `ServerError` (5xx),
`APIConnectionError`, `APITimeoutError`, `UploadError`, `StreamError`, `SpeakError`,
`WaitTimeoutError` (`error.job` is the last state).
