# @verbex-ai/verbex-speech-sdk

Node.js SDK for the Verbex Speech API: transcribe recorded files and live audio, and synthesise
speech. It covers the API-key surface of the API; console features (usage, billing, keys) are
not reachable with an API key and are not in the SDK.

- Node.js 18 or later. One runtime dependency: `ws`.
- ESM with TypeScript declarations.

## Installation

```sh
npm install @verbex-ai/verbex-speech-sdk
# or
pnpm add @verbex-ai/verbex-speech-sdk
# or
yarn add @verbex-ai/verbex-speech-sdk
```

### From GitHub

Until the package is on the npm registry, install it from this repository. npm cannot install a
subdirectory, so the repository root has its own `package.json`: it is this package, and its
`prepare` script builds `node/` when npm fetches it.

npm 12 refuses git dependencies, and blocks their `prepare` script, unless the project allows
both. Without the second setting the install succeeds but the package has no code in it. In your
project:

```sh
echo "allow-git=root" >> .npmrc
npm pkg set "allowScripts[github:verbex-ai/verbex-speech-sdk]=true" --json
npm install github:verbex-ai/verbex-speech-sdk     # or github:verbex-ai/verbex-speech-sdk#<tag or commit>
```

`allowScripts` matches a git dependency by its repository, not its package name, so
`npm install-scripts approve @verbex-ai/verbex-speech-sdk` does not work for it. Add `#<commit>` to
the key to approve one commit only. npm 11 and earlier need neither setting.

pnpm and Yarn can install the subdirectory directly
(`pnpm add "github:verbex-ai/verbex-speech-sdk#path:/node"`); `node/package.json` builds on
`prepare` for them.

Use it from your server, never from a browser: the API key must stay secret. For voice agents in
the browser, Verbex has a separate package, `@verbex-ai/verbex-js-sdk`.

## Configure

```js
import { VerbexSpeech } from "@verbex-ai/verbex-speech-sdk";

const client = new VerbexSpeech({
  apiKey: process.env.VERBEX_API_KEY, // default: env VERBEX_API_KEY; must start with vbx_
  baseUrl: process.env.VERBEX_BASE_URL, // default: env VERBEX_BASE_URL; required, no built-in default
  timeoutMs: 30_000, // per HTTP call (submit has 330 s, speak 360 s)
  maxRetries: 2, // automatic retries; 0 turns them off
  userAgent: "my-app/1.0", // optional suffix
});
```

A missing or malformed key or base URL throws `ConfigurationError` at construction. The key is
never logged, never put in an error message, and never sent anywhere but the base URL.
WebSockets use the same host with `ws`/`wss`.

Every request carries `Authorization: Token <key>`, `User-Agent: verbex-speech-node/<version>`
and a fresh `X-Trace-Id`. Pass `{ traceId }` to a call to use your own UUID.

## Speech-to-text

### Models

```js
const models = await client.listen.models();
// [{ model, displayName, language, modes: [{ mode, features: [{ paramName, ... }] }] }]
```

### Transcribe a file

```js
const job = await client.listen.transcribe({
  source: "meeting.wav", // a path, a Buffer/Uint8Array, or a Readable (then give `size`)
  model: "verbex_jp_stt_x1",
  language: "ja",
  hotwords: ["かいみょう"], // optional
  metadata: { call_id: "c-42" }, // optional, returned with the job
  tags: ["campaign-7"], // optional
});
if (job.completed) console.log(job.text);
else console.log(job.status, job.error);
```

One call works for any size:

| Source | Size | How it is sent |
|---|---|---|
| path, bytes, stream | up to 1 MiB | in the request (multipart) |
| path, bytes, stream | up to 100 MiB | the upload flow: start an upload, one PUT straight to storage, submit |
| path, bytes, stream | over 100 MiB | refused locally with `ValidationError`, nothing sent |
| `url` | (checked by the API) | the API fetches it |

The storage PUT carries exactly the headers the API gave for it, never your key. It is never
retried (the URL allows one write); a failure is an `UploadError`, and you start again.

`transcribe` is `submit` followed by `wait`. Use them apart to keep the job id:

```js
const receipt = await client.listen.submit({ url: "https://storage.example.com/a.wav?sig=...", model, language });
const job = await client.listen.wait(receipt.jobId, { pollIntervalMs: 2000, timeoutMs: 600_000 });
```

`wait` returns failed and cancelled jobs too (read `status` and `error`); past `timeoutMs` it
throws `WaitTimeoutError` with the last job seen. Polls are never closer than 0.5 s, and a 429
while polling waits `Retry-After` and carries on. Finished jobs are kept 10 minutes.

`client.listen.get(jobId)` reads a job once.

### Submitting safely more than once

Every submit sends an `Idempotency-Key` header. By default it is a random UUID made once per
`submit` or `transcribe` call and reused by every automatic retry of that call (including the
upload flow's final submit), so a retry after a lost answer gets the first request's receipt
instead of a second job. The API keeps a key's receipt for 24 hours, per workspace.

If your process may submit the same work again after a restart, pass your own key and save it
with the work:

```js
const receipt = await client.listen.submit({ source: "meeting.wav", model, language, idempotencyKey: "order-42-meeting" });
if (receipt.replayed) console.log("already submitted as", receipt.jobId);
```

- `idempotencyKey` must be 1 to 255 visible ASCII characters (no spaces); anything else throws
  `ValidationError` (`code` `validation_failed`) before anything is sent.
- `receipt.replayed` is `true` when the API answered `Idempotent-Replayed: true`: the job was made
  by an earlier request with the same key, and nothing new was submitted.
- The same key with a different request is refused with `BadRequestError` (422,
  `idempotency_key_reused`). Use a new key for each new piece of work.

### Webhooks

```js
import { parseWebhook, verifyWebhook } from "@verbex-ai/verbex-speech-sdk";

// rawBody must be the exact bytes received, before any JSON parsing.
const job = parseWebhook({ apiKey, rawBody, headers: req.headers }); // throws WebhookVerificationError
const ok = verifyWebhook({ apiKey, rawBody, headers: req.headers }); // returns true/false, never throws
```

The headers `X-Verbex-Timestamp` and `X-Verbex-Signature` are found in any letter case. A
timestamp more than 300 s from now fails (`toleranceSec` changes that).

### Live transcription

Raw PCM only: 16 kHz, mono, signed 16-bit little-endian, no header. Send it at the pace it is
produced, in 0.2 to 0.5 s pieces.

```js
const session = await client.listen.stream({ model: "verbex_jp_stt_x1", language: "ja", hotwords: ["かいみょう"] });
console.log(session.sessionId);

microphone.on("data", (pcm) => session.send(pcm)); // frames over 64 KiB are split
microphone.on("end", () => session.finish()); // sends eof; remaining segments and done follow

for await (const ev of session) {
  if (ev.type === "segment") console.log(ev.index, ev.startSec, ev.endSec, ev.text);
  if (ev.type === "configured") console.log("hotwords now", ev.hotwords);
  if (ev.type === "error") console.warn(ev.code, ev.message); // e.g. a refused configure; the session goes on
  if (ev.type === "done") console.log(ev.totalSegments, "segments");
}
await session.close();
```

- `session.configure(hotwords)` changes the hotwords mid-session.
- `session.close()` without `finish()` sends `eof` itself and waits up to 10 s for `done`.
- When the server ends the session with an error, the loop throws `StreamError` (`code`,
  `closeCode`, `retryable`). `retryable` says reconnecting and resuming makes sense.
- Sending after `finish()` throws `ValidationError`.

For audio you already have in chunks:

```js
const segments = await client.listen.transcribeStream(chunks, { model, language }); // does not pace for you
```

## Text-to-speech

### Voices

```js
const voices = await client.speak.voices();
// [{ model, modelDisplayName, code, displayName, language, gender, style, sampleUrl }]
```

### One utterance

```js
const { audio, contentType, requestId } = await client.speak.synthesize({
  model: "verbex_v1_ja",
  voice: "Aoi",
  text: "こんにちは。",
  format: "pcm", // or "wav"
  settings: { temperature: 0.7 }, // optional, as the model offers
});
```

To start playback before synthesis ends:

```js
const stream = await client.speak.synthesizeStream({ model, voice, text });
console.log(stream.contentType); // headers are known before the first chunk
for await (const chunk of stream) speaker.write(chunk);
```

A body that ends with a transport error throws `SpeakError` (`audio_incomplete`); you never
get short audio silently.

#### Speaking safely more than once

Every `synthesize` and `synthesizeStream` carries an `Idempotency-Key` header, the same one for
each of its automatic retries. An utterance is billed once: if the audio breaks off and you send
the same request again with the same key, it is spoken again without charge (up to 3 times within
24 hours), and `replayed` is `true` on the result (or the stream). To use this for your own
retries, pass your key:

```js
const idempotencyKey = crypto.randomUUID(); // one per utterance, kept for its retries
const { audio, replayed } = await client.speak.synthesize({ model, voice, text, idempotencyKey });
```

Past the 3 free replays the API refuses the key: `BadRequestError` with code
`idempotency_replays_exhausted` (422). Reusing a key for different text throws `BadRequestError`
with code `idempotency_key_reused` (422).

### Many utterances on one socket

```js
const session = await client.speak.stream();
for (const text of ["一つ目。", "二つ目。"]) {
  const utterance = session.say({ model, voice, text }); // requestId is generated if omitted
  for await (const pcm of utterance) speaker.write(pcm);
  const { requestId, audioSeconds } = await utterance.done;
}
await session.close();
```

Utterances go one at a time: a `say` while another is in progress throws `ValidationError`. A
failed utterance throws `SpeakError` and the session stays open; a session the server closes
throws `StreamError`.

## Errors

Every error is a `VerbexError`.

| Type | When |
|---|---|
| `ConfigurationError` | Missing or invalid key or base URL |
| `ValidationError` | Refused locally, nothing sent (file over 100 MiB, stream without `size`, a bad `idempotencyKey` on a submit or a speak, `send` after `finish`, overlapping `say`). `code` is `validation_failed`, or `audio_too_large` for a file over 100 MiB |
| `ApiError` | An HTTP error: `status`, `code`, `message`, `requestId`, `fields`, `retryAfter` |
| `BadRequestError` | 400, 405, 406, 409, 413, 415, 422 |
| `AuthenticationError` | 401 |
| `PaymentRequiredError` | 402 |
| `PermissionDeniedError` | 403 |
| `NotFoundError` | 404 |
| `RateLimitError` | 429 |
| `ServerError` | 5xx |
| `APIConnectionError` | No response: DNS, connect, TLS, reset |
| `APITimeoutError` | No response within the timeout |
| `UploadError` | The storage PUT failed (`status` from storage; 412 means the URL was already used) |
| `StreamError` | A WebSocket session ended by an error: `code`, `closeCode`, `retryable` |
| `SpeakError` | One utterance failed: `code`, `requestId`, `fields` |
| `WaitTimeoutError` | `wait` ran past its timeout; `job` is the last job seen |
| `WebhookVerificationError` | `parseWebhook` with a bad signature |

Branch on `code`, not on `message`. A non-JSON error page still gives the right type, with
`code` `http_<status>`. Quote `requestId` to support.

```js
try {
  await client.listen.submit({ source: "a.wav", model, language });
} catch (err) {
  if (err instanceof PaymentRequiredError) console.log("top up:", err.code);
  else if (err instanceof RateLimitError) console.log("retry after", err.retryAfter, "s");
  else throw err;
}
```

## Retries

Retries are automatic, up to `maxRetries` (default 2), with exponential backoff and full
jitter from 0.5 s, capped at 8 s. `Retry-After` is honoured when present, up to 60 s; a longer
wait is not taken and the error is thrown.

| Call | Retried on |
|---|---|
| models, voices, job reads, upload start | connection errors, timeouts, 429 `rate_limited`/`workspace_rate_limited`, 502, 503, 504 |
| `speak.synthesize` | the same, and 409 `request_in_progress`, only before any audio has arrived. Every attempt carries the same `Idempotency-Key`. 422 is not retried |
| submit (`POST /v1/listen`) | the same as reads, and 409 `request_in_progress` (the first request with this key is still running). Safe because every attempt carries the same `Idempotency-Key`: a retry after a lost answer gets the first receipt, never a second job. 422 `idempotency_key_reused` is not retried |
| submit after an upload | also 409 `upload_incomplete`, 1 s apart |
| storage PUT, WebSocket handshakes | never |

`429 concurrency_limit` is not retried: waiting for your own work to finish is your call.

## Examples

Build first (`npm run build`), then:

```sh
export VERBEX_API_KEY=vbx_... VERBEX_BASE_URL=https://...
node examples/transcribe-file.mjs meeting.wav
node examples/live-session.mjs audio.raw
node examples/speak-to-wav.mjs "こんにちは。" hello.wav
```

## Modules

The root import `@verbex-ai/verbex-speech-sdk` exports every public name. Each product can also be imported
on its own; the classes are the same objects either way, so `instanceof` works across them.

```js
import { VerbexSpeech, StreamError } from "@verbex-ai/verbex-speech-sdk";
import { Listen, verifyWebhook, WaitTimeoutError } from "@verbex-ai/verbex-speech-sdk/listen";
import { Speak, SpeakError } from "@verbex-ai/verbex-speech-sdk/speak";
```

Shared errors (`VerbexError`, `ApiError` and its subclasses, `StreamError`, ...) come from the
root. The source is laid out by responsibility:

| Path | Holds |
|---|---|
| `src/index.ts` | The root export: the client and every public name |
| `src/client.ts` | `VerbexSpeech`, the entry point with `.listen` and `.speak` |
| `src/errors.ts` | The shared errors |
| `src/version.ts` | `VERSION` |
| `src/listen/index.ts` | `Listen` and the speech-to-text names (`@verbex-ai/verbex-speech-sdk/listen`) |
| `src/listen/models.ts` | Model types, `listen.models()` |
| `src/listen/jobs.ts` | Receipt, job and segment types, `WaitTimeoutError`; submit, get, wait, transcribe |
| `src/listen/source.ts`, `upload.ts` | Audio sources and size limits; the upload flow and `UploadError` |
| `src/listen/stream.ts` | `ListenSession` and its events, `transcribeStream` |
| `src/listen/webhook.ts` | `verifyWebhook`, `parseWebhook`, `WebhookVerificationError` |
| `src/speak/index.ts` | `Speak`, `SpeakError` and the text-to-speech names (`@verbex-ai/verbex-speech-sdk/speak`) |
| `src/speak/voices.ts` | `Voice`, `speak.voices()` |
| `src/speak/synthesis.ts` | `synthesize`, `synthesizeStream` and their result types |
| `src/speak/stream.ts` | `SpeakSession`, `Utterance`, `SpeakResult` |
| `src/transport/` | Internal: HTTP and retry loop, retry rules, WebSocket connect, error mapping, JSON readers, async queue |

## Development

```sh
npm install
npm run typecheck
npm test          # unit and conformance tests against local servers
npm run build     # dist/
```

The tests load the shared contract fixtures from `../conformance/`. A live smoke test runs only
when `VERBEX_API_KEY` and `VERBEX_BASE_URL` are both set; it is never run in CI against
production.
