// Speak a sentence and save it as a WAV file.
//
//   VERBEX_API_KEY=vbx_... VERBEX_BASE_URL=https://... node examples/speak-to-wav.mjs "こんにちは。" out.wav [model] [voice]
import { writeFileSync } from "node:fs";
import { VerbexSpeech } from "@verbex-ai/verbex-speech-sdk";

const [text, out = "speech.wav", modelArg, voiceArg] = process.argv.slice(2);
if (!text) {
  console.error('usage: node examples/speak-to-wav.mjs "<text>" [out.wav] [model] [voice]');
  process.exit(2);
}

const client = new VerbexSpeech();
let model = modelArg;
let voice = voiceArg;
if (!model || !voice) {
  // Take a voice from the current list rather than hard-coding one.
  const voices = await client.speak.voices();
  const v = voices.find((x) => !model || x.model === model) ?? voices[0];
  model = v.model;
  voice = v.code;
}

// Ask for raw PCM and write a WAV header with the real sizes.
const { audio, requestId } = await client.speak.synthesize({ model, voice, text });
writeFileSync(out, Buffer.concat([wavHeader(audio.length), audio]));
console.error(`wrote ${out}: ${(audio.length / 32_000).toFixed(2)} s (${model}/${voice}, request ${requestId})`);

function wavHeader(dataBytes, sampleRate = 16_000, channels = 1, bits = 16) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0);
  h.writeUInt32LE(36 + dataBytes, 4);
  h.write("WAVE", 8);
  h.write("fmt ", 12);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);
  h.writeUInt16LE(channels, 22);
  h.writeUInt32LE(sampleRate, 24);
  h.writeUInt32LE((sampleRate * channels * bits) / 8, 28);
  h.writeUInt16LE((channels * bits) / 8, 32);
  h.writeUInt16LE(bits, 34);
  h.write("data", 36);
  h.writeUInt32LE(dataBytes, 40);
  return h;
}
