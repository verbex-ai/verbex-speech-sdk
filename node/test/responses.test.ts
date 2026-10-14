import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { fixture, json, startHttp, testClient, type TestServer } from "./helpers.js";

const r = fixture("responses.json");

let server: TestServer;
let next: { status: number; body: unknown };

beforeAll(async () => {
  server = await startHttp((_req, res) => json(res, next.status, next.body));
});
afterAll(() => server.close());

const client = () => testClient(server.url);

describe("response parsing (conformance/responses.json)", () => {
  it("models", async () => {
    next = { status: 200, body: r.models };
    const models = await client().listen.models();
    expect(models).toEqual([
      {
        model: "verbex_jp_stt_x1",
        displayName: "Verbex Japanese STT X1",
        language: "ja",
        modes: [
          { mode: "async", features: [{ paramName: "hotwords", displayName: "Hotwords", type: "list", maxItems: 20, description: null }] },
          { mode: "stream", features: [{ paramName: "hotwords", displayName: "Hotwords", type: "list", maxItems: 20, description: "Hiragana only." }] },
        ],
      },
    ]);
    expect(server.requests.at(-1)!.path).toBe("/v1/listen/models");
  });

  it("voices", async () => {
    next = { status: 200, body: r.voices };
    const voices = await client().speak.voices();
    expect(voices).toEqual([
      {
        model: "verbex_v1_bn",
        modelDisplayName: "Verbex TTS v1 · Bengali",
        code: "maria_2",
        displayName: "Maria",
        language: "bn",
        gender: "female",
        style: "warm",
        sampleUrl: "https://cdn.example/maria.wav",
      },
      {
        model: "verbex_v1_ja",
        modelDisplayName: "Verbex TTS v1 · Japanese",
        code: "Aoi",
        displayName: null,
        language: "ja",
        gender: null,
        style: null,
        sampleUrl: null,
      },
    ]);
  });

  it("receipt", async () => {
    next = { status: 202, body: r.receipt };
    const receipt = await client().listen.submit({ source: Buffer.from("RIFF"), model: "m", language: "ja" });
    expect(receipt).toEqual({ jobId: "9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", status: "queued", queuePosition: 1, replayed: false });
  });

  it("receipt without position", async () => {
    next = { status: 202, body: r.receipt_without_position };
    const receipt = await client().listen.submit({ url: "https://example.com/a.wav", model: "m", language: "ja" });
    expect(receipt.jobId).toBe("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b");
    expect(receipt.queuePosition).toBeUndefined();
  });

  it("job processing", async () => {
    next = { status: 200, body: r.job_processing };
    const job = await client().listen.get(r.job_processing.job_id);
    expect(server.requests.at(-1)!.path).toBe(`/v1/listen/${r.job_processing.job_id}`);
    expect(job.status).toBe("processing");
    expect(job.completed).toBe(false);
    expect(job.progress).toBe(0.4);
    expect(job.segmentCount).toBe(2);
    expect(job.failedSegmentCount).toBe(0);
    expect(job.audioDurationSec).toBeUndefined();
    expect(job.error).toBeUndefined();
    expect(job.segments).toEqual([{ index: 0, startSec: 0.1, endSec: 1.2, text: "おでんわ" }]);
  });

  it("job completed (extra field ignored)", async () => {
    next = { status: 200, body: r.job_completed };
    const job = await client().listen.get(r.job_completed.job_id);
    expect(job).toEqual({
      jobId: "9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b",
      status: "completed",
      model: "verbex_jp_stt_x1",
      language: "ja",
      audioDurationSec: 19.2,
      progress: 1.0,
      segmentCount: 2,
      failedSegmentCount: 0,
      text: "おでんわありがとうございます。おりかえしごれんらくいたします。",
      segments: [
        { index: 0, startSec: 0.1, endSec: 17.48, text: "おでんわありがとうございます。" },
        { index: 1, startSec: 17.48, endSec: 19.68, text: "おりかえしごれんらくいたします。" },
      ],
      metadata: { call_id: "c-42" },
      completed: true,
    });
    expect((job as any).extraField).toBeUndefined();
    expect((job as any).extra_field).toBeUndefined();
  });

  it("job failed", async () => {
    next = { status: 200, body: r.job_failed };
    const job = await client().listen.get(r.job_failed.job_id);
    expect(job.status).toBe("failed");
    expect(job.completed).toBe(false);
    expect(job.language).toBeUndefined();
    expect(job.text).toBeUndefined();
    expect(job.segments).toEqual([]);
    expect(job.error).toEqual({ code: "invalid_audio", message: "The audio could not be decoded." });
  });

  it("refuses a job id that is not a plain path segment, sending nothing", async () => {
    const before = server.requests.length;
    await expect(client().listen.get("../uploads")).rejects.toThrow(/job id/i);
    expect(server.requests.length).toBe(before);
  });
});
