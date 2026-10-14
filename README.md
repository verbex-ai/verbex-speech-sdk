# Verbex Speech SDKs

Client libraries for the Verbex Speech API (speech-to-text and text-to-speech), in three languages
that behave the same way. For server-side use with an API key. (Browser voice agents use Verbex's
separate `@verbex-ai/verbex-js-sdk`.)

| Folder | Package | Runtime |
|---|---|---|
| [`python/`](python/README.md) | PyPI `verbex-speech-sdk` | Python 3.9+, sync and async |
| [`node/`](node/README.md) | npm `@verbex-ai/verbex-speech-sdk` | Node.js 18+ (server side) |
| [`java/`](java/README.md) | Maven `ai.verbex:verbex-speech-sdk` | Java 17+ |

- [`SPEC.md`](SPEC.md) is the contract all three implement: the calls, defaults, error types,
  retry rules and WebSocket behaviour.
- [`conformance/`](conformance/) holds the JSON fixtures every SDK's tests load: example
  responses, error answers and the type each maps to, retry decisions, webhook signature vectors,
  and scripted WebSocket sessions. A change to the contract changes the fixtures first.

The API itself is described in the developer guide, `documents/user-guides/api.html` in the
platform repository.

## Tests

```
cd python && pip install -e '.[dev]' && pytest
cd node && npm install && npx vitest run
cd java && mvn test
```

Each suite also has live smoke tests that run only when `VERBEX_API_KEY` and `VERBEX_BASE_URL` are
set. Never point them at production from CI.
