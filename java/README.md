# Verbex Speech SDK for Java

Client for the Verbex Speech API: speech-to-text (file jobs and live sessions) and text-to-speech
(one utterance or many over one socket), plus webhook signature verification.

- Java 17 or later. One runtime dependency: `jackson-databind`.
- HTTP and WebSocket through the JDK (`java.net.http`).
- All calls are synchronous. The client is thread-safe; build one and share it.

The behaviour (names, defaults, retries, errors) follows `../SPEC.md`, which the Python and Node.js
SDKs follow too. The API itself is described in the developer guide (`documents/user-guides/api.html`).

## Installation

Maven:

```xml
<dependency>
  <groupId>ai.verbex</groupId>
  <artifactId>verbex-speech-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("ai.verbex:verbex-speech-sdk:0.1.0")
```

Gradle (Groovy):

```groovy
implementation 'ai.verbex:verbex-speech-sdk:0.1.0'
```

Build from source: `mvn install` in this directory (installs `0.1.0-SNAPSHOT` into your local
repository).

## Packages

| Package | Responsibility |
|---|---|
| `ai.verbex.speech.sdk` | `VerbexSpeech`: the client and its builder |
| `…sdk.listen` | `Listen`: every speech-to-text call, in one place |
| `…sdk.listen.model` | The models: `SttModel`, `ModelMode`, `ModelFeature` |
| `…sdk.listen.job` | File transcription: `TranscribeRequest`, `Receipt`, `Job`, `JobError`, `Segment`, `UploadException`, `WaitTimeoutException` |
| `…sdk.listen.stream` | Live transcription: `ListenSession`, `ListenEvent` |
| `…sdk.listen.webhook` | Verifying a finished job's callback: `Webhooks`, `WebhookVerificationException` |
| `…sdk.speak` | `Speak`: every text-to-speech call, in one place; `SpeakRequest`, `AudioFormat`, `SpeakException` |
| `…sdk.speak.voice` | The voices: `Voice` |
| `…sdk.speak.synthesis` | One utterance over HTTP: `SpeakAudio`, `SpeakAudioStream` |
| `…sdk.speak.stream` | Many utterances over a socket: `SpeakSession`, `SayResult`, `SpeakDone` |
| `…sdk.error` | The exceptions every part of the SDK shares: `VerbexException` (the base of all), `ApiException` and its HTTP subtypes, `ApiConnectionException`, `ApiTimeoutException`, `ConfigurationException`, `StreamException`, `FieldError` |
| `…sdk.transport` | The SDK's own HTTP and WebSocket machinery, JSON mapping and retry rules |

Call everything through `client.listen()` and `client.speak()`. The classes that do the work behind
them (`Models`, `Jobs`, `LiveSessions`, `Voices`, `Synthesizer`, `SpeakSessions`) and everything in
`transport` are public only so the packages can share them: they are **not part of the API** and may
change in any release.

## Create a client

```java
import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.error.*;
import ai.verbex.speech.sdk.listen.Listen;
import ai.verbex.speech.sdk.listen.job.*;
import ai.verbex.speech.sdk.listen.stream.*;
import ai.verbex.speech.sdk.speak.*;

VerbexSpeech client = VerbexSpeech.builder()
        .apiKey("vbx_...")                                  // default: env VERBEX_API_KEY
        .baseUrl("https://speech-api-stage.verbex.ai")      // default: env VERBEX_BASE_URL
        .timeout(Duration.ofSeconds(30))                    // default 30 s per HTTP call
        .maxRetries(2)                                      // default 2; 0 turns retries off
        .userAgentSuffix("my-app/1.4")                      // optional
        .build();
```

A missing or malformed key or base URL throws `ConfigurationException` from `build()`. There is no
built-in base URL. A trailing slash is ignored; WebSockets use the same host with `ws`/`wss`.

Every request carries `Authorization: Token <key>`, `User-Agent: verbex-speech-java/<version>` and a
fresh `X-Trace-Id` (a UUID). `TranscribeRequest` and `SpeakRequest` accept your own `traceId(...)`.
The key is never logged, never in an exception message or `toString()`, and never sent to any host
but the base URL.

## Speech-to-text

### Models

```java
for (SttModel m : client.listen().models()) {
    System.out.println(m.model() + " " + m.language());
}
```

### Transcribe a file

```java
Job job = client.listen().transcribe(Path.of("call.wav"), "verbex_jp_stt_x1", "ja");
if (job.isCompleted()) {
    System.out.println(job.text());
} else {
    System.out.println(job.status() + " " + job.error());     // failed or cancelled: not an exception
}
```

With every option:

```java
TranscribeRequest request = TranscribeRequest.builder()
        .file(Path.of("call.wav"))          // or .bytes(byte[]), .stream(InputStream, size), .url("https://...")
        .model("verbex_jp_stt_x1").language("ja")
        .filename("call.wav")               // for bytes/streams; default: the path's name, else audio.wav
        .hotwords(List.of("かいみょう"))
        .metadata(Map.of("call_id", "c-42"))
        .webhookUrl("https://example.com/stt/done")
        .tags(List.of("campaign-7"))
        .idempotencyKey("call-c-42")        // optional; default: a random UUID per submit call
        .build();

Receipt receipt = client.listen().submit(request);            // job_id, status, queue_position, replayed
Job job = client.listen().waitFor(receipt.jobId(), Duration.ofSeconds(2), Duration.ofMinutes(10));
```

How the audio goes, by size:

| Size | Path |
|---|---|
| up to 1,048,576 bytes | in the request (`POST /v1/listen`, multipart) |
| up to 104,857,600 bytes | the upload flow: `POST /v1/listen/uploads`, one `PUT` to storage, then `POST /v1/listen` with the `upload_id` |
| larger | refused locally with `IllegalArgumentException`; nothing is sent |
| `url(...)` | `POST /v1/listen` as JSON |

Submitting is given 330 s (the gateway's limit) rather than the client timeout.

Every submit sends an `Idempotency-Key` header: yours from `idempotencyKey(...)`, else a random UUID
the SDK makes once per `submit`/`transcribe` call. Every retry of that call sends the same key,
including the final submit of the upload flow, so a retry after a lost answer gets the first
request's receipt instead of creating a second job. The API keeps a key's receipt for 24 hours, per
workspace. If you may submit the same work again from another process (after a restart, say), pass
your own key and save it with the work. A key must be 1 to 255 visible ASCII characters; anything
else is refused by `build()` with `IllegalArgumentException`, before anything is sent.

`receipt.replayed()` is true when the API answered with `Idempotent-Replayed: true`: the job was made
by an earlier request with the same key, and nothing new was submitted. Sending the same key with a
different request is answered `422 idempotency_key_reused` (a `BadRequestException`, not retried):
use a new key for each new piece of work.

The upload `PUT` sends the whole file with exactly the headers the API returned and none of the
SDK's own: no `Authorization`, no `X-Trace-Id`. `java.net.http` does not let a caller set
`Content-Length`, so it comes from the body's declared length (the file size); the JDK also adds its
own `Host` and `User-Agent`. The `PUT` has no timeout beyond the connect timeout, is never retried,
and a failure is an `UploadException` with the storage status (`412`: the URL was already used).
The upload URL is a credential and never appears in a message or `toString()`. A `409
upload_incomplete` on the final submit is retried up to `maxRetries` times, 1 s apart.

`waitFor` polls every 2 s by default (never faster than 0.5 s) until `completed`, `failed` or
`cancelled`. A `429` while polling waits `Retry-After` and carries on. Past its timeout it throws
`WaitTimeoutException`, whose `lastJob()` is the last job read.

### Live transcription

The session is blocking: `nextEvent()` waits for the next event and returns `null` after `done`.
`send`, `configure` and `finish` may be called from another thread while one thread reads events.

```java
try (ListenSession session = client.listen().stream("verbex_jp_stt_x1", "ja",
        List.of("かいみょう"), null)) {                   // returns once the server's ready frame arrives
    sender.start();                                      // calls session.send(pcm) at the audio's pace,
                                                         // then session.finish()
    ListenEvent event;
    while ((event = session.nextEvent()) != null) {
        if (event instanceof ListenEvent.SegmentEvent s) {
            System.out.println(s.text());
        } else if (event instanceof ListenEvent.Configured c) {
            System.out.println("hotwords now " + c.hotwords());
        } else if (event instanceof ListenEvent.Error e) {
            System.out.println("error " + e.code());     // e.g. a refused configure; the session stays open
        }
    }
} catch (StreamException e) {
    if (e.retryable()) { /* reconnect and resume sending audio */ }
}
```

- Audio is raw PCM: 16 kHz, mono, signed 16-bit little-endian. Send it at the pace it is produced
  (pieces of 0.2 to 0.5 s). Frames over 65,536 bytes are split.
- `configure(hotwords)` changes the hotwords; the answer is a `Configured` or an `Error` event.
- `finish()` sends `eof`; the remaining segments and `Done` follow. `send` after `finish` throws
  `IllegalStateException`.
- `close()` without `finish()` sends `eof`, waits up to 10 s for `done` (events not yet read are
  dropped), then closes.
- When the session ends without `done`, `nextEvent()` throws `StreamException`: with the code of the
  error frame that came before the close (which is first delivered as an `Error` event), else
  `stream_closed`. `retryable()` is true for close codes 1006, 1011 and 1013 and for the codes
  `stream_failed`, `service_unavailable`, `credit_unavailable` and `engine_unavailable`.
- A handshake refused with an HTTP status throws that status's `ApiException` subtype (for example
  `AuthenticationException` for 401). Pings are answered automatically.

`transcribeStream(chunks, model, language, hotwords, tags)` opens a session, sends every chunk,
finishes, and returns all segments in order. It does not pace the audio: pass an `Iterable` that
paces itself. See `LiveSessionExample`.

## Text-to-speech

### Voices

```java
List<Voice> voices = client.speak().voices();
```

### One utterance (HTTP)

```java
SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder()
        .model("verbex_v1_bn").voice("maria_2").text("আপনার অর্ডারটি আজ বিকেলে পৌঁছে যাবে।")
        .requestId("order-42")                          // optional; echoed as requestId()
        .settings(Map.of("temperature", 0.7))           // optional, sent as top-level fields
        .format(AudioFormat.PCM)                        // PCM (default) or WAV
        .build());
Files.write(Path.of("speech.pcm"), audio.audio());
```

To play while it is synthesised, stream it. The headers are available before the first byte:

```java
try (SpeakAudioStream in = client.speak().synthesizeStream(request)) {
    System.out.println(in.contentType() + " " + in.requestId());
    in.transferTo(player);
}
```

The call is given 360 s until the first audio. A body that ends with a transport error throws
`SpeakException` with code `audio_incomplete` (from `read` when streaming); it is never returned as
shorter audio. Retries happen only before any audio has arrived.

### Many utterances (WebSocket)

```java
try (SpeakSession session = client.speak().stream()) {   // usable at once; no ready frame
    SayResult first = session.say("verbex_v1_bn", "maria_2", "প্রথম বাক্য।");
    System.out.println(first.audio().length + " bytes, " + first.audioSeconds() + " s");

    // Or hand each PCM frame to a consumer as it arrives:
    SpeakDone done = session.say("verbex_v1_bn", "maria_2", "দ্বিতীয় বাক্য।", "turn-2", Map.of(),
            chunk -> player.write(chunk));
}
```

Utterances are one at a time: a `say` while another is in progress throws `IllegalStateException`.
Without a request id the SDK generates one (a UUID). A failed utterance throws `SpeakException`
(`code`, `speakMessage`, `requestId`, `fields`) and the session stays open. An error that closes the
socket (limits, `service_unavailable`, a revoked key) throws `StreamException`, now and on later
`say` calls.

## Webhooks

```java
// rawBody: the exact bytes of the request body; headers: the request headers (any case)
if (!Webhooks.verify(apiKey, rawBody, headers)) {
    return 401;
}
Job job = Webhooks.parse(apiKey, rawBody, headers);       // verify, then parse; throws WebhookVerificationException
```

`verify(apiKey, rawBody, timestamp, signature, tolerance, now)` takes the two header values
directly. The default tolerance is 300 s. It never throws for bad input; it returns `false`.

## Errors

All errors are unchecked and extend `VerbexException`. The spec's names map to these classes:

| Spec (§7) | Java | When |
|---|---|---|
| `VerbexError` | `VerbexException` | Base type |
| `ConfigurationError` | `ConfigurationException` | Missing or invalid key or base URL at `build()` |
| `ApiError` | `ApiException` | An HTTP error: `status()`, `code()`, `apiMessage()`, `requestId()`, `fields()`, `retryAfter()` |
| `BadRequestError` | `BadRequestException` | 400, 405, 406, 409, 413, 415, 422 |
| `AuthenticationError` | `AuthenticationException` | 401 |
| `PaymentRequiredError` | `PaymentRequiredException` | 402 |
| `PermissionDeniedError` | `PermissionDeniedException` | 403 |
| `NotFoundError` | `NotFoundException` | 404 |
| `RateLimitError` | `RateLimitException` | 429 |
| `ServerError` | `ServerException` | 5xx |
| `ConnectionError` | `ApiConnectionException` | No response: DNS, connect, TLS, reset |
| `TimeoutError` | `ApiTimeoutException` | No response within the timeout |
| `UploadError` | `UploadException` | The storage `PUT` failed: `status()` (0 when no answer arrived) |
| `StreamError` | `StreamException` | A WebSocket session ended by an error: `code()`, `streamMessage()`, `closeCode()`, `retryable()` |
| `SpeakError` | `SpeakException` | One utterance failed |
| `WaitTimeoutError` | `WaitTimeoutException` | `waitFor` ran past its timeout: `lastJob()` |
| `WebhookVerificationError` | `WebhookVerificationException` | `Webhooks.parse` with a bad signature |

Other statuses produce a plain `ApiException`. A body that is not the API's JSON (a proxy's HTML
page) still gives the right type by status, with code `http_<status>`. `requestId()` is the body's
`error.request_id`, else the response's `X-Trace-Id`: quote it to support.

Mistakes in the call itself (missing model, two sources, audio over 100 MiB, a bad idempotency key, a `say` while another
runs, `send` after `finish`) are `IllegalArgumentException` or `IllegalStateException`.

## Retries

Retries are automatic, up to `maxRetries` (default 2), with exponential backoff and full jitter from
0.5 s, capped at 8 s. `Retry-After` is honoured when present, up to 60 s; a longer wait is not taken
and the error is thrown.

| Call | Retried on |
|---|---|
| `models`, `voices`, `get`, starting an upload | connection errors, timeouts, 429 `rate_limited`/`workspace_rate_limited`, 502, 503, 504 |
| `synthesize`, `synthesizeStream` | as above, only before any audio has arrived |
| `submit` (and `transcribe`) | as for reads, and 409 `request_in_progress` (the first request with the same key is still running). Safe because every attempt carries the same `Idempotency-Key`: a retry after a lost answer gets the first receipt, never a second job. 422 `idempotency_key_reused` is not retried |
| storage `PUT` | never |
| WebSocket handshakes | never; reconnect yourself when `StreamException.retryable()` says so |

`429 concurrency_limit`/`workspace_concurrency_limit` is not retried: wait for your own running
work to finish.

## Examples

In `src/test/java/ai/verbex/speech/sdk/examples/` (compiled with the tests; run them from your IDE
with `VERBEX_API_KEY` and `VERBEX_BASE_URL` set):

- `TranscribeFileExample`: transcribe a file and print the segments.
- `LiveSessionExample`: a live session fed from a raw PCM file at real time.
- `SpeakToWavExample`: speak one sentence into a WAV file.

## Tests

```
mvn test
```

The suite loads the shared fixtures in `../conformance/` and uses local servers on ephemeral ports
only. `LiveSmokeTest` runs against a real API only when `VERBEX_API_KEY` and `VERBEX_BASE_URL` are
set; it uses the Japanese sample in `documents/stt-engine-documents/`. Never point it at production
from CI.
