# Verbex Speech SDKs

Client libraries for the Verbex Speech API (speech-to-text and text-to-speech), in three languages
that behave the same way. For server-side use with an API key. (Browser voice agents use Verbex's
separate `@verbex-ai/verbex-js-sdk`.)

| Folder | Package | Latest | Runtime |
|---|---|---|---|
| [`python/`](python/README.md) | PyPI [`verbex-speech-sdk`](https://pypi.org/project/verbex-speech-sdk/) | 0.1.0 | Python 3.9+, sync and async |
| [`node/`](node/README.md) | npm [`@verbex.ai/verbex-speech-sdk`](https://www.npmjs.com/package/@verbex.ai/verbex-speech-sdk) | 0.1.0 | Node.js 18+ (server side) |
| [`java/`](java/README.md) | Maven Central [`ai.verbex:verbex-speech-sdk`](https://central.sonatype.com/artifact/ai.verbex/verbex-speech-sdk) | 0.1.1 | Java 17+ |

## Installation

```sh
pip install verbex-speech-sdk
npm install @verbex.ai/verbex-speech-sdk
```

```xml
<dependency>
  <groupId>ai.verbex</groupId>
  <artifactId>verbex-speech-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

Each package's README has the other package managers, a quick start and the full API.

The root `package.json` is the Node.js package as npm installs it from GitHub
(`npm install github:verbex-ai/verbex-speech-sdk`): it builds `node/` on `prepare`. It must
describe the same package as `node/package.json`, which a test in `node/` checks. See
[node/README.md](node/README.md#from-github) for the two settings npm 12 needs.

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

## Releases

Each SDK is released on its own, at its own version, by the `Release` workflow in the private
repository `verbex-ai/verbex-speech-sdk-release`:

1. Set the version in the package's version file and merge it to `master`: `python/pyproject.toml`,
   `package.json` and `node/package.json` (both), or `java/pom.xml`.
2. Push the tag `pypi/v<version>`, `npm/v<version>` or `maven/v<version>` on that commit.
3. Run the workflow with the package and version, as a dry run first, then for real.

A version published to PyPI, npm or Maven Central cannot be replaced: a fix after a release is a
new version.
