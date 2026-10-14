import { afterEach, describe, expect, it } from "vitest";
import type { WebSocket } from "ws";
import { APIConnectionError, AuthenticationError, PermissionDeniedError, StreamError, ValidationError, type ListenEvent } from "../../src/index.js";
import { TEST_KEY, delay, fixture, recordFrames, startWs, testClient, type TestWsServer } from "../helpers.js";

const fx = fixture("listen-stream.json");

let server: TestWsServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

/**
 * Plays a fixture script on the server side: server steps are sent, client steps are awaited
 * and checked, a close step closes with that code (1006 = drop the connection).
 */
function play(script: any[], seen: string[] = []) {
  return (ws: WebSocket) => {
    const inbox: Array<{ text?: any; binary?: Buffer }> = [];
    let wake: (() => void) | undefined;
    ws.on("message", (data, isBinary) => {
      inbox.push(isBinary ? { binary: Buffer.from(data as Buffer) } : { text: JSON.parse(data.toString()) });
      wake?.();
    });
    const nextClientFrame = async () => {
      while (!inbox.length) await new Promise<void>((r) => (wake = r));
      return inbox.shift()!;
    };
    (async () => {
      for (const step of script) {
        if (step.server) ws.send(JSON.stringify(step.server));
        else if (step.client) {
          const frame = await nextClientFrame();
          if (step.client.binary_bytes !== undefined) {
            seen.push(frame.binary && frame.binary.length === step.client.binary_bytes ? "ok" : `bad binary ${JSON.stringify(frame)}`);
          } else {
            seen.push(JSON.stringify(frame.text) === JSON.stringify(step.client.text) ? "ok" : `bad text ${JSON.stringify(frame.text)}`);
          }
        } else if (step.close !== undefined) {
          if (step.close === 1006) ws.terminate();
          else ws.close(step.close);
        }
      }
    })();
  };
}

describe("listen.stream (conformance/listen-stream.json)", () => {
  it("normal: ready, audio, configure, segments, eof, done, close 1000", async () => {
    const seen: string[] = [];
    server = await startWs(play(fx.normal.script, seen));
    const q = fx.normal.query;
    const session = await testClient(server.url).listen.stream({ model: q.model, language: q.language, hotwords: q.hotwords, tags: ["t1"] });

    const hs = server.handshakes[0];
    expect(hs.url.pathname).toBe("/v1/listen/stream");
    expect(hs.url.searchParams.get("model")).toBe(q.model);
    expect(hs.url.searchParams.get("language")).toBe(q.language);
    expect(hs.url.searchParams.getAll("hotwords")).toEqual(q.hotwords);
    expect(hs.url.searchParams.getAll("tag")).toEqual(["t1"]);
    expect(hs.headers.authorization).toBe(`Token ${TEST_KEY}`);
    expect(hs.headers["x-trace-id"]).toBeTruthy();
    expect(session.sessionId).toBe(fx.normal.script[0].server.session_id);

    session.send(Buffer.alloc(3200));
    session.configure([]);
    const events: ListenEvent[] = [];
    for await (const ev of session) {
      events.push(ev);
      if (ev.type === "segment" && ev.index === 0) session.finish();
    }
    expect(events).toEqual([
      { type: "configured", hotwords: [] },
      { type: "segment", index: 0, startSec: 0.1, endSec: 17.48, text: "おでんわありがとうございます。" },
      { type: "segment", index: 1, startSec: 17.48, endSec: 19.68, text: "おりかえしごれんらくいたします。" },
      { type: "done", totalSegments: 2 },
    ]);
    expect(seen).toEqual(["ok", "ok", "ok"]);
    expect(session.ended).toBe(true);
  });

  it("refused: an error frame then close 1008 rejects the open with StreamError", async () => {
    server = await startWs(play(fx.refused.script));
    const err: any = await testClient(server.url).listen.stream({ model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe(fx.refused.expect.code);
    expect(err.closeCode).toBe(fx.refused.expect.close_code);
    expect(err.retryable).toBe(fx.refused.expect.retryable);
  });

  it("failed mid-session: segments before the error, then StreamError (retryable)", async () => {
    server = await startWs(play(fx.failed_mid_session.script));
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    const segments: ListenEvent[] = [];
    const err: any = await (async () => {
      for await (const ev of session) if (ev.type === "segment") segments.push(ev);
    })().catch((e) => e);
    const x = fx.failed_mid_session.expect;
    expect(segments.length).toBe(x.segments_before_error);
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe(x.code);
    expect(err.closeCode).toBe(x.close_code);
    expect(err.retryable).toBe(x.retryable);
  });

  it("closed without done: StreamError stream_closed with the close code", async () => {
    server = await startWs(play(fx.closed_without_done.script));
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    const err: any = await (async () => {
      for await (const _ev of session);
    })().catch((e) => e);
    const x = fx.closed_without_done.expect;
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe(x.code);
    expect(err.closeCode).toBe(x.close_code);
    expect(err.retryable).toBe(x.retryable);
    expect(() => session.send(Buffer.alloc(2))).toThrow(StreamError);
  });
});

describe("listen.stream behaviour", () => {
  const ready = { type: "ready", session_id: "s-1", model: "m", language: "ja", hotwords: [] };

  it("raises the HTTP error when the handshake is refused, without retrying", async () => {
    server = await startWs(
      () => undefined,
      () => ({ status: 401, body: JSON.stringify({ error: { code: "unauthenticated", message: "Authentication required.", request_id: "r-401" } }) }),
    );
    const err: any = await testClient(server.url).listen.stream({ model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(AuthenticationError);
    expect(err.code).toBe("unauthenticated");
    expect(err.requestId).toBe("r-401");
    expect(server.handshakes.length).toBe(1);

    await server.close();
    server = await startWs(() => undefined, () => ({ status: 403, body: "<html>no</html>" }));
    const err2: any = await testClient(server.url).listen.stream({ model: "m", language: "ja" }).catch((e) => e);
    expect(err2).toBeInstanceOf(PermissionDeniedError);
    expect(err2.code).toBe("http_403");
  });

  it("raises APIConnectionError when nothing listens", async () => {
    const err = await testClient("http://127.0.0.1:1").listen.stream({ model: "m", language: "ja" }).catch((e) => e);
    expect(err).toBeInstanceOf(APIConnectionError);
  });

  it("splits frames above 65,536 bytes and refuses send after finish", async () => {
    let frames: Array<{ text?: any; binary?: Buffer }> = [];
    server = await startWs((ws) => {
      frames = recordFrames(ws);
      ws.on("message", (data, isBinary) => {
        if (!isBinary && JSON.parse(data.toString()).type === "eof") ws.send(JSON.stringify({ type: "done", total_segments: 0 }));
      });
      ws.send(JSON.stringify(ready));
    });
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    session.send(Buffer.alloc(150_000, 3));
    session.finish();
    expect(() => session.send(Buffer.alloc(10))).toThrow(ValidationError);
    await delay(100);
    expect(frames.map((f) => f.binary?.length ?? f.text.type)).toEqual([65_536, 65_536, 18_928, "eof"]);
    await session.close();
  });

  it("close() without finish sends eof, waits for done, then closes 1000", async () => {
    let closeCode: number | undefined;
    let frames: Array<{ text?: any; binary?: Buffer }> = [];
    server = await startWs((ws) => {
      frames = recordFrames(ws);
      ws.on("message", (data, isBinary) => {
        if (!isBinary && JSON.parse(data.toString()).type === "eof") {
          setTimeout(() => {
            ws.send(JSON.stringify({ type: "segment", index: 0, start_sec: 0, end_sec: 1, text: "late" }));
            ws.send(JSON.stringify({ type: "done", total_segments: 1 }));
          }, 50);
        }
      });
      ws.on("close", (code) => (closeCode = code));
      ws.send(JSON.stringify(ready));
    });
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    session.send(Buffer.alloc(320));
    await session.close();
    expect(frames.map((f) => f.binary?.length ?? f.text.type)).toEqual([320, "eof"]);
    await delay(50);
    expect(closeCode).toBe(1000);
    // The events that arrived are still readable, and iteration ends without an error.
    const events: string[] = [];
    for await (const ev of session) events.push(ev.type);
    expect(events).toEqual(["segment", "done"]);
  });

  it("delivers a configure error as an event and stays open", async () => {
    server = await startWs((ws) => {
      ws.on("message", (data, isBinary) => {
        if (isBinary) return;
        const m = JSON.parse(data.toString());
        if (m.type === "configure") ws.send(JSON.stringify({ type: "error", code: "unsupported_feature", message: "No hotwords." }));
        if (m.type === "eof") {
          ws.send(JSON.stringify({ type: "done", total_segments: 0 }));
          ws.close(1000);
        }
      });
      ws.send(JSON.stringify(ready));
    });
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    session.configure(["x"]);
    const events: ListenEvent[] = [];
    for await (const ev of session) {
      events.push(ev);
      if (ev.type === "error") session.finish();
    }
    expect(events).toEqual([
      { type: "error", code: "unsupported_feature", message: "No hotwords." },
      { type: "done", totalSegments: 0 },
    ]);
  });

  it("answers pings", async () => {
    let ponged!: () => void;
    const pong = new Promise<void>((r) => (ponged = r));
    server = await startWs((ws) => {
      ws.on("pong", () => ponged());
      ws.send(JSON.stringify(ready));
      ws.ping();
    });
    const session = await testClient(server.url).listen.stream({ model: "m", language: "ja" });
    await Promise.race([pong, delay(2000).then(() => Promise.reject(new Error("no pong")))]);
    session.finish();
  });
});

describe("listen.transcribeStream", () => {
  it("sends every chunk, finishes, and returns all segments in order", async () => {
    let received = 0;
    server = await startWs((ws) => {
      ws.on("message", (data, isBinary) => {
        if (isBinary) {
          received += (data as Buffer).length;
          return;
        }
        if (JSON.parse(data.toString()).type === "eof") {
          ws.send(JSON.stringify({ type: "segment", index: 0, start_sec: 0, end_sec: 1, text: "a" }));
          ws.send(JSON.stringify({ type: "segment", index: 1, start_sec: 1, end_sec: 2, text: "b" }));
          ws.send(JSON.stringify({ type: "done", total_segments: 2 }));
          ws.close(1000);
        }
      });
      ws.send(JSON.stringify({ type: "ready", session_id: "s" }));
    });
    async function* chunks() {
      for (let i = 0; i < 5; i++) yield Buffer.alloc(6400);
    }
    const segments = await testClient(server.url).listen.transcribeStream(chunks(), { model: "m", language: "ja" });
    expect(received).toBe(32_000);
    expect(segments).toEqual([
      { index: 0, startSec: 0, endSec: 1, text: "a" },
      { index: 1, startSec: 1, endSec: 2, text: "b" },
    ]);
  });

  it("raises the session's StreamError", async () => {
    server = await startWs(play(fx.failed_mid_session.script));
    const err: any = await testClient(server.url)
      .listen.transcribeStream([Buffer.alloc(10)], { model: "m", language: "ja" })
      .catch((e) => e);
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe("stream_failed");
  });
});
