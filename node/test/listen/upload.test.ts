import { afterEach, describe, expect, it } from "vitest";
import { Readable } from "node:stream";
import { inspect } from "node:util";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { BadRequestError, UploadError, ValidationError, UPLOAD_MAX_BYTES } from "../../src/index.js";
import { TEST_KEY, apiError, fakeClock, fixture, json, startHttp, testClient, type RecordedRequest, type TestServer } from "../helpers.js";

const r = fixture("responses.json");
const SECRET = "X-Goog-Signature=secret";

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

type Step = (req: RecordedRequest, res: ServerResponse) => unknown;

/** One server plays both the API and the storage service. */
async function start(opts: { put?: Step; submit?: Step; storageUrl?: string } = {}): Promise<TestServer> {
  const s = await startHttp((req, res) => {
    if (req.method === "POST" && req.path === "/v1/listen/uploads") {
      const size = JSON.parse(req.body.toString()).size;
      return json(res, 201, {
        ...r.upload_started,
        upload_url: `${opts.storageUrl ?? s.url}/storage/bucket/jobs/uploads/7f3c/audio?${SECRET}`,
        headers: { ...r.upload_started.headers, "x-goog-content-length-range": `${size},${size}` },
      });
    }
    if (req.path.startsWith("/storage/")) return opts.put ? opts.put(req, res) : (res.writeHead(200), res.end());
    if (req.method === "POST" && req.path === "/v1/listen") return opts.submit ? opts.submit(req, res) : json(res, 202, r.receipt);
    json(res, 404, {});
  });
  return s;
}

const BIG = 1_048_577;
const bigAudio = () => Buffer.alloc(BIG, 7);

function assertNoSecrets(err: unknown): void {
  const shown = [String(err), (err as Error).message, inspect(err, { depth: 10, showHidden: true }), JSON.stringify(err), (err as Error).stack ?? ""].join("\n");
  expect(shown).not.toContain(SECRET);
  expect(shown).not.toContain("/storage/bucket");
  expect(shown).not.toContain(TEST_KEY);
}

describe("upload flow (files over 1 MiB)", () => {
  it("starts the upload, PUTs with exactly the given headers, then submits the upload_id", async () => {
    server = await start();
    const receipt = await testClient(server.url).listen.submit({
      source: bigAudio(),
      filename: "meeting.wav",
      model: "verbex_jp_stt_x1",
      language: "ja",
      hotwords: ["かいみょう"],
      metadata: { call_id: "c-42" },
      webhookUrl: "https://example.com/done",
      tags: ["t1"],
    });
    expect(receipt.jobId).toBe(r.receipt.job_id);
    const [start_, put, submit] = server!.requests;
    expect(server!.requests.length).toBe(3);

    expect(start_.path).toBe("/v1/listen/uploads");
    expect(start_.query.getAll("tag")).toEqual(["t1"]);
    expect(JSON.parse(start_.body.toString())).toEqual({ size: BIG, filename: "meeting.wav" });

    expect(put.method).toBe("PUT");
    expect(put.url).toBe(`/storage/bucket/jobs/uploads/7f3c/audio?${SECRET}`);
    expect(put.body.equals(bigAudio())).toBe(true);
    expect(put.headers["x-goog-if-generation-match"]).toBe("0");
    expect(put.headers["x-goog-content-length-range"]).toBe(`${BIG},${BIG}`);
    expect(put.headers["content-length"]).toBe(String(BIG));
    const names = Object.keys(put.headers).sort();
    expect(names).toEqual(["connection", "content-length", "host", "x-goog-content-length-range", "x-goog-if-generation-match"]);
    expect(put.headers.authorization).toBeUndefined();
    expect(put.headers["x-trace-id"]).toBeUndefined();

    expect(submit.path).toBe("/v1/listen");
    expect(submit.query.getAll("tag")).toEqual(["t1"]);
    expect(submit.headers.authorization).toBe(`Token ${TEST_KEY}`);
    expect(JSON.parse(submit.body.toString())).toEqual({
      upload_id: r.upload_started.upload_id,
      model: "verbex_jp_stt_x1",
      language: "ja",
      hotwords: ["かいみょう"],
      metadata: { call_id: "c-42" },
      webhook_url: "https://example.com/done",
    });
  });

  it("streams a file path, named after the file", async () => {
    server = await start();
    const dir = mkdtempSync(join(tmpdir(), "vbx-sdk-"));
    const path = join(dir, "long-call.wav");
    writeFileSync(path, bigAudio());
    await testClient(server.url).listen.submit({ source: path, model: "m", language: "ja" });
    expect(JSON.parse(server!.requests[0].body.toString())).toEqual({ size: BIG, filename: "long-call.wav" });
    expect(server!.requests[1].body.equals(bigAudio())).toBe(true);
  });

  it("streams a Readable with an explicit size", async () => {
    server = await start();
    const data = bigAudio();
    const stream = Readable.from([data.subarray(0, 500_000), data.subarray(500_000)]);
    await testClient(server.url).listen.submit({ source: stream, size: BIG, model: "m", language: "ja" });
    expect(server!.requests[1].headers["content-length"]).toBe(String(BIG));
    expect(server!.requests[1].body.equals(data)).toBe(true);
  });

  it("retries the submit on 409 upload_incomplete, 1 s apart", async () => {
    let n = 0;
    server = await start({ submit: (_q, res) => (++n === 1 ? apiError(res, 409, "upload_incomplete") : json(res, 202, r.receipt)) });
    const clock = fakeClock();
    await testClient(server.url, {}, clock).listen.submit({ source: bigAudio(), model: "m", language: "ja" });
    expect(n).toBe(2);
    expect(clock.sleeps).toEqual([1000]);
    expect(server!.requests.filter((q) => q.method === "PUT").length).toBe(1);
  });

  it("gives up on upload_incomplete after maxRetries", async () => {
    server = await start({ submit: (_q, res) => apiError(res, 409, "upload_incomplete") });
    const clock = fakeClock();
    const err: any = await testClient(server.url, { maxRetries: 2 }, clock).listen.submit({ source: bigAudio(), model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(BadRequestError);
    expect(err.code).toBe("upload_incomplete");
    expect(server!.requests.filter((q) => q.path === "/v1/listen").length).toBe(3);
    expect(clock.sleeps).toEqual([1000, 1000]);
  });

  it("reports 412 from storage as UploadError, without the URL, and does not submit", async () => {
    server = await start({ put: (_q, res) => (res.writeHead(412, { "Content-Type": "application/xml" }), res.end(`<Error>PreconditionFailed for /storage/bucket?${SECRET}</Error>`)) });
    const err: any = await testClient(server.url).listen.submit({ source: bigAudio(), model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    expect(err.status).toBe(412);
    expect(err.message).toMatch(/already used/i);
    assertNoSecrets(err);
    expect(server!.requests.map((q) => q.method + " " + q.path.split("/")[1])).toEqual(["POST v1", "PUT storage"]);
  });

  it("reports storage's answer when it answers and closes before the body is out", async () => {
    // Storage may answer before it has the whole body and close the connection. The request then
    // closes before it finishes, which the stream pipeline reports as ERR_STREAM_PREMATURE_CLOSE.
    // Storage's answer, not that, is the result: a 412 stays a 412.
    const storage: Server = createServer((req, res) => {
      req.once("data", () => {
        res.writeHead(412, { Connection: "close" });
        res.end();
      });
    });
    await new Promise<void>((resolve) => storage.listen(0, "127.0.0.1", resolve));
    try {
      server = await start({ storageUrl: `http://127.0.0.1:${(storage.address() as AddressInfo).port}` });
      const data = bigAudio();
      // The rest of the body waits until storage has answered and closed.
      const body = Readable.from((async function* () {
        yield data.subarray(0, 64 * 1024);
        await new Promise((resolve) => setTimeout(resolve, 300));
        yield data.subarray(64 * 1024);
      })());
      const err: any = await testClient(server.url).listen.submit({ source: body, size: BIG, model: "m", language: "ja" }).catch((e) => e);
      expect(err).toBeInstanceOf(UploadError);
      expect(err.status).toBe(412);
      assertNoSecrets(err);
    } finally {
      storage.closeAllConnections();
      await new Promise<void>((resolve) => storage.close(() => resolve()));
    }
  });

  it("never retries the PUT", async () => {
    server = await start({ put: (_q, res) => (res.writeHead(503), res.end()) });
    const err: any = await testClient(server.url, { maxRetries: 2 }).listen.submit({ source: bigAudio(), model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    expect(err.status).toBe(503);
    expect(server!.requests.filter((q) => q.method === "PUT").length).toBe(1);
  });

  it("reports a broken PUT connection as UploadError without the URL", async () => {
    server = await start({ put: (_q, res) => res.socket?.destroy() });
    const err: any = await testClient(server.url).listen.submit({ source: bigAudio(), model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    expect(err.status).toBeUndefined();
    assertNoSecrets(err);
  });

  it("fails the PUT when a stream is shorter than its declared size", async () => {
    server = await start();
    const err: any = await testClient(server.url).listen.submit({ source: Readable.from([Buffer.alloc(BIG - 10)]), size: BIG, model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(UploadError);
    assertNoSecrets(err);
    expect(server!.requests.filter((q) => q.path === "/v1/listen").length).toBe(0);
  });

  it("refuses more than 100 MiB locally, sending nothing", async () => {
    server = await start();
    const stream = Readable.from([]);
    const err = await testClient(server.url).listen.submit({ source: stream, size: UPLOAD_MAX_BYTES + 1, model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect(server!.requests.length).toBe(0);
  });

  it("refuses a stream without a size", async () => {
    server = await start();
    const err = await testClient(server.url).listen.submit({ source: Readable.from([Buffer.from("x")]), model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect(server!.requests.length).toBe(0);
  });
});
