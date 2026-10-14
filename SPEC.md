# Verbex Speech SDK specification

One behaviour, three languages. This document is the contract the Python, Node.js and Java SDKs
implement. Where a language has its own idiom (async iterators, `CompletableFuture`, context
managers), the SDK uses it, but the names, defaults, retry rules and error mapping below are the
same everywhere. The API itself is described in `documents/user-guides/api.html`; this document
says what the SDKs make of it.

## 1. Scope

The SDKs cover the API-key surface of the Speech API and nothing else:

| Area | Endpoints |
|---|---|
| Speech-to-text models | `GET /v1/listen/models` |
| File transcription | `POST /v1/listen` (multipart, up to 1 MiB), `POST /v1/listen/uploads` + `PUT <upload_url>` + `POST /v1/listen` (JSON, `upload_id`), `POST /v1/listen` (JSON, `url`) |
| Jobs | `GET /v1/listen/{job_id}` |
| Webhooks | signature verification only (no HTTP server) |
| Live transcription | `WS /v1/listen/stream` |
| Text-to-speech voices | `GET /v1/speak/voices` |
| Speak one utterance | `POST /v1/speak` |
| Speak many utterances | `WS /v1/speak/stream` |

Console-only features (usage, billing, keys, members) are out of scope: an API key cannot reach them.

## 2. Packages

| Language | Package | Import / module | Minimum runtime | Runtime dependencies |
|---|---|---|---|---|
| Python | PyPI `verbex-speech-sdk` | `verbex_speech` (flat), `.listen{,.models,.jobs,.stream,.webhook}`, `.speak{,.voices,.synthesis,.stream}`, `.errors` | Python 3.9 | `httpx`, `websockets` |
| Node.js | npm `@verbex-ai/verbex-speech-sdk` | `@verbex-ai/verbex-speech-sdk` (everything), `@verbex-ai/verbex-speech-sdk/listen`, `@verbex-ai/verbex-speech-sdk/speak` | Node.js 18 | `ws` |
| Java | Maven `ai.verbex:verbex-speech-sdk` | `ai.verbex.speech.sdk` (client), `.listen{,.model,.job,.stream,.webhook}`, `.speak{,.voice,.synthesis,.stream}`, `.error`, `.transport` (internal) | Java 17 | `jackson-databind` |

All three are organised the same way: the client; `listen` (models, jobs, live stream, webhook);
`speak` (voices, synthesis, stream); the errors shared by both; and a private transport layer (HTTP,
WebSocket, retries). An error only one product raises lives with that product.

Versions follow semantic versioning, independently of the services. The API version (`/v1`) is
fixed per major SDK version.

## 3. The client

Constructed with:

| Option | Default | Notes |
|---|---|---|
| `api_key` / `apiKey` | env `VERBEX_API_KEY` | Required; a missing key is a configuration error at construction, never a request that fails later. Must start with `vbx_` |
| `base_url` / `baseUrl` | env `VERBEX_BASE_URL` | Required. No built-in default: the production address is not fixed yet, and a wrong default is worse than none. A trailing slash is ignored. WebSockets use the same host with `ws`/`wss` |
| `timeout` | 30 s | Per HTTP call, except where a call's own default is longer (below) |
| `max_retries` / `maxRetries` | 2 | Automatic retries, per §8. `0` turns them off |
| `user_agent` suffix | none | Appended to `verbex-speech-<lang>/<version>` |

Every request carries `Authorization: Token <api_key>`, `User-Agent: verbex-speech-<lang>/<version>`
and a fresh `X-Trace-Id` (a UUID) unless the caller supplies one. The API key is never logged,
never put in an exception message, and never sent to any host but `base_url` (in particular, never
with the storage `PUT`).

The client exposes two namespaces: `listen` and `speak`.

## 4. Speech-to-text

### 4.1 `listen.models()`

`GET /v1/listen/models`. Returns the list of models (`items`), each with `model`, `display_name`,
`language` and `modes[]` (`mode`, `features[]` with `param_name`, `display_name`, `type`,
`max_items`, `description`). Unknown fields are kept or ignored, never an error.

### 4.2 `listen.transcribe(...)` and `listen.submit(...)`

One call for a file, whatever its size:

| Parameter | Type | Notes |
|---|---|---|
| `source` | path, bytes, or a readable stream with a known size | Exactly one of `source` and `url` |
| `url` | string | Audio at an https URL the API fetches |
| `model`, `language` | string | Required |
| `filename` | string | For bytes/streams; defaults to the path's name, else `audio.wav` |
| `hotwords` | list of strings | Optional |
| `metadata` | JSON object | Optional, at most 4096 bytes serialised (checked by the API) |
| `webhook_url` | string | Optional |
| `tags` | list of strings | Optional, sent as repeated `tag` query parameters |

- `submit(...)` returns the receipt: `job_id`, `status`, `queue_position` (optional).
- `transcribe(...)` = `submit(...)` then `wait(job_id)`; returns the finished job (§4.4).

How the audio goes:

| Source | Size | Path |
|---|---|---|
| bytes/path/stream | ≤ 1,048,576 bytes (`DIRECT_MAX_BYTES`) | `POST /v1/listen`, multipart: `file`, `model`, `language`, one `hotwords` part per term, `metadata` as JSON text, `webhook_url` |
| bytes/path/stream | > 1 MiB, ≤ 104,857,600 bytes (`UPLOAD_MAX_BYTES`) | upload flow (§4.3) |
| bytes/path/stream | > 100 MiB | refused locally with a validation error, nothing sent |
| `url` | — | `POST /v1/listen`, JSON: `url`, `model`, `language`, `hotwords` (array), `metadata` (object), `webhook_url` |

Submitting is given 330 s (the gateway's limit), not the client's default timeout.

### 4.3 The upload flow

1. `POST /v1/listen/uploads` `{size, filename}` → `201 {upload_id, upload_url, method, headers, upload_url_expires_in_seconds, submit_window_seconds}`.
2. `PUT upload_url` with the whole file and **every** header from `headers`, exactly, and nothing
   else of ours: no `Authorization`, no `X-Trace-Id`. `Content-Length` is the declared size. No
   timeout on the body transfer beyond the client's connect timeout: a large file on a slow line
   takes as long as it takes. A non-2xx answer is an `UploadError` with the storage status; `412`
   means the URL was already used.
3. `POST /v1/listen` JSON `{upload_id, model, language, hotwords?, metadata?, webhook_url?}` → the
   receipt. On `409 upload_incomplete` retry this step (up to `max_retries`, 1 s apart).

`upload_url` is a credential: never logged, never in an exception message or `toString`/`repr`.
The PUT is never retried automatically (the URL allows one write); a failure is reported, and the
caller starts again.

### 4.4 `listen.get(job_id)` and `listen.wait(job_id, ...)`

`get` is `GET /v1/listen/{job_id}`. The job has `job_id`, `status` (`queued`, `processing`,
`completed`, `failed`, `cancelled`), `segments[]` (`index`, `start_sec`, `end_sec`, `text`, always
present, possibly empty) and `metadata` (`{}` when none), and optionally `model`, `language`,
`audio_duration_sec`, `progress`, `segment_count`, `failed_segment_count`, `text` and `error`
(`code`, `message`). They are optional because a webhook body may leave some out.

`wait(job_id, poll_interval=2 s, timeout=None)` polls `get` until the status is `completed`,
`failed` or `cancelled`, then returns the job. It does not raise for `failed` or `cancelled`: the
caller reads `status` (and `error`). Exceeding `timeout` raises `WaitTimeoutError` carrying the last
job seen. Each poll counts against the key's requests per minute, so the interval is never below
0.5 s; a `429` during polling waits `Retry-After` and continues.

`job.completed` / `isCompleted()` is a convenience for `status == "completed"`.

### 4.5 Webhook verification

`verify_webhook(api_key, raw_body, timestamp, signature, tolerance=300 s, now=current time) -> bool`

- `signing_key = hex(HMAC-SHA256(key = api_key UTF-8, message = "verbex-webhook-v1"))`
- `expected = hex(HMAC-SHA256(key = signing_key UTF-8, message = timestamp + "." + raw_body))`
- `signature` must be `v1=<expected>`, compared in constant time.
- `|now - timestamp| > tolerance` is false. A timestamp that is not an integer is false.
- Never raises for bad input; returns false.

`parse_webhook(...)` = verify, then parse the body as a job; raises `WebhookVerificationError` if
the signature does not verify. The headers are `X-Verbex-Timestamp` and `X-Verbex-Signature`; each
SDK accepts a header map and finds them case-insensitively.

The test vectors in `conformance/webhook-vectors.json` are binding for all three SDKs.

### 4.6 Live transcription: `listen.stream(model, language, hotwords?, tags?)`

Opens `wss://<host>/v1/listen/stream?model=…&language=…&hotwords=…&tag=…` with the API key header,
and returns a session:

| Operation | Effect |
|---|---|
| open | resolves once the `ready` frame arrives; `session.session_id` is set. A handshake refused with an HTTP status raises the HTTP error (§7) |
| `send(pcm)` | one binary frame of raw PCM (16 kHz, mono, s16le). Frames above 65,536 bytes are split. Sending after `finish` is an error |
| `configure(hotwords)` | `{"type":"configure","hotwords":[…]}`; the answer arrives as a `configured` event (or an `error` event, session stays open) |
| `finish()` | `{"type":"eof"}`; the session then delivers the remaining segments and `done` |
| events | `segment` (`index`, `start_sec`, `end_sec`, `text`), `configured` (`hotwords`), `done` (`total_segments`), `error` (`code`, `message`) |
| close | after `done` the server closes 1000. An `error` followed by a close ends the session with a `StreamError(code, message, close_code)` |

The SDK never sends `eof` for the caller implicitly except when the caller closes the session
without having called `finish` (a context-manager exit, `close()`): then it sends `eof`, waits up to
10 s for `done`, and closes. Pings are answered automatically. A server close without `done` and
without an `error` frame is a `StreamError` with code `stream_closed` and the close code.

An `error` frame is delivered to the caller as an `error` event, because some do not end the session
(a refused `configure`). If the server then closes, the session ends with a `StreamError` carrying
that frame's `code` and `message` and the close code.

`StreamError.retryable` is true for close codes 1006, 1011 and 1013 and codes `stream_failed`,
`stream_closed`, `service_unavailable`, `credit_unavailable`, `engine_unavailable`: the caller may
reconnect and resume sending audio.

`listen.transcribe_stream(pcm_iterable, ...)` is a helper that opens a session, sends the chunks,
finishes, and returns all segments in order. It does not pace the audio; the caller does.

## 5. Text-to-speech

### 5.1 `speak.voices()`

`GET /v1/speak/voices`. Items with `model`, `model_display_name`, `code`, `display_name`,
`language`, `gender`, `style`, `sample_url` (nullable).

### 5.2 `speak.synthesize(model, voice, text, format="pcm", request_id=None, settings={}, tags=None)`

`POST /v1/speak?format=pcm|wav` with JSON `{model, voice, text, request_id?, …settings}` (settings
as top-level fields). Returns the whole audio with `content_type` and `request_id` (from
`X-Request-Id`). 360 s timeout. A body that ends with a transport error is a `SpeakError` with code
`audio_incomplete`, never short audio.

`speak.synthesize_stream(...)` takes the same arguments and yields the audio chunks as they arrive,
for playback that starts before synthesis ends. The response headers are available before the
first chunk.

### 5.3 Many utterances: `speak.stream()`

Opens `wss://<host>/v1/speak/stream` (optional `tag`s). No `ready` frame: the session is usable
once open.

`session.say(model, voice, text, request_id=None, settings={})` sends one JSON text frame and
yields the binary PCM frames until `{"event":"done", …}` (returned as the result: `request_id`,
`audio_seconds`), or raises `SpeakError(code, message, request_id, fields)` on
`{"event":"error", …}`; the session stays open after an utterance error. Utterances are strictly
one at a time: a `say` while another is in progress is an error in the SDK (the server would queue
it, and the audio of the two could not be told apart). When `request_id` is omitted the SDK
generates one (a UUID) so that answers can be matched.

An error frame followed by a close (limits, `service_unavailable`, a revoked key) ends the
session with a `StreamError`.

## 6. Types

Response objects are plain data (dataclasses, interfaces, records). Field names follow the
language's convention (`job_id` in Python, `jobId` in Node and Java), and unknown fields in a
response never fail parsing. Optional fields are `None`/`undefined`/`null` when absent.

## 7. Errors

One base type, `VerbexError`, with these subtypes:

| Type | When |
|---|---|
| `ConfigurationError` | Missing/invalid API key or base URL at construction |
| `ApiError` (base of the next six) | An HTTP error response. Carries `status`, `code`, `message`, `request_id` (from the body, else `X-Trace-Id`), `fields` (list of `{name, reason}`), `retry_after` (seconds, from `Retry-After`) |
| `BadRequestError` | 400, 405, 406, 409, 413, 415 |
| `AuthenticationError` | 401 |
| `PaymentRequiredError` | 402 |
| `PermissionDeniedError` | 403 |
| `NotFoundError` | 404 |
| `RateLimitError` | 429 |
| `ServerError` | 5xx |
| `ConnectionError` / `APIConnectionError` | No response: DNS, connect, TLS, reset |
| `TimeoutError` / `APITimeoutError` | No response within the timeout |
| `UploadError` | The storage PUT failed (status from storage, no API code) |
| `StreamError` | A WebSocket session ended by an error: `code`, `message`, `close_code`, `retryable` |
| `SpeakError` | One utterance failed: `code`, `message`, `request_id`, `fields` |
| `WaitTimeoutError` | `wait` ran past its timeout; carries the last job |
| `WebhookVerificationError` | `parse_webhook` with a bad signature |
| `ValidationError` | An argument refused before anything is sent: over 100 MiB, a stream without a size, a format other than `pcm`/`wav`, a setting that names a body field. `code` is the API's code for the same refusal (`validation_failed`, `audio_too_large`). In Python it is also a `ValueError`; Java uses `IllegalArgumentException` instead |

Misusing a session (sending after `finish`, a second `say` while one is in progress) is the
language's illegal-state error: Python `RuntimeError`, Node.js `ValidationError`, Java
`IllegalStateException`.

A non-JSON error body (a proxy's HTML page) still produces the right subtype by status, with
`code` = `http_<status>` and a generic message. Messages never contain the API key or an upload URL.

## 8. Retries

Automatic retries, at most `max_retries`, with exponential backoff and full jitter starting at
0.5 s and capped at 8 s, except that `Retry-After` is honoured when present (capped at 60 s; a
longer wait is not taken, the error is raised).

| Call | Retried on |
|---|---|
| `GET` (models, voices, job) | connection errors, timeouts, 429 `rate_limited`/`workspace_rate_limited`, 502, 503, 504 |
| `POST /v1/listen/uploads` | same as `GET` (an unused upload expires on its own) |
| `POST /v1/speak` | same as `GET`, only before any audio has been received (status arrives with the first audio, and nothing is billed without audio) |
| `POST /v1/listen` (submit) | **only** 429 `rate_limited`/`workspace_rate_limited` and 503 `engine_busy`/`service_unavailable`/`credit_unavailable`/`catalogue_unavailable` — answers that say nothing was created. Never on a timeout, any connection error (an HTTP client cannot always tell a failure before sending from one after), 502 or 504: a job may exist, and submitting twice creates two jobs |
| storage `PUT` | never |
| WebSocket handshakes | never (the caller reconnects; `StreamError.retryable` says when it makes sense) |

`429 concurrency_limit`/`workspace_concurrency_limit` is not retried automatically: waiting for the
caller's own work to finish is the caller's decision.

## 9. Conformance

`conformance/` holds JSON fixtures every SDK's tests load: example responses, error bodies and
their expected error type, retry decisions, and webhook signature vectors. A change to the
contract changes the fixtures first; all three test suites must pass against them.

Each SDK also has a live smoke test, skipped unless `VERBEX_API_KEY` and `VERBEX_BASE_URL` are set,
that lists models and voices, transcribes the 20 s Japanese sample, runs it through a live session
at real time, and speaks one sentence. It is never run in CI against production.
