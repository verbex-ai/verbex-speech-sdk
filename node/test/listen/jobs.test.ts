import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ValidationError, WaitTimeoutError, DIRECT_MAX_BYTES } from "../../src/index.js";
import { apiError, delay, fakeClock, fixture, json, startHttp, testClient, type RecordedRequest, type TestServer } from "../helpers.js";

const r = fixture("responses.json");

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

async function formOf(req: RecordedRequest): Promise<FormData> {
  return new Response(new Uint8Array(req.body), { headers: { "content-type": String(req.headers["content-type"]) } }).formData();
}

describe("listen.submit, file in the request", () => {
  it("sends multipart: file, model, language, one hotwords part per term, metadata as JSON, webhook_url, tags in the query", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    const audio = Buffer.from("RIFF....WAVEfmt fake audio");
    await testClient(server.url).listen.submit({
      source: audio,
      model: "verbex_jp_stt_x1",
      language: "ja",
      hotwords: ["かいみょう", "a,b"],
      metadata: { call_id: "c-42" },
      webhookUrl: "https://example.com/stt/done",
      tags: ["campaign-7", "env:prod"],
    });
    const req = server.requests[0];
    expect(req.method).toBe("POST");
    expect(req.path).toBe("/v1/listen");
    expect(req.query.getAll("tag")).toEqual(["campaign-7", "env:prod"]);
    expect(String(req.headers["content-type"])).toMatch(/^multipart\/form-data; boundary=/);
    const form = await formOf(req);
    const file = form.get("file") as File;
    expect(file.name).toBe("audio.wav");
    expect(Buffer.from(await file.arrayBuffer())).toEqual(audio);
    expect(form.get("model")).toBe("verbex_jp_stt_x1");
    expect(form.get("language")).toBe("ja");
    expect(form.getAll("hotwords")).toEqual(["かいみょう", "a,b"]);
    expect(JSON.parse(String(form.get("metadata")))).toEqual({ call_id: "c-42" });
    expect(form.get("webhook_url")).toBe("https://example.com/stt/done");
  });

  it("leaves optional parts out, and takes the file name from a path", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    const dir = mkdtempSync(join(tmpdir(), "vbx-sdk-"));
    const path = join(dir, "meeting.mp3");
    writeFileSync(path, Buffer.from("ID3 fake"));
    await testClient(server.url).listen.submit({ source: path, model: "m", language: "ja" });
    const form = await formOf(server.requests[0]);
    expect((form.get("file") as File).name).toBe("meeting.mp3");
    expect([...form.keys()].sort()).toEqual(["file", "language", "model"]);
  });

  it("uses the filename option for bytes and streams", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    await testClient(server.url).listen.submit({ source: new Uint8Array([1, 2, 3]), filename: "x.mp3", model: "m", language: "ja" });
    expect(((await formOf(server.requests[0])).get("file") as File).name).toBe("x.mp3");
  });

  it("sends exactly 1 MiB in the request", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    await testClient(server.url).listen.submit({ source: Buffer.alloc(DIRECT_MAX_BYTES), model: "m", language: "ja" });
    expect(server.requests.map((q) => q.path)).toEqual(["/v1/listen"]);
  });

  it("gives submit its own 330 s budget, not the client's timeout", async () => {
    server = await startHttp(async (_req, res) => {
      await delay(400);
      json(res, 202, r.receipt);
    });
    const receipt = await testClient(server.url, { timeoutMs: 150 }).listen.submit({ source: Buffer.from("x"), model: "m", language: "ja" });
    expect(receipt.jobId).toBe(r.receipt.job_id);
  });
});

describe("listen.submit, by URL", () => {
  it("sends JSON with hotwords as an array and metadata as an object", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    await testClient(server.url).listen.submit({
      url: "https://storage.example.com/a.wav?sig=1",
      model: "m",
      language: "ja",
      hotwords: ["x"],
      metadata: { a: 1 },
      webhookUrl: "https://example.com/hook",
      tags: ["t1"],
    });
    const req = server.requests[0];
    expect(req.headers["content-type"]).toBe("application/json");
    expect(req.query.getAll("tag")).toEqual(["t1"]);
    expect(JSON.parse(req.body.toString())).toEqual({
      url: "https://storage.example.com/a.wav?sig=1",
      model: "m",
      language: "ja",
      hotwords: ["x"],
      metadata: { a: 1 },
      webhook_url: "https://example.com/hook",
    });
  });

  it("refuses both or neither of source and url, sending nothing", async () => {
    server = await startHttp((_req, res) => json(res, 202, r.receipt));
    const c = testClient(server.url);
    await expect(c.listen.submit({ model: "m", language: "ja" })).rejects.toBeInstanceOf(ValidationError);
    await expect(c.listen.submit({ source: Buffer.from("x"), url: "https://a/b.wav", model: "m", language: "ja" })).rejects.toBeInstanceOf(ValidationError);
    expect(server.requests.length).toBe(0);
  });
});

describe("listen.wait", () => {
  const processing = r.job_processing;
  const completed = r.job_completed;

  it("polls every 2 s by default until a terminal status", async () => {
    let n = 0;
    server = await startHttp((_req, res) => json(res, 200, ++n < 3 ? processing : completed));
    const clock = fakeClock();
    const job = await testClient(server.url, {}, clock).listen.wait(completed.job_id);
    expect(job.completed).toBe(true);
    expect(server.requests.length).toBe(3);
    expect(clock.sleeps).toEqual([2000, 2000]);
  });

  it("never polls faster than every 0.5 s", async () => {
    let n = 0;
    server = await startHttp((_req, res) => json(res, 200, ++n < 2 ? processing : completed));
    const clock = fakeClock();
    await testClient(server.url, {}, clock).listen.wait(completed.job_id, { pollIntervalMs: 100 });
    expect(clock.sleeps).toEqual([500]);
  });

  it("returns failed and cancelled jobs without raising", async () => {
    server = await startHttp((_req, res) => json(res, 200, r.job_failed));
    const job = await testClient(server.url).listen.wait(r.job_failed.job_id);
    expect(job.status).toBe("failed");
    expect(job.error?.code).toBe("invalid_audio");
    await server.close();
    server = await startHttp((_req, res) => json(res, 200, { ...processing, status: "cancelled" }));
    expect((await testClient(server.url).listen.wait(processing.job_id)).status).toBe("cancelled");
  });

  it("raises WaitTimeoutError carrying the last job", async () => {
    server = await startHttp((_req, res) => json(res, 200, processing));
    const clock = fakeClock();
    const err: any = await testClient(server.url, {}, clock).listen.wait(processing.job_id, { timeoutMs: 5000 }).catch((e) => e);
    expect(err).toBeInstanceOf(WaitTimeoutError);
    expect(err.job.status).toBe("processing");
    expect(err.job.jobId).toBe(processing.job_id);
    expect(clock.sleeps).toEqual([2000, 2000, 1000]);
    expect(server.requests.length).toBe(4);
  });

  it("waits Retry-After on a 429 while polling and continues", async () => {
    let n = 0;
    server = await startHttp((_req, res) => (++n === 1 ? apiError(res, 429, "rate_limited", { "Retry-After": "7" }) : json(res, 200, completed)));
    const clock = fakeClock();
    const job = await testClient(server.url, { maxRetries: 0 }, clock).listen.wait(completed.job_id);
    expect(job.completed).toBe(true);
    expect(clock.sleeps).toEqual([7000]);
  });

  it("raises other errors", async () => {
    server = await startHttp((_req, res) => apiError(res, 404, "job_not_found"));
    await expect(testClient(server.url).listen.wait("abc")).rejects.toMatchObject({ name: "NotFoundError", code: "job_not_found" });
  });
});

describe("listen.transcribe", () => {
  it("submits, then waits for the finished job", async () => {
    let polls = 0;
    server = await startHttp((req, res) => {
      if (req.method === "POST") return json(res, 202, r.receipt);
      return json(res, 200, ++polls < 2 ? r.job_processing : r.job_completed);
    });
    const clock = fakeClock();
    const job = await testClient(server.url, {}, clock).listen.transcribe({ source: Buffer.from("x"), model: "m", language: "ja", pollIntervalMs: 1000 });
    expect(job.text).toBe(r.job_completed.text);
    expect(server.requests.map((q) => `${q.method} ${q.path}`)).toEqual([
      "POST /v1/listen",
      `GET /v1/listen/${r.receipt.job_id}`,
      `GET /v1/listen/${r.receipt.job_id}`,
    ]);
    expect(clock.sleeps).toEqual([1000]);
  });
});
