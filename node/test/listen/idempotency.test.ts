import { afterEach, describe, expect, it } from "vitest";
import { Readable } from "node:stream";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { ServerResponse } from "node:http";
import { BadRequestError, ValidationError } from "../../src/index.js";
import { apiError, fakeClock, fixture, json, startHttp, testClient, type RecordedRequest, type TestServer } from "../helpers.js";

const r = fixture("responses.json");
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BIG = 1_048_577;

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

type Step = (req: RecordedRequest, res: ServerResponse) => unknown;

/** Plays the API and the storage service; `submit` answers `POST /v1/listen`. */
async function start(submit: Step = (_q, res) => json(res, 202, r.receipt)): Promise<TestServer> {
  const s = await startHttp((req, res) => {
    if (req.method === "POST" && req.path === "/v1/listen/uploads") {
      return json(res, 201, { ...r.upload_started, upload_url: `${s.url}/storage/u?sig=1`, headers: {} });
    }
    if (req.path.startsWith("/storage/")) return (res.writeHead(200), res.end());
    if (req.method === "POST" && req.path === "/v1/listen") return submit(req, res);
    json(res, 404, {});
  });
  return s;
}

const submits = (s: TestServer) => s.requests.filter((q) => q.method === "POST" && q.path === "/v1/listen");
const keyOf = (q: RecordedRequest) => q.headers["idempotency-key"];

async function fileOf(req: RecordedRequest): Promise<Buffer> {
  const form = await new Response(new Uint8Array(req.body), { headers: { "content-type": String(req.headers["content-type"]) } }).formData();
  return Buffer.from(await (form.get("file") as File).arrayBuffer());
}

/** Fails the first `n` submits with 503, then accepts. */
const failFirst = (n: number, status = 503, code = "engine_unavailable"): Step => {
  let seen = 0;
  return (_q, res) => (seen++ < n ? apiError(res, status, code) : json(res, 202, r.receipt));
};

describe("Idempotency-Key on submit", () => {
  it("is a fresh UUID v4 on a multipart submit", async () => {
    server = await start();
    await testClient(server.url).listen.submit({ source: Buffer.from("RIFF"), model: "m", language: "ja" });
    expect(String(keyOf(submits(server)[0]))).toMatch(UUID_V4);
  });

  it("is sent on a url submit", async () => {
    server = await start();
    await testClient(server.url).listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
    expect(String(keyOf(submits(server)[0]))).toMatch(UUID_V4);
  });

  it("is sent on the upload flow's submit, and not on the upload start", async () => {
    server = await start();
    await testClient(server.url).listen.submit({ source: Buffer.alloc(BIG), model: "m", language: "ja" });
    expect(String(keyOf(submits(server)[0]))).toMatch(UUID_V4);
    const startReq = server.requests.find((q) => q.path === "/v1/listen/uploads")!;
    expect(keyOf(startReq)).toBeUndefined();
  });

  it("differs between two submit calls", async () => {
    server = await start();
    const c = testClient(server.url);
    await c.listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
    await c.listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
    const [a, b] = submits(server).map(keyOf);
    expect(a).not.toBe(b);
  });

  it("is the same on every retry of one call (503 then 202), multipart", async () => {
    server = await start(failFirst(1));
    const receipt = await testClient(server.url, {}, fakeClock()).listen.submit({ source: Buffer.from("RIFF"), model: "m", language: "ja" });
    expect(receipt.jobId).toBe(r.receipt.job_id);
    const [a, b] = submits(server);
    expect(keyOf(a)).toMatch(UUID_V4);
    expect(keyOf(b)).toBe(keyOf(a));
  });

  it("is the same on every retry of a url submit (connection lost, 502, then 202)", async () => {
    let n = 0;
    server = await start((_q, res) => {
      n++;
      if (n === 1) return res.socket?.destroy();
      if (n === 2) return apiError(res, 502, "upstream_unavailable");
      json(res, 202, r.receipt);
    });
    await testClient(server.url, { maxRetries: 3 }, fakeClock()).listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
    const keys = submits(server).map(keyOf);
    expect(keys.length).toBeGreaterThanOrEqual(3);
    expect(new Set(keys).size).toBe(1);
  });

  it("is retried on 409 request_in_progress with the same key", async () => {
    server = await start(failFirst(1, 409, "request_in_progress"));
    await testClient(server.url, {}, fakeClock()).listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
    const [a, b] = submits(server);
    expect(keyOf(b)).toBe(keyOf(a));
  });

  it("keeps the key across the upload flow's 409 upload_incomplete loop and its transport retries", async () => {
    let n = 0;
    server = await start((_q, res) => {
      n++;
      if (n === 1) return apiError(res, 409, "upload_incomplete");
      if (n === 2) return apiError(res, 503, "engine_unavailable");
      json(res, 202, r.receipt);
    });
    await testClient(server.url, {}, fakeClock()).listen.submit({ source: Buffer.alloc(BIG), model: "m", language: "ja" });
    const keys = submits(server).map(keyOf);
    expect(keys.length).toBe(3);
    expect(new Set(keys).size).toBe(1);
    expect(server.requests.filter((q) => q.method === "PUT").length).toBe(1);
  });

  it("transcribe makes one key for its submit and keeps it across retries", async () => {
    let n = 0;
    server = await startHttp((req, res) => {
      if (req.method === "POST") return ++n === 1 ? apiError(res, 504, "upstream_timeout") : json(res, 202, r.receipt);
      json(res, 200, r.job_completed);
    });
    await testClient(server.url, {}, fakeClock()).listen.transcribe({ source: Buffer.from("x"), model: "m", language: "ja" });
    const posts = server.requests.filter((q) => q.method === "POST");
    expect(posts.length).toBe(2);
    expect(String(keyOf(posts[0]))).toMatch(UUID_V4);
    expect(keyOf(posts[1])).toBe(keyOf(posts[0]));
    expect(keyOf(server.requests.find((q) => q.method === "GET")!)).toBeUndefined();
  });

  it("uses the caller's key verbatim, on submit and transcribe", async () => {
    server = await startHttp((req, res) => (req.method === "POST" ? json(res, 202, r.receipt) : json(res, 200, r.job_completed)));
    const c = testClient(server.url);
    await c.listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja", idempotencyKey: "order-42:file~1" });
    await c.listen.transcribe({ source: Buffer.from("x"), model: "m", language: "ja", idempotencyKey: "A".repeat(255) });
    const posts = server.requests.filter((q) => q.method === "POST");
    expect(keyOf(posts[0])).toBe("order-42:file~1");
    expect(keyOf(posts[1])).toBe("A".repeat(255));
  });

  for (const [label, bad] of [
    ["empty", ""],
    ["256 characters", "a".repeat(256)],
    ["a space", "a b"],
    ["a non-ASCII character", "clé"],
    ["a control character", "a\tb"],
    ["a newline", "a\nb"],
  ] as const) {
    it(`refuses a caller key with ${label} before sending anything`, async () => {
      server = await start();
      const c = testClient(server.url);
      for (const params of [
        { url: "https://a.example/b.wav" },
        { source: Buffer.from("x") },
        { source: Buffer.alloc(BIG) },
      ]) {
        const err: any = await c.listen.submit({ ...params, model: "m", language: "ja", idempotencyKey: bad }).catch((e) => e);
        expect(err).toBeInstanceOf(ValidationError);
        expect(err.code).toBe("validation_failed");
      }
      const tErr = await c.listen.transcribe({ url: "https://a.example/b.wav", model: "m", language: "ja", idempotencyKey: bad }).catch((e) => e);
      expect(tErr).toBeInstanceOf(ValidationError);
      expect(server.requests.length).toBe(0);
    });
  }

  it("maps 422 idempotency_key_reused to BadRequestError, not retried", async () => {
    server = await start((_q, res) => apiError(res, 422, "idempotency_key_reused"));
    const err: any = await testClient(server.url, {}, fakeClock()).listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja", idempotencyKey: "k1" }).catch((e) => e);
    expect(err).toBeInstanceOf(BadRequestError);
    expect(err.status).toBe(422);
    expect(err.code).toBe("idempotency_key_reused");
    expect(submits(server).length).toBe(1);
  });
});

describe("multipart retries resend the whole file", () => {
  const audio = Buffer.from(Array.from({ length: 70_000 }, (_, i) => i % 251));

  it("bytes", async () => {
    server = await start(failFirst(1));
    await testClient(server.url, {}, fakeClock()).listen.submit({ source: audio, model: "m", language: "ja" });
    const [a, b] = submits(server);
    expect((await fileOf(a)).equals(audio)).toBe(true);
    expect((await fileOf(b)).equals(audio)).toBe(true);
  });

  it("a file path", async () => {
    const dir = mkdtempSync(join(tmpdir(), "vbx-sdk-"));
    const path = join(dir, "a.wav");
    writeFileSync(path, audio);
    server = await start(failFirst(2));
    await testClient(server.url, {}, fakeClock()).listen.submit({ source: path, model: "m", language: "ja" });
    const all = submits(server);
    expect(all.length).toBe(3);
    for (const q of all) expect((await fileOf(q)).equals(audio)).toBe(true);
  });

  it("a stream (read once, sent whole on every attempt)", async () => {
    server = await start(failFirst(1, 429, "rate_limited"));
    const stream = Readable.from([audio.subarray(0, 30_000), audio.subarray(30_000)]);
    await testClient(server.url, {}, fakeClock()).listen.submit({ source: stream, size: audio.length, model: "m", language: "ja" });
    const [a, b] = submits(server);
    expect((await fileOf(a)).equals(audio)).toBe(true);
    expect((await fileOf(b)).equals(audio)).toBe(true);
    expect(keyOf(b)).toBe(keyOf(a));
  });
});

describe("receipt.replayed", () => {
  for (const [header, expected] of [
    [undefined, false],
    ["true", true],
    ["True", true],
    ["TRUE", true],
    ["false", false],
    ["yes", false],
  ] as const) {
    it(`is ${expected} for Idempotent-Replayed: ${header ?? "(absent)"}`, async () => {
      server = await start((_q, res) => json(res, 202, r.receipt, header === undefined ? {} : { "Idempotent-Replayed": header }));
      const receipt = await testClient(server.url).listen.submit({ url: "https://a.example/b.wav", model: "m", language: "ja" });
      expect(receipt.replayed).toBe(expected);
      expect(receipt.jobId).toBe(r.receipt.job_id);
    });
  }

  it("is read from the upload flow's submit too", async () => {
    server = await start((_q, res) => json(res, 202, r.receipt, { "Idempotent-Replayed": "true" }));
    const receipt = await testClient(server.url).listen.submit({ source: Buffer.alloc(BIG), model: "m", language: "ja" });
    expect(receipt.replayed).toBe(true);
  });
});
