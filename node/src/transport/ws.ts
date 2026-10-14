import WebSocket from "ws";
import { APIConnectionError, APITimeoutError } from "../errors.js";
import { errorFromResponse } from "./errors.js";
import type { HttpClient } from "./http.js";

export interface WsConnectOptions {
  traceId?: string;
  /** Limit for the opening handshake. */
  timeoutMs?: number;
}

/**
 * Opens a WebSocket to the API with the auth headers. Returns the socket at once (so the
 * caller attaches its handlers before any frame can arrive) and a promise for the handshake.
 * A refused handshake rejects with the HTTP error (spec §7). Never retried (spec §8).
 */
export function connectWs(
  http: HttpClient,
  path: string,
  query: Array<[string, string]>,
  options: WsConnectOptions,
): { ws: WebSocket; opened: Promise<void> } {
  const timeoutMs = options.timeoutMs ?? http.config.timeoutMs;
  const ws = new WebSocket(http.url(path, query, "ws"), {
    headers: http.authHeaders(options.traceId),
    handshakeTimeout: timeoutMs,
    followRedirects: false,
    perMessageDeflate: false,
  });
  // An 'error' without a listener would crash the process; the close that follows is what ends a session.
  ws.on("error", () => undefined);

  const opened = new Promise<void>((resolve, reject) => {
    let settled = false;
    const settle = (fn: () => void) => {
      if (!settled) {
        settled = true;
        fn();
      }
    };
    ws.once("open", () => settle(resolve));
    ws.once("unexpected-response", (req, res) => {
      const chunks: Buffer[] = [];
      const finish = () => {
        req.destroy();
        const headers = {
          get(name: string): string | null {
            const v = res.headers[name.toLowerCase()];
            return Array.isArray(v) ? v[0] ?? null : v ?? null;
          },
        };
        settle(() => reject(errorFromResponse(res.statusCode ?? 0, headers, http.redact(Buffer.concat(chunks).toString("utf8")))));
      };
      res.on("data", (c: Buffer) => chunks.push(c));
      res.once("end", finish);
      res.once("error", finish);
      res.once("close", finish);
    });
    ws.once("error", (err: Error) => {
      settle(() =>
        reject(
          /timed out/i.test(err.message)
            ? new APITimeoutError(`The WebSocket handshake to ${path} did not finish within ${timeoutMs} ms.`)
            : new APIConnectionError(`Could not open the WebSocket to ${path}: ${http.redact((err as { code?: string }).code ?? err.message)}`, { cause: err }),
        ),
      );
    });
    ws.once("close", (code: number) => settle(() => reject(new APIConnectionError(`The WebSocket to ${path} closed during the handshake (close ${code}).`))));
  });
  return { ws, opened };
}
