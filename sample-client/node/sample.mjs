// Verbex Speech sample: node sample.mjs call.wav
// Reads VERBEX_API_KEY (and VERBEX_BASE_URL, if set) from the environment.
import { writeFile } from "node:fs/promises";
import { VerbexSpeech } from "@verbex.ai/verbex-speech-sdk";

const MODEL = "verbex_jp_stt_x1";
const LANGUAGE = "ja";

const file = process.argv[2];
if (!file) {
  console.error("usage: node sample.mjs <audio.wav|audio.mp3>");
  process.exit(1);
}

const client = new VerbexSpeech();
console.log("API:", client.baseUrl);

for (const m of await client.listen.models()) console.log("stt", m.model, m.language);
const voices = await client.speak.voices();
for (const v of voices) console.log("tts", v.model, v.code, v.language);

const job = await client.listen.transcribe({ source: file, model: MODEL, language: LANGUAGE });
console.log("job", job.status);
if (job.text) console.log(job.text);

const voice = voices.find((v) => v.language === LANGUAGE);
if (!voice) {
  console.error(`no voice for ${LANGUAGE}`);
  process.exit(1);
}
const { audio } = await client.speak.synthesize({ model: voice.model, voice: voice.code, text: "こんにちは", format: "wav" });
await writeFile("hello.wav", audio);
console.log("wrote hello.wav");
