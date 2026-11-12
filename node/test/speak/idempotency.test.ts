import { afterEach, describe, expect, it } from "vitest";
import type { ServerResponse } from "node:http";
import { BadRequestError, ValidationError } from "../../src/index.js";
import { apiError, fakeClock, startHttp, testClient, type RecordedRequest, type TestServer } from "../helpers.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PCM = Buffer.alloc(3200);

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

type Step = (req: RecordedRequest, res: ServerResponse) => unknown;

function spoken(res: ServerResponse, headers: Record<string, string> = {}): void {
  res.writeHead(200, { "Content-Type": "audio/L16;rate=16000;channels=1", "X-Request-Id": "r", ...headers });
  res.end(PCM);
}

/** Answers each `POST /v1/speak` with the next step, the last one for every later request. */
async function start(...steps: Step[]): Promise<TestServer> {
  let n = 0;
  return startHttp((req, res) => steps[Math.min(n++, steps.length - 1)](req, res));
}

const keyOf = (q: RecordedRequest) => q.headers["idempotency-key"];
const ok: Step = (_q, res) => spoken(res);

describe("Idempotency-Key on speak", () => {
  it("is a new UUID v4 on each synthesize call", async () => {
    server = await start(ok);
    const c = testClient(server.url);
    await c.speak.synthesize({ model: "m", voice: "v", text: "t" });
    await c.speak.synthesize({ model: "m", voice: "v", text: "t" });
    const [a, b] = server.requests.map(keyOf);
    expect(String(a)).toMatch(UUID_V4);
    expect(String(b)).toMatch(UUID_V4);
    expect(a).not.toBe(b);
  });

  it("is the same on every retry of one call (503, connection lost, then 200)", async () => {
    server = await start((_q, res) => apiError(res, 503, "service_unavailable"), (_q, res) => res.socket?.destroy(), ok);
    const out = await testClient(server.url, { maxRetries: 3 }, fakeClock()).speak.synthesize({ model: "m", voice: "v", text: "t" });
    expect(out.audio.equals(PCM)).toBe(true);
    const keys = server.requests.map(keyOf);
    expect(keys.length).toBe(3);
    expect(String(keys[0])).toMatch(UUID_V4);
    expect(new Set(keys).size).toBe(1);
  });

  it("is retried on 409 request_in_progress with the same key", async () => {
    server = await start((_q, res) => apiError(res, 409, "request_in_progress"), ok);
    const out = await testClient(server.url, {}, fakeClock()).speak.synthesize({ model: "m", voice: "v", text: "t" });
    expect(out.audio.equals(PCM)).toBe(true);
    const [a, b] = server.requests;
    expect(keyOf(b)).toBe(keyOf(a));
  });

  it("uses the caller's key verbatim, as a header and not in the body", async () => {
    server = await start(ok);
    await testClient(server.url).speak.synthesize({ model: "m", voice: "v", text: "t", idempotencyKey: "turn-42:utt-7" });
    const [req] = server.requests;
    expect(keyOf(req)).toBe("turn-42:utt-7");
    expect(req.body.toString()).not.toContain("turn-42");
  });

  it("is sent by synthesizeStream too", async () => {
    server = await start(ok);
    const stream = await testClient(server.url).speak.synthesizeStream({ model: "m", voice: "v", text: "t", idempotencyKey: "k-1" });
    for await (const _ of stream);
    expect(keyOf(server.requests[0])).toBe("k-1");
  });

  for (const [label, bad] of [
    ["empty", ""],
    ["256 characters", "k".repeat(256)],
    ["a space", "has space"],
    ["non-ASCII characters", "キー"],
  ] as const) {
    it(`refuses a caller key with ${label} before sending anything`, async () => {
      server = await start(ok);
      const c = testClient(server.url);
      const err: any = await c.speak.synthesize({ model: "m", voice: "v", text: "t", idempotencyKey: bad }).catch((e) => e);
      expect(err).toBeInstanceOf(ValidationError);
      expect(err.code).toBe("validation_failed");
      const sErr = await c.speak.synthesizeStream({ model: "m", voice: "v", text: "t", idempotencyKey: bad }).catch((e) => e);
      expect(sErr).toBeInstanceOf(ValidationError);
      expect(server.requests.length).toBe(0);
    });
  }

  for (const code of ["idempotency_key_reused", "idempotency_replays_exhausted"]) {
    it(`maps 422 ${code} to BadRequestError, not retried`, async () => {
      server = await start((_q, res) => apiError(res, 422, code));
      const err: any = await testClient(server.url, {}, fakeClock()).speak.synthesize({ model: "m", voice: "v", text: "t", idempotencyKey: "k-1" }).catch((e) => e);
      expect(err).toBeInstanceOf(BadRequestError);
      expect(err.status).toBe(422);
      expect(err.code).toBe(code);
      expect(server.requests.length).toBe(1);
    });
  }
});

describe("replayed", () => {
  for (const [header, expected] of [
    [undefined, false],
    ["true", true],
    [" True ", true],
    ["TRUE", true],
    ["false", false],
    ["yes", false],
  ] as const) {
    it(`is ${expected} for Idempotent-Replayed: ${header ?? "(absent)"}, on the audio and on the stream`, async () => {
      server = await start((_q, res) => spoken(res, header === undefined ? {} : { "Idempotent-Replayed": header }));
      const c = testClient(server.url);
      const out = await c.speak.synthesize({ model: "m", voice: "v", text: "t" });
      expect(out.replayed).toBe(expected);
      expect(out.audio.equals(PCM)).toBe(true);
      const stream = await c.speak.synthesizeStream({ model: "m", voice: "v", text: "t" });
      expect(stream.replayed).toBe(expected);
      for await (const _ of stream);
    });
  }

  it("is read from the answer to the retry that succeeded", async () => {
    server = await start((_q, res) => apiError(res, 503, "service_unavailable"), (_q, res) => spoken(res, { "Idempotent-Replayed": "true" }));
    const out = await testClient(server.url, {}, fakeClock()).speak.synthesize({ model: "m", voice: "v", text: "t" });
    expect(out.replayed).toBe(true);
    const [a, b] = server.requests;
    expect(String(keyOf(a))).toMatch(UUID_V4);
    expect(keyOf(b)).toBe(keyOf(a));
  });
});
