// Live transcription of a raw PCM file (16 kHz, mono, s16le, no header), sent at real time.
// Make one with: ffmpeg -i in.wav -f s16le -ac 1 -ar 16000 out.raw
//
//   VERBEX_API_KEY=vbx_... VERBEX_BASE_URL=https://... node examples/live-session.mjs audio.raw [model] [language]
import { readFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";
import { VerbexSpeech, StreamError } from "@verbex-ai/verbex-speech-sdk";

const [path, model = "verbex_jp_stt_x1", language = "ja"] = process.argv.slice(2);
if (!path) {
  console.error("usage: node examples/live-session.mjs <audio.raw> [model] [language]");
  process.exit(2);
}

const BYTES_PER_SECOND = 32_000; // 16 kHz x 2 bytes
const CHUNK = 6_400; // 0.2 s

const pcm = readFileSync(path);
const client = new VerbexSpeech();
const session = await client.listen.stream({ model, language });
console.error(`session ${session.sessionId}`);

// Send at the pace the audio would be produced; a live session gains nothing from bursts.
const sending = (async () => {
  const start = Date.now();
  for (let off = 0; off < pcm.length; off += CHUNK) {
    session.send(pcm.subarray(off, off + CHUNK));
    const due = start + ((off + CHUNK) / BYTES_PER_SECOND) * 1000;
    await sleep(Math.max(0, due - Date.now()));
  }
  session.finish();
})();
// If the session fails, the loop below reports it; the sender's own error adds nothing.
sending.catch(() => undefined);

try {
  for await (const ev of session) {
    if (ev.type === "segment") console.log(`[${ev.startSec.toFixed(2)}-${ev.endSec.toFixed(2)}] ${ev.text}`);
    else if (ev.type === "error") console.error(`error ${ev.code}: ${ev.message}`);
    else if (ev.type === "done") console.error(`done: ${ev.totalSegments} segment(s)`);
  }
  await sending;
} catch (err) {
  if (err instanceof StreamError) {
    console.error(`${err.code} (close ${err.closeCode}): ${err.message}${err.retryable ? " - reconnect and resume" : ""}`);
    process.exit(1);
  }
  throw err;
} finally {
  await session.close();
}
