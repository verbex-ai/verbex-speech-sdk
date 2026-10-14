import { randomUUID } from "node:crypto";
import type WebSocket from "ws";
import { StreamError, ValidationError } from "../errors.js";
import { parseFields } from "../transport/errors.js";
import type { HttpClient } from "../transport/http.js";
import { AsyncQueue } from "../transport/queue.js";
import { connectWs } from "../transport/ws.js";
import { SpeakError } from "./errors.js";

export interface SpeakStreamOptions {
  tags?: string[];
  /** Limit for the handshake. Default: the client's timeout. */
  timeoutMs?: number;
  traceId?: string;
}

export interface SayParams {
  model: string;
  voice: string;
  text: string;
  /** Generated (a UUID) when omitted, so answers can be matched. */
  requestId?: string;
  /** Generation settings the model offers, sent as top-level fields. */
  settings?: Record<string, unknown>;
}

export interface SpeakResult {
  requestId: string;
  audioSeconds: number;
}

/**
 * One utterance on a speak socket: iterate it for the PCM chunks as they arrive, and await
 * `done` for the result. A failed utterance makes both raise (`SpeakError`, or `StreamError`
 * if the session ended).
 */
export class Utterance implements AsyncIterable<Buffer> {
  readonly requestId: string;
  readonly done: Promise<SpeakResult>;
  /** @internal */ readonly chunks = new AsyncQueue<Buffer>();
  /** @internal */ resolve!: (r: SpeakResult) => void;
  /** @internal */ reject!: (e: unknown) => void;

  /** @internal */
  constructor(requestId: string) {
    this.requestId = requestId;
    this.done = new Promise<SpeakResult>((res, rej) => {
      this.resolve = res;
      this.reject = rej;
    });
    // A caller who only iterates still sees the error there; this avoids an unhandled rejection.
    this.done.catch(() => undefined);
  }

  [Symbol.asyncIterator](): AsyncIterator<Buffer> {
    return this.chunks[Symbol.asyncIterator]();
  }

  /** @internal */
  finish(result: SpeakResult): void {
    this.chunks.end();
    this.resolve(result);
  }

  /** @internal */
  fail(error: Error): void {
    this.chunks.end(error);
    this.reject(error);
  }
}

/**
 * A socket for many utterances. `say` sends one; utterances are strictly one at a time
 * (a `say` while another is in progress is refused).
 */
export class SpeakSession {
  readonly #ws: WebSocket;
  #current: Utterance | undefined;
  #closed = false;
  #closedByUs = false;
  #sessionError: { code: string; message: string } | undefined;
  #terminal: StreamError | undefined;
  #onClose: Array<() => void> = [];

  /** @internal Use `client.speak.stream()`. */
  constructor(ws: WebSocket) {
    this.#ws = ws;
    ws.on("message", (data, isBinary) => {
      if (isBinary) {
        this.#current?.chunks.push(Buffer.from(data as Buffer));
        return;
      }
      let msg: any;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      const current = this.#current;
      if (msg?.event === "done") {
        if (current && (msg.request_id == null || msg.request_id === current.requestId)) {
          this.#current = undefined;
          current.finish({ requestId: String(msg.request_id ?? current.requestId), audioSeconds: typeof msg.audio_seconds === "number" ? msg.audio_seconds : 0 });
        }
      } else if (msg?.event === "error") {
        const code = String(msg.code ?? "error");
        const message = String(msg.message ?? "");
        const requestId = typeof msg.request_id === "string" ? msg.request_id : undefined;
        if (current && (requestId === undefined || requestId === current.requestId)) {
          this.#current = undefined;
          current.fail(new SpeakError(code, message, requestId ?? current.requestId, parseFields(msg.fields)));
        }
        // Remembered in case a close follows: then it is the session's error.
        if (requestId === undefined) this.#sessionError = { code, message };
      }
    });
    ws.on("close", (code: number) => {
      this.#closed = true;
      const err = this.#sessionError
        ? new StreamError(this.#sessionError.code, this.#sessionError.message, code)
        : this.#closedByUs
          ? undefined
          : new StreamError("stream_closed", "The server closed the speak session.", code);
      this.#terminal = err;
      const current = this.#current;
      this.#current = undefined;
      current?.fail(err ?? new StreamError("stream_closed", "The session was closed.", code));
      for (const f of this.#onClose.splice(0)) f();
    });
  }

  /** True once the socket has closed. */
  get ended(): boolean {
    return this.#closed;
  }

  /** Speaks one utterance. Returns at once; iterate the result for audio, await `.done` for the end. */
  say(params: SayParams): Utterance {
    if (this.#closed || this.#ws.readyState !== this.#ws.OPEN) throw this.#terminal ?? new ValidationError("The speak session is closed.");
    if (this.#current) throw new ValidationError("An utterance is still in progress; wait for its done before the next say().");
    const requestId = params.requestId ?? randomUUID();
    const utterance = new Utterance(requestId);
    this.#current = utterance;
    this.#ws.send(JSON.stringify({ ...(params.settings ?? {}), model: params.model, voice: params.voice, text: params.text, request_id: requestId }));
    return utterance;
  }

  /** Closes the socket (1000). An utterance in progress fails. */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closedByUs = true;
    await new Promise<void>((resolve) => {
      const force = setTimeout(() => {
        this.#ws.terminate();
        resolve();
      }, 2_000);
      this.#onClose.push(() => {
        clearTimeout(force);
        resolve();
      });
      this.#ws.close(1000);
    });
  }
}

/** Opens the socket. There is no ready frame: the session is usable once open. */
export async function openSpeakSession(http: HttpClient, options: SpeakStreamOptions): Promise<SpeakSession> {
  const query = (options.tags ?? []).map((t): [string, string] => ["tag", t]);
  const { ws, opened } = connectWs(http, "/v1/speak/stream", query, { traceId: options.traceId, timeoutMs: options.timeoutMs });
  const session = new SpeakSession(ws);
  try {
    await opened;
  } catch (err) {
    ws.terminate();
    throw err;
  }
  return session;
}
