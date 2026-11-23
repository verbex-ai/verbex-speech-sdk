# Node.js: @verbex.ai/verbex-speech-sdk

```sh
npm install @verbex.ai/verbex-speech-sdk     # or: pnpm add / yarn add
```

ESM with TypeScript declarations. Node.js 18 or later. Server side only.

## Client

```js
import { VerbexSpeech } from "@verbex.ai/verbex-speech-sdk";

const client = new VerbexSpeech(); // reads VERBEX_API_KEY and VERBEX_BASE_URL
// or new VerbexSpeech({ apiKey, baseUrl, timeoutMs: 30_000, maxRetries: 2 })
```

Sub-path imports: `@verbex.ai/verbex-speech-sdk/listen`, `@verbex.ai/verbex-speech-sdk/speak`.

## Models and voices

```js
const models = await client.listen.models(); // [{ model, displayName, language, modes }]
const voices = await client.speak.voices();  // [{ model, code, displayName, language, gender, style, sampleUrl }]
```

## Transcribe a file or a URL

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
else console.log(job.status, job.error); // failed or cancelled: not thrown

const fromUrl = await client.listen.transcribe({ url: "https://storage.example.com/a.wav", model, language });
```

Without waiting:

```js
const receipt = await client.listen.submit({ source: "meeting.wav", model, language,
  webhookUrl: "https://example.com/stt/done",
  idempotencyKey: "order-42-meeting" }); // optional; saved with the work
const done = await client.listen.wait(receipt.jobId, { pollIntervalMs: 2000, timeoutMs: 600_000 });
// or client.listen.get(receipt.jobId) once
```

`receipt.replayed` is `true` when an earlier request with the same key already made the job.
Finished jobs are kept 10 minutes.

## Webhook

```js
import { parseWebhook, verifyWebhook, WebhookVerificationError } from "@verbex.ai/verbex-speech-sdk";

// rawBody: the exact bytes received, before any JSON parsing (e.g. express.raw()).
const job = parseWebhook({ apiKey, rawBody, headers: req.headers }); // throws WebhookVerificationError
const ok = verifyWebhook({ apiKey, rawBody, headers: req.headers }); // true/false
```

## Live transcription

```js
const session = await client.listen.stream({ model: "verbex_jp_stt_x1", language: "ja" });
microphone.on("data", (pcm) => session.send(pcm)); // 16 kHz mono s16le, at the pace it is produced
microphone.on("end", () => session.finish());

for await (const ev of session) {
  if (ev.type === "segment") console.log(ev.startSec, ev.endSec, ev.text);
  if (ev.type === "error") console.warn(ev.code, ev.message); // the session goes on
  if (ev.type === "done") break;
}
await session.close();
```

`session.configure(hotwords)` changes hotwords mid-session. A session the server ends throws
`StreamError` (`code`, `closeCode`, `retryable`). For audio already in chunks:
`client.listen.transcribeStream(chunks, { model, language })` (it does not pace for you).

## Text-to-speech

```js
import { writeFile } from "node:fs/promises";

const voice = voices.find((v) => v.language === "ja");
const { audio, requestId } = await client.speak.synthesize({
  model: voice.model,
  voice: voice.code,
  text: "こんにちは。",
  format: "wav", // default "pcm"
});
await writeFile("hello.wav", audio); // audio is a Buffer
```

Also `requestId`, `settings: {...}`, `tags`, `idempotencyKey` (one per utterance: a repeat with
the same key is spoken again without charge, up to 3 times in 24 hours).

Play while it is synthesised:

```js
const stream = await client.speak.synthesizeStream({ model, voice, text });
for await (const chunk of stream) speaker.write(chunk);
```

Many utterances on one socket, one at a time:

```js
const session = await client.speak.stream();
for (const text of lines) {
  const utterance = session.say({ model, voice, text });
  for await (const pcm of utterance) speaker.write(pcm);
  await utterance.done;
}
await session.close();
```

A failed utterance throws `SpeakError`; the session stays open.

## Errors

```js
import { ApiError, PaymentRequiredError, RateLimitError } from "@verbex.ai/verbex-speech-sdk";

try {
  await client.listen.submit({ source: "a.wav", model, language });
} catch (err) {
  if (err instanceof PaymentRequiredError) console.log("billing:", err.code); // 402: do not retry
  else if (err instanceof RateLimitError) console.log("retry after", err.retryAfter, "s");
  else if (err instanceof ApiError) console.error(err.status, err.code, err.requestId);
  throw err;
}
```

Types: `ConfigurationError`, `ValidationError` (refused locally), `ApiError` (`status`, `code`,
`message`, `requestId`, `fields`, `retryAfter`) with `BadRequestError`, `AuthenticationError`,
`PaymentRequiredError`, `PermissionDeniedError`, `NotFoundError`, `RateLimitError`, `ServerError`;
`APIConnectionError`, `APITimeoutError`, `UploadError`, `StreamError`, `SpeakError`,
`WaitTimeoutError` (`job` is the last job seen), `WebhookVerificationError`.
