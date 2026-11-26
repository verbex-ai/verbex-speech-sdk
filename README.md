# Verbex Speech SDKs

Client libraries for the Verbex Speech API (speech-to-text and text-to-speech), in three languages
that behave the same way. For server-side use with an API key. (Browser voice agents use Verbex's
separate `@verbex-ai/verbex-js-sdk`.)

| Folder | Package | Latest | Runtime |
|---|---|---|---|
| [`python/`](python/README.md) | PyPI [`verbex-speech-sdk`](https://pypi.org/project/verbex-speech-sdk/) | 0.1.1 | Python 3.9+, sync and async |
| [`node/`](node/README.md) | npm [`@verbex.ai/verbex-speech-sdk`](https://www.npmjs.com/package/@verbex.ai/verbex-speech-sdk) | 0.1.2 | Node.js 18+ (server side) |
| [`java/`](java/README.md) | Maven Central [`ai.verbex:verbex-speech-sdk`](https://central.sonatype.com/artifact/ai.verbex/verbex-speech-sdk) | 0.1.2 | Java 17+ |

## Usage

Each example installs the package from its registry, transcribes a file, transcribes audio at a
URL, and speaks one sentence. Set the API key (it starts with `vbx_`) first. The SDKs call
the production API, `https://jp-speech-api.verbex.ai`, unless `VERBEX_BASE_URL` names another:

```sh
export VERBEX_API_KEY=vbx_...
# export VERBEX_BASE_URL=https://speech-api-stage.verbex.ai   # stage only
```

### Python (PyPI)

```sh
pip install verbex-speech-sdk
```

```python
from verbex_speech import VerbexSpeech

with VerbexSpeech() as client:
    # A file: up to 1 MiB in the request, up to 100 MiB through the upload flow.
    job = client.listen.transcribe("call.wav", model="verbex_jp_stt_x1", language="ja")
    print(job.status, job.text)

    # Audio the API fetches from an https URL.
    job = client.listen.transcribe(url="https://storage.example.com/call.wav",
                                   model="verbex_jp_stt_x1", language="ja")
    print(job.status, job.text)

    # Text-to-speech.
    voice = next(v for v in client.speak.voices() if v.language == "ja")
    audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="こんにちは", format="wav")
    with open("hello.wav", "wb") as f:
        f.write(audio.data)
```

### Node.js (npm)

```sh
npm install @verbex.ai/verbex-speech-sdk
```

```js
import { writeFile } from "node:fs/promises";
import { VerbexSpeech } from "@verbex.ai/verbex-speech-sdk";

const client = new VerbexSpeech(); // reads VERBEX_API_KEY, and VERBEX_BASE_URL if set

// A file: up to 1 MiB in the request, up to 100 MiB through the upload flow.
let job = await client.listen.transcribe({ source: "call.wav", model: "verbex_jp_stt_x1", language: "ja" });
console.log(job.status, job.text);

// Audio the API fetches from an https URL.
job = await client.listen.transcribe({
  url: "https://storage.example.com/call.wav",
  model: "verbex_jp_stt_x1",
  language: "ja",
});
console.log(job.status, job.text);

// Text-to-speech.
const voice = (await client.speak.voices()).find((v) => v.language === "ja");
const { audio } = await client.speak.synthesize({ model: voice.model, voice: voice.code, text: "こんにちは", format: "wav" });
await writeFile("hello.wav", audio);
```

### Java (Maven Central)

Maven:

```xml
<dependency>
  <groupId>ai.verbex</groupId>
  <artifactId>verbex-speech-sdk</artifactId>
  <version>0.1.2</version>
</dependency>
```

Gradle: `implementation("ai.verbex:verbex-speech-sdk:0.1.2")`

```java
import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.job.TranscribeRequest;
import ai.verbex.speech.sdk.speak.AudioFormat;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.voice.Voice;
import java.nio.file.Files;
import java.nio.file.Path;

public class Example {
    public static void main(String[] args) throws Exception {
        VerbexSpeech client = VerbexSpeech.builder().build(); // reads VERBEX_API_KEY, and VERBEX_BASE_URL if set

        // A file: up to 1 MiB in the request, up to 100 MiB through the upload flow.
        Job job = client.listen().transcribe(Path.of("call.wav"), "verbex_jp_stt_x1", "ja");
        System.out.println(job.status() + " " + job.text());

        // Audio the API fetches from an https URL.
        job = client.listen().transcribe(TranscribeRequest.builder()
                .url("https://storage.example.com/call.wav")
                .model("verbex_jp_stt_x1").language("ja")
                .build());
        System.out.println(job.status() + " " + job.text());

        // Text-to-speech.
        Voice voice = client.speak().voices().stream()
                .filter(v -> v.language().equals("ja")).findFirst().orElseThrow();
        SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder()
                .model(voice.model()).voice(voice.code()).text("こんにちは")
                .format(AudioFormat.WAV)
                .build());
        Files.write(Path.of("hello.wav"), audio.audio());
    }
}
```

A failed or cancelled job is returned, not raised: check its status and error. Each package's
README has the other package managers, live transcription, webhooks, errors and retries.

### HTTP (curl)

The same calls without an SDK. Every request carries the key as `Authorization: Token <key>`.

```sh
API=https://jp-speech-api.verbex.ai

# Models and voices your workspace can use.
curl -sS "$API/v1/listen/models" -H "Authorization: Token $VERBEX_API_KEY"
curl -sS "$API/v1/speak/voices"  -H "Authorization: Token $VERBEX_API_KEY"

# A file of up to 1 MiB (WAV or MP3). Answers 202 with a job_id.
curl -sS -X POST "$API/v1/listen" -H "Authorization: Token $VERBEX_API_KEY" \
  -F "file=@call.wav" -F "model=verbex_jp_stt_x1" -F "language=ja"

# Audio the API fetches from an https URL.
curl -sS -X POST "$API/v1/listen" -H "Authorization: Token $VERBEX_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://storage.example.com/call.wav", "model": "verbex_jp_stt_x1", "language": "ja"}'

# Poll the job until its status is final; the transcript is in "text".
curl -sS "$API/v1/listen/<job_id>" -H "Authorization: Token $VERBEX_API_KEY"

# Text-to-speech: the response body is the audio. Take model and voice from /v1/speak/voices.
curl -sS -X POST "$API/v1/speak" -H "Authorization: Token $VERBEX_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "<model>", "voice": "<voice>", "text": "こんにちは", "format": "wav"}' \
  -o hello.wav
```

A file over 1 MiB, live transcription and streaming speech need more than one call; the SDKs do
that for you.

Runnable versions of these examples, each installing its package from the registry, are in
[`sample-client/`](sample-client/README.md).

## AI coding agents

[`skills/verbex-speech-sdk/`](skills/verbex-speech-sdk/SKILL.md) is an agent skill. It teaches
Claude Code and Codex to write code with these SDKs: which package to install, the calls, the rules
on keys, errors and retries, and examples for each language. Both tools read the same skill.

### Install

Claude Code, as a plugin from this repository:

```
/plugin marketplace add verbex-ai/verbex-speech-sdk
/plugin install verbex-speech-sdk@verbex-speech-sdk
```

Codex, as a plugin from this repository:

```sh
codex plugin marketplace add verbex-ai/verbex-speech-sdk
codex plugin add verbex-speech-sdk@verbex-speech-sdk
```

Either tool without plugins: copy the skill folder into the tool's skills folder.

```sh
git clone --depth 1 https://github.com/verbex-ai/verbex-speech-sdk /tmp/verbex-speech-sdk
cp -r /tmp/verbex-speech-sdk/skills/verbex-speech-sdk ~/.agents/skills/    # Codex
cp -r /tmp/verbex-speech-sdk/skills/verbex-speech-sdk ~/.claude/skills/    # Claude Code
```

For one project only, use the project's `.agents/skills/` (Codex) or `.claude/skills/` (Claude
Code) instead. Restart the tool if the skill does not show up.

To update: `/plugin marketplace update verbex-speech-sdk` (Claude Code),
`codex plugin marketplace upgrade verbex-speech-sdk` (Codex), or copy the folder again.

### Use

Ask for what you want in your project. The skill is picked up when the request is about Verbex,
speech-to-text or text-to-speech with these SDKs. For example:

- "Add a function that transcribes an uploaded WAV file with Verbex and returns the text."
- "Transcribe the audio at this signed URL with Verbex in our Node.js service, with a webhook."
- "Add a Spring endpoint that turns text into a WAV file with the Verbex Java SDK."
- "Verify Verbex webhooks in our FastAPI app."
- "Stream microphone audio to Verbex live transcription and print the segments."
- "Handle Verbex errors: tell the user to top up on 402, log the request id otherwise."

To call the skill by name:

| Tool | How |
|---|---|
| Claude Code | `/verbex-speech-sdk:verbex-speech-sdk` as a plugin, `/verbex-speech-sdk` when copied |
| Codex | `$verbex-speech-sdk` in the prompt, or pick it from `/skills` |

What the agent does with it: installs the right package for your project's language, reads the
key from `VERBEX_API_KEY` and `VERBEX_BASE_URL` (never in code), uses one `transcribe` call for any
file size or a URL, checks the job's status instead of expecting an exception, leaves retries to
the SDK, and branches on error codes. It keeps the API key on the server side.

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
