---
name: verbex-speech-sdk
description: Write code that uses the Verbex Speech API through the official Verbex Speech SDKs for Python (verbex-speech-sdk on PyPI), Node.js (@verbex.ai/verbex-speech-sdk on npm) and Java (ai.verbex:verbex-speech-sdk on Maven Central). Use when the user wants to transcribe audio files or audio at a URL, transcribe live audio, synthesise speech (text-to-speech), list Verbex models or voices, verify a Verbex webhook, or handle Verbex API errors, or mentions Verbex, verbex_speech, VerbexSpeech or a vbx_ API key.
---

# Verbex Speech SDK

The Verbex Speech SDKs are the supported way to call the Verbex Speech API with an API key. Use
them instead of writing HTTP or WebSocket calls by hand: they do the upload flow, retries,
idempotency keys and webhook signatures for you.

## Pick the package

| Language | Install | Version | Reference |
|---|---|---|---|
| Python 3.9+ | `pip install verbex-speech-sdk` (import `verbex_speech`) | 0.1.0 | [python.md](python.md) |
| Node.js 18+ (server side, ESM) | `npm install @verbex.ai/verbex-speech-sdk` | 0.1.0 | [node.md](node.md) |
| Java 17+ | Maven `ai.verbex:verbex-speech-sdk` | 0.1.1 | [java.md](java.md) |

Use the language of the project you are working in. Read only that language's reference file.
If a newer version is published, prefer it; the calls below stay the same within 0.x unless its
README says otherwise.

## Rules that apply in every language

1. **Server side only.** The API key (it starts with `vbx_`) must never reach a browser or a
   mobile app. For voice agents in the browser, Verbex has a different package; do not use this
   SDK there.
2. **Configuration from the environment.** The client reads `VERBEX_API_KEY` and
   `VERBEX_BASE_URL`. Without `VERBEX_BASE_URL` it calls the production API,
   `https://jp-speech-api.verbex.ai`; set it only for another address, such as stage. Never hard-code the key in source.
3. **One call for any file size.** `transcribe` sends a file of up to 1 MiB in the request and a
   larger one (up to 100 MiB) through the upload flow. Over 100 MiB is refused before anything is
   sent. Audio already in storage: pass an https `url` instead of a file. Never both.
4. **A failed job is returned, not thrown.** After `transcribe` or `wait`, check the job's status
   (`completed`, `failed`, `cancelled`) and its error.
5. **Do not add your own retry loop around SDK calls.** The SDK retries connection errors,
   timeouts, 429 rate limits and 502/503/504 (default 2 retries, `Retry-After` honoured up to
   60 s). Submits and speech carry an `Idempotency-Key`, so a retry never makes a second job or
   a second charge. Not retried: 422, `429 concurrency_limit` (wait for your own running work),
   `502 synthesis_failed` (the engine refused this text), the storage upload, WebSocket handshakes.
6. **Work that may be submitted again after a restart** gets the caller's own `idempotency_key`
   (1 to 255 visible ASCII characters), saved with the work. The API keeps it 24 hours.
7. **Branch on the error's `code`, not its message.** Every error extends one base type
   (`VerbexError`; `VerbexException` in Java). HTTP errors extend `APIError` (Python), `ApiError`
   (Node) or `ApiException` (Java), which carry the status, `code` and request id. `402` (`PaymentRequiredError`) means no plan, not
   enough credit or an overdue invoice: tell the user to check billing in the Verbex console, do
   not retry. Log `request_id` with errors; support needs it.
8. **Webhooks:** verify with the SDK's webhook function and the API key that submitted the job,
   on the raw body bytes exactly as received, before parsing JSON.
9. **Live transcription audio** is raw PCM: 16 kHz, mono, signed 16-bit little-endian, no header,
   sent at the pace it is produced in 0.2 to 0.5 s pieces. Convert other formats first.
10. **Text-to-speech output** is raw PCM (16 kHz, mono, 16-bit) by default; ask for `wav` to get a
    playable file. Pick `model` and `voice` from `voices()`, matching the text's language.

## Not in the SDK

Usage, billing, API keys and members are console features: an API key cannot reach them. Do not
invent SDK calls for them.

## Where to look next

- The package README on PyPI, npm or Maven Central, and the repository
  https://github.com/verbex-ai/verbex-speech-sdk (its `SPEC.md` is the contract all three SDKs
  follow).
