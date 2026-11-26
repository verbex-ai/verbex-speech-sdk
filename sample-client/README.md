# Sample clients

One small program per supported language. Each installs the **published** SDK from its package
registry (not this repository's source), then does the same three things:

1. lists the speech-to-text models and text-to-speech voices your workspace can use;
2. transcribes a WAV or MP3 file (up to 100 MiB);
3. speaks one sentence into `hello.wav`.

| Folder | Package | Registry |
|---|---|---|
| [`python/`](python) | `verbex-speech-sdk` 0.1.1 | PyPI |
| [`node/`](node) | `@verbex.ai/verbex-speech-sdk` 0.1.2 | npm |
| [`java/`](java) | `ai.verbex:verbex-speech-sdk` 0.1.2 | Maven Central |
| [`curl/`](curl) | none: plain HTTP | |

## Before you run

```sh
export VERBEX_API_KEY=vbx_...      # from the customer console; keep it secret
# export VERBEX_BASE_URL=https://speech-api-stage.verbex.ai   # only for stage; default is production
```

Without `VERBEX_BASE_URL` every sample calls the production API, `https://jp-speech-api.verbex.ai`.
Transcription and speech are billed to the key's workspace.

The samples use the model `verbex_jp_stt_x1` and language `ja`; change them to one your workspace
lists. The voice is the first one for that language.

## Run

```sh
# Python 3.9+
cd python && python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python sample.py call.wav

# Node.js 18+
cd node && npm install
node sample.mjs call.wav

# Java 17+ and Maven
cd java && mvn -q compile exec:java -Dexec.args="call.wav"

# curl (and jq)
cd curl && ./sample.sh call.wav
```
