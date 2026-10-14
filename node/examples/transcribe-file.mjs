// Transcribe a recorded file (WAV or MP3, up to 100 MiB) and print the transcript.
//
//   VERBEX_API_KEY=vbx_... VERBEX_BASE_URL=https://... node examples/transcribe-file.mjs meeting.wav [model] [language]
import { VerbexSpeech, VerbexError } from "@verbex-ai/verbex-speech-sdk";

const [path, model = "verbex_jp_stt_x1", language = "ja"] = process.argv.slice(2);
if (!path) {
  console.error("usage: node examples/transcribe-file.mjs <audio file> [model] [language]");
  process.exit(2);
}

const client = new VerbexSpeech();
try {
  // Up to 1 MiB goes in the request; larger files go by the upload flow automatically.
  const job = await client.listen.transcribe({ source: path, model, language });
  if (job.status !== "completed") {
    console.error(`Job ${job.jobId} ended ${job.status}: ${job.error?.code ?? ""} ${job.error?.message ?? ""}`);
    process.exit(1);
  }
  for (const s of job.segments) console.log(`[${s.startSec.toFixed(2)}-${s.endSec.toFixed(2)}] ${s.text}`);
  if (job.failedSegmentCount > 0) console.error(`Note: ${job.failedSegmentCount} stretch(es) could not be transcribed.`);
} catch (err) {
  if (err instanceof VerbexError) console.error(`${err.name}: ${err.message}`);
  else throw err;
  process.exit(1);
}
