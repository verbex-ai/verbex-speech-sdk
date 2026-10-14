import { afterEach, describe, expect, it } from "vitest";
import { SpeakError } from "../../src/index.js";
import { apiError, delay, json, startHttp, testClient, type TestServer } from "../helpers.js";

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

const PCM_HEADERS = { "Content-Type": "audio/L16;rate=16000;channels=1", "X-Request-Id": "order-42" };

describe("speak.synthesize", () => {
  it("POSTs a flat JSON body with settings as top-level fields, format in the query", async () => {
    server = await startHttp((_req, res) => {
      res.writeHead(200, PCM_HEADERS);
      res.write(Buffer.alloc(3200, 1));
      res.end(Buffer.alloc(1600, 2));
    });
    const out = await testClient(server.url).speak.synthesize({
      model: "verbex_v1_bn",
      voice: "maria_2",
      text: "hello",
      requestId: "order-42",
      settings: { temperature: 0.7, top_k: 20, model: "must-not-override" },
      tags: ["t1", "t2"],
    });
    const req = server.requests[0];
    expect(req.path).toBe("/v1/speak");
    expect(req.query.get("format")).toBe("pcm");
    expect(req.query.getAll("tag")).toEqual(["t1", "t2"]);
    expect(req.headers["content-type"]).toBe("application/json");
    expect(JSON.parse(req.body.toString())).toEqual({
      model: "verbex_v1_bn",
      voice: "maria_2",
      text: "hello",
      request_id: "order-42",
      temperature: 0.7,
      top_k: 20,
    });
    expect(out.audio.length).toBe(4800);
    expect(out.audio[0]).toBe(1);
    expect(out.audio[4799]).toBe(2);
    expect(out.contentType).toBe("audio/L16;rate=16000;channels=1");
    expect(out.requestId).toBe("order-42");
  });

  it("asks for WAV with format wav", async () => {
    server = await startHttp((_req, res) => (res.writeHead(200, { "Content-Type": "audio/wav", "X-Request-Id": "gen" }), res.end(Buffer.alloc(44))));
    const out = await testClient(server.url).speak.synthesize({ model: "m", voice: "v", text: "t", format: "wav" });
    expect(server.requests[0].query.get("format")).toBe("wav");
    expect(out.contentType).toBe("audio/wav");
    expect(out.requestId).toBe("gen");
  });

  it("raises SpeakError, never short audio, when the body ends with a transport error", async () => {
    server = await startHttp(async (_req, res) => {
      res.writeHead(200, PCM_HEADERS);
      res.write(Buffer.alloc(3200));
      await delay(30);
      res.socket?.destroy();
    });
    const err: any = await testClient(server.url).speak.synthesize({ model: "m", voice: "v", text: "t" }).catch((e) => e);
    expect(err).toBeInstanceOf(SpeakError);
    expect(err.code).toBe("audio_incomplete");
    expect(err.requestId).toBe("order-42");
    expect(server.requests.length).toBe(1); // not retried once audio has arrived
  });

  it("retries before any audio (503), then returns the audio", async () => {
    let n = 0;
    server = await startHttp((_req, res) => (++n === 1 ? apiError(res, 503, "engine_unavailable") : (res.writeHead(200, PCM_HEADERS), res.end(Buffer.alloc(10)))));
    const out = await testClient(server.url).speak.synthesize({ model: "m", voice: "v", text: "t" });
    expect(out.audio.length).toBe(10);
    expect(n).toBe(2);
  });

  it("maps an error status to the API error", async () => {
    server = await startHttp((_req, res) => json(res, 404, { error: { code: "voice_not_found", message: "no", request_id: "r1" } }));
    await expect(testClient(server.url).speak.synthesize({ model: "m", voice: "nope", text: "t" })).rejects.toMatchObject({
      name: "NotFoundError",
      code: "voice_not_found",
      requestId: "r1",
    });
  });

  it("gives speak its own 360 s budget, not the client's timeout", async () => {
    server = await startHttp(async (_req, res) => {
      await delay(400);
      res.writeHead(200, PCM_HEADERS);
      res.end(Buffer.alloc(2));
    });
    const out = await testClient(server.url, { timeoutMs: 150 }).speak.synthesize({ model: "m", voice: "v", text: "t" });
    expect(out.audio.length).toBe(2);
  });
});

describe("speak.synthesizeStream", () => {
  it("has the headers before the first chunk and yields audio as it arrives", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    server = await startHttp(async (_req, res) => {
      res.writeHead(200, PCM_HEADERS);
      res.write(Buffer.alloc(3200, 9));
      await gate;
      res.end(Buffer.alloc(100, 8));
    });
    const stream = await testClient(server.url).speak.synthesizeStream({ model: "m", voice: "v", text: "t" });
    expect(stream.contentType).toBe("audio/L16;rate=16000;channels=1");
    expect(stream.requestId).toBe("order-42");
    const it = stream[Symbol.asyncIterator]();
    const first = await it.next();
    expect(first.done).toBe(false);
    expect((first.value as Buffer)[0]).toBe(9);
    release();
    let rest = 0;
    for (let n = await it.next(); !n.done; n = await it.next()) rest += n.value.length;
    expect((first.value as Buffer).length + rest).toBe(3300);
  });

  it("releases the connection when the caller stops early", async () => {
    let closed!: () => void;
    const sawClose = new Promise<void>((r) => (closed = r));
    server = await startHttp((_req, res) => {
      res.writeHead(200, PCM_HEADERS);
      res.write(Buffer.alloc(3200));
      res.on("close", () => closed());
    });
    const stream = await testClient(server.url).speak.synthesizeStream({ model: "m", voice: "v", text: "t" });
    for await (const _chunk of stream) break;
    await Promise.race([sawClose, delay(2000).then(() => Promise.reject(new Error("connection was not released")))]);
  });
});
