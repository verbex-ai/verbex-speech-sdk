import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { WebSocketServer, type WebSocket } from "ws";
import { VerbexSpeech } from "../src/index.js";

const here = dirname(fileURLToPath(import.meta.url));

/** Loads a binding conformance fixture from ../conformance/. */
export function fixture<T = any>(name: string): T {
  return JSON.parse(readFileSync(join(here, "..", "..", "conformance", name), "utf8")) as T;
}

/** A fake key; never a real one. */
export const TEST_KEY = "vbx_test0000_abcdefghijklmnopqrstuvwxyz0123";

export interface RecordedRequest {
  method: string;
  url: string;
  path: string;
  query: URLSearchParams;
  headers: IncomingMessage["headers"];
  rawHeaders: string[];
  body: Buffer;
}

export type Handler = (req: RecordedRequest, res: ServerResponse) => unknown;

export interface TestServer {
  url: string;
  port: number;
  requests: RecordedRequest[];
  close(): Promise<void>;
}

/** A real HTTP server on an ephemeral port. Bodies are read fully before the handler runs. */
export async function startHttp(handler: Handler): Promise<TestServer> {
  const requests: RecordedRequest[] = [];
  const server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("error", () => undefined);
    req.on("end", () => {
      const u = new URL(req.url ?? "/", "http://localhost");
      const rec: RecordedRequest = {
        method: req.method ?? "",
        url: req.url ?? "",
        path: u.pathname,
        query: u.searchParams,
        headers: req.headers,
        rawHeaders: req.rawHeaders,
        body: Buffer.concat(chunks),
      };
      requests.push(rec);
      Promise.resolve(handler(rec, res)).catch((err) => {
        res.statusCode = 599;
        res.end(String(err));
      });
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    port,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.();
        server.close(() => resolve());
      }),
  };
}

export function json(res: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}): void {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(body));
}

export function apiError(res: ServerResponse, status: number, code: string, headers: Record<string, string> = {}): void {
  json(res, status, { error: { code, message: `${code} happened`, request_id: "00000000-0000-4000-8000-000000000000" } }, headers);
}

export interface WsHandshake {
  url: URL;
  headers: IncomingMessage["headers"];
}

export interface TestWsServer {
  url: string;
  port: number;
  handshakes: WsHandshake[];
  close(): Promise<void>;
}

/**
 * A real ws server on an ephemeral port. `onConnection` drives the socket. `refuse` lets a test
 * refuse the handshake with an HTTP status and body.
 */
export async function startWs(
  onConnection: (ws: WebSocket, hs: WsHandshake) => void,
  refuse?: (hs: WsHandshake) => { status: number; body: string; headers?: Record<string, string> } | undefined,
): Promise<TestWsServer> {
  const handshakes: WsHandshake[] = [];
  const server = createServer();
  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, socket, head) => {
    const hs: WsHandshake = { url: new URL(req.url ?? "/", "http://localhost"), headers: req.headers };
    handshakes.push(hs);
    const refusal = refuse?.(hs);
    if (refusal) {
      const headers = { "Content-Type": "application/json", "Content-Length": String(Buffer.byteLength(refusal.body)), ...(refusal.headers ?? {}) };
      socket.write(
        `HTTP/1.1 ${refusal.status} Refused\r\n` +
          Object.entries(headers).map(([k, v]) => `${k}: ${v}\r\n`).join("") +
          "\r\n" +
          refusal.body,
      );
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, hs));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    port,
    handshakes,
    close: () =>
      new Promise<void>((resolve) => {
        for (const c of wss.clients) c.terminate();
        wss.close();
        server.closeAllConnections?.();
        server.close(() => resolve());
      }),
  };
}

/** Collects every frame a ws server-side socket receives, in order. */
export function recordFrames(ws: WebSocket): Array<{ text?: any; binary?: Buffer }> {
  const frames: Array<{ text?: any; binary?: Buffer }> = [];
  ws.on("message", (data, isBinary) => {
    if (isBinary) frames.push({ binary: Buffer.from(data as Buffer) });
    else frames.push({ text: JSON.parse(data.toString()) });
  });
  return frames;
}

export interface FakeClock {
  now: () => number;
  sleeps: number[];
  sleep: (ms: number) => Promise<void>;
}

/** A clock whose sleep returns at once and advances time, so retry and polling tests run fast. */
export function fakeClock(start = 1_000_000): FakeClock {
  let t = start;
  const sleeps: number[] = [];
  return {
    now: () => t,
    sleeps,
    sleep: async (ms: number) => {
      sleeps.push(ms);
      t += ms;
    },
  };
}

/** A client against a test server, with a fake clock injected through the internal options. */
export function testClient(baseUrl: string, opts: Record<string, unknown> = {}, clock: FakeClock = fakeClock()): VerbexSpeech {
  return new VerbexSpeech({ apiKey: TEST_KEY, baseUrl, ...opts, _sleep: clock.sleep, _now: clock.now } as any);
}

export function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
