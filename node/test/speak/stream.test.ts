import { afterEach, describe, expect, it } from "vitest";
import type { WebSocket } from "ws";
import { AuthenticationError, SpeakError, StreamError, ValidationError } from "../../src/index.js";
import { TEST_KEY, delay, fixture, startWs, testClient, type TestWsServer } from "../helpers.js";

const fx = fixture("speak-stream.json");
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

let server: TestWsServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

/** Plays the fixture script: for each client step, wait for that frame, then send the server steps after it. */
function playScript(seen: string[]) {
  return (ws: WebSocket) => {
    const script = fx.script as any[];
    let i = 0;
    const runServerSteps = () => {
      while (i < script.length && script[i].server) {
        const s = script[i++].server;
        if (s.binary_bytes !== undefined) ws.send(Buffer.alloc(s.binary_bytes, 1), { binary: true });
        else ws.send(JSON.stringify(s.text));
      }
    };
    ws.on("message", (data, isBinary) => {
      const step = script[i++];
      if (isBinary || !step?.client) {
        seen.push("unexpected frame");
        return;
      }
      seen.push(JSON.stringify(JSON.parse(data.toString())) === JSON.stringify(step.client.text) ? "ok" : `bad ${data}`);
      runServerSteps();
    });
  };
}

async function collect(utt: AsyncIterable<Buffer>): Promise<number> {
  let n = 0;
  for await (const chunk of utt) n += chunk.length;
  return n;
}

describe("speak.stream (conformance/speak-stream.json)", () => {
  it("speaks turn by turn; an utterance error leaves the session open", async () => {
    const seen: string[] = [];
    server = await startWs(playScript(seen));
    const session = await testClient(server.url).speak.stream({ tags: ["t1"] });
    expect(server.handshakes[0].url.pathname).toBe("/v1/speak/stream");
    expect(server.handshakes[0].url.searchParams.getAll("tag")).toEqual(["t1"]);
    expect(server.handshakes[0].headers.authorization).toBe(`Token ${TEST_KEY}`);

    const t1 = session.say({ model: "verbex_v1_bn", voice: "maria_2", text: "one", requestId: "turn-1" });
    expect(await collect(t1)).toBe(fx.expect["turn-1"].audio_bytes);
    expect(await t1.done).toEqual({ requestId: "turn-1", audioSeconds: fx.expect["turn-1"].audio_seconds });

    const t2 = session.say({ model: "verbex_v1_bn", voice: "maria_2", text: "", requestId: "turn-2" });
    const err: any = await collect(t2).catch((e) => e);
    expect(err).toBeInstanceOf(SpeakError);
    expect(err.name).toBe(fx.expect["turn-2"].error);
    expect(err.code).toBe(fx.expect["turn-2"].code);
    expect(err.fields).toEqual(fx.expect["turn-2"].fields);
    expect(err.requestId).toBe("turn-2");
    await expect(t2.done).rejects.toBeInstanceOf(SpeakError);
    expect(session.ended).toBe(!fx.expect["turn-2"].session_open);

    const t3 = session.say({ model: "verbex_v1_bn", voice: "maria_2", text: "three", requestId: "turn-3" });
    const [bytes, result] = await Promise.all([collect(t3), t3.done]);
    expect(bytes).toBe(fx.expect["turn-3"].audio_bytes);
    expect(result.audioSeconds).toBe(fx.expect["turn-3"].audio_seconds);

    expect(seen).toEqual(["ok", "ok", "ok"]);
    await session.close();
    expect(session.ended).toBe(true);
  });
});

describe("speak.stream behaviour", () => {
  function echoServer(onFrame?: (ws: WebSocket, msg: any) => void) {
    return (ws: WebSocket) =>
      ws.on("message", (data) => {
        const msg = JSON.parse(data.toString());
        if (onFrame) return onFrame(ws, msg);
        ws.send(Buffer.alloc(100), { binary: true });
        ws.send(JSON.stringify({ event: "done", request_id: msg.request_id, audio_seconds: 0.003 }));
      });
  }

  it("generates a UUID request_id and sends settings as top-level fields", async () => {
    const frames: any[] = [];
    server = await startWs(echoServer((ws, msg) => {
      frames.push(msg);
      ws.send(JSON.stringify({ event: "done", request_id: msg.request_id, audio_seconds: 0 }));
    }));
    const session = await testClient(server.url).speak.stream();
    const utt = session.say({ model: "m", voice: "v", text: "hi", settings: { temperature: 0.5 } });
    expect(utt.requestId).toMatch(UUID_RE);
    const result = await utt.done;
    expect(result.requestId).toBe(utt.requestId);
    expect(frames[0]).toEqual({ temperature: 0.5, model: "m", voice: "v", text: "hi", request_id: utt.requestId });
    await session.close();
  });

  it("refuses a second say while one is in progress", async () => {
    server = await startWs(echoServer((ws, msg) => setTimeout(() => ws.send(JSON.stringify({ event: "done", request_id: msg.request_id, audio_seconds: 0 })), 100)));
    const session = await testClient(server.url).speak.stream();
    const first = session.say({ model: "m", voice: "v", text: "a" });
    expect(() => session.say({ model: "m", voice: "v", text: "b" })).toThrow(ValidationError);
    await first.done;
    const second = session.say({ model: "m", voice: "v", text: "c" });
    await second.done;
    await session.close();
  });

  it("buffers audio when only done is awaited", async () => {
    server = await startWs(echoServer());
    const session = await testClient(server.url).speak.stream();
    const utt = session.say({ model: "m", voice: "v", text: "a" });
    await utt.done;
    expect(await collect(utt)).toBe(100);
    await session.close();
  });

  it("an error frame then a close ends the session with StreamError", async () => {
    server = await startWs(echoServer((ws, msg) => {
      ws.send(Buffer.alloc(10), { binary: true });
      ws.send(JSON.stringify({ event: "error", code: "key_revoked", message: "The key was revoked." }));
      ws.close(1008);
      void msg;
    }));
    const session = await testClient(server.url).speak.stream();
    const utt = session.say({ model: "m", voice: "v", text: "a" });
    const err: any = await utt.done.catch((e) => e);
    expect(err.code).toBe("key_revoked");
    await delay(50);
    expect(session.ended).toBe(true);
    let after: any;
    try {
      session.say({ model: "m", voice: "v", text: "b" });
    } catch (e) {
      after = e;
    }
    expect(after).toBeInstanceOf(StreamError);
    expect(after.code).toBe("key_revoked");
    expect(after.closeCode).toBe(1008);
    expect(after.retryable).toBe(false);
  });

  it("a limit refusal right after opening ends the session with StreamError", async () => {
    server = await startWs((ws) => {
      ws.send(JSON.stringify({ event: "error", code: "service_unavailable", message: "Try later." }));
      ws.close(1013);
    });
    const session = await testClient(server.url).speak.stream();
    await delay(100);
    let err: any;
    try {
      session.say({ model: "m", voice: "v", text: "a" });
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe("service_unavailable");
    expect(err.closeCode).toBe(1013);
    expect(err.retryable).toBe(true);
  });

  it("a close mid-utterance without an error fails the utterance with StreamError", async () => {
    server = await startWs(echoServer((ws) => {
      ws.send(Buffer.alloc(10), { binary: true });
      ws.terminate();
    }));
    const session = await testClient(server.url).speak.stream();
    const utt = session.say({ model: "m", voice: "v", text: "a" });
    const err: any = await collect(utt).catch((e) => e);
    expect(err).toBeInstanceOf(StreamError);
    expect(err.code).toBe("stream_closed");
    expect(err.closeCode).toBe(1006);
  });

  it("raises the HTTP error when the handshake is refused", async () => {
    server = await startWs(() => undefined, () => ({ status: 401, body: JSON.stringify({ error: { code: "unauthenticated", message: "no", request_id: "r" } }) }));
    await expect(testClient(server.url).speak.stream()).rejects.toBeInstanceOf(AuthenticationError);
  });
});
