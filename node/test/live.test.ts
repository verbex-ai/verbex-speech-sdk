/**
 * Live smoke test (spec §9). Runs only when VERBEX_API_KEY and VERBEX_BASE_URL are both set;
 * otherwise skipped. Never run in CI against production.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { VerbexSpeech } from "../src/index.js";

const live = Boolean(process.env.VERBEX_API_KEY && process.env.VERBEX_BASE_URL);
const samples = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..", "documents", "stt-engine-documents");
const WAV = join(samples, "sample-ja-16k-mono.wav");
const RAW = join(samples, "sample-ja-16k-mono.raw");

describe.skipIf(!live)("live smoke test", () => {
  const client = () => new VerbexSpeech();

  it("lists models and voices", async () => {
    const [models, voices] = await Promise.all([client().listen.models(), client().speak.voices()]);
    expect(models.length).toBeGreaterThan(0);
    expect(voices.length).toBeGreaterThan(0);
  });

  it.skipIf(!existsSync(WAV))("transcribes the 20 s Japanese sample", async () => {
    const job = await client().listen.transcribe({ source: WAV, model: "verbex_jp_stt_x1", language: "ja", waitTimeoutMs: 300_000 });
    expect(job.status).toBe("completed");
    expect(job.text?.length ?? 0).toBeGreaterThan(0);
  }, 360_000);

  it.skipIf(!existsSync(RAW))("runs the sample through a live session at real time", async () => {
    const pcm = readFileSync(RAW);
    const CHUNK = 6_400; // 0.2 s
    async function* paced() {
      for (let off = 0; off < pcm.length; off += CHUNK) {
        yield pcm.subarray(off, off + CHUNK);
        await new Promise((r) => setTimeout(r, 200));
      }
    }
    const segments = await client().listen.transcribeStream(paced(), { model: "verbex_jp_stt_x1", language: "ja" });
    expect(segments.length).toBeGreaterThan(0);
  }, 120_000);

  it("speaks one sentence", async () => {
    const voices = await client().speak.voices();
    const v = voices.find((x) => x.language === "ja") ?? voices[0];
    const out = await client().speak.synthesize({ model: v.model, voice: v.code, text: v.language === "ja" ? "こんにちは。" : "Hello." });
    expect(out.audio.length).toBeGreaterThan(0);
  }, 400_000);
});
