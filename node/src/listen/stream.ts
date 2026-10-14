import type WebSocket from "ws";
import { APITimeoutError, StreamError, ValidationError } from "../errors.js";
import type { HttpClient } from "../transport/http.js";
import { num } from "../transport/json.js";
import { AsyncQueue } from "../transport/queue.js";
import { connectWs } from "../transport/ws.js";
import type { Segment } from "./jobs.js";

export interface ListenStreamOptions {
  model: string;
  language: string;
  hotwords?: string[];
  tags?: string[];
  /** Limit for the handshake and the `ready` frame. Default: the client's timeout. */
  timeoutMs?: number;
  traceId?: string;
}

/** What a live session delivers, in order. */
export type ListenEvent =
  | { type: "segment"; index: number; startSec: number; endSec: number; text: string }
  | { type: "configured"; hotwords: string[] }
  | { type: "done"; totalSegments: number }
  /** A refusal. If the server then closes, iteration ends with a `StreamError`; otherwise the session goes on. */
  | { type: "error"; code: string; message: string };

/** Frames above this are split. */
export const MAX_FRAME_BYTES = 65_536;
/** How long `close()` waits for `done` after sending `eof` itself. */
export const CLOSE_WAIT_MS = 10_000;

/**
 * A live transcription session. Iterate it for events; `send` raw PCM (16 kHz, mono, s16le);
 * `finish` when the audio ends; `close` when done (it sends `eof` itself if `finish` was not
 * called, and waits up to 10 s for `done`).
 */
export class ListenSession implements AsyncIterable<ListenEvent> {
  /** From the server's `ready` frame. Quote it for support. */
  sessionId = "";
  readonly #ws: WebSocket;
  readonly #events = new AsyncQueue<ListenEvent>();
  #finished = false;
  #doneSeen = false;
  #closedByUs = false;
  #closed = false;
  #lastError: { code: string; message: string } | undefined;
  #terminal: StreamError | undefined;
  #onDone: Array<() => void> = [];
  #onClose: Array<() => void> = [];
  readonly ready: Promise<void>;

  /** @internal Use `client.listen.stream(...)`. */
  constructor(ws: WebSocket, readyTimeoutMs: number) {
    this.#ws = ws;
    let resolveReady!: () => void;
    let rejectReady!: (e: unknown) => void;
    let isReady = false;
    this.ready = new Promise<void>((res, rej) => {
      resolveReady = res;
      rejectReady = rej;
    });
    this.ready.catch(() => undefined);
    const readyTimer = setTimeout(() => {
      if (isReady) return;
      rejectReady(new APITimeoutError(`No ready frame within ${readyTimeoutMs} ms.`));
      ws.terminate();
    }, readyTimeoutMs);

    ws.on("message", (data, isBinary) => {
      if (isBinary) return;
      let msg: any;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      switch (msg?.type) {
        case "ready":
          this.sessionId = String(msg.session_id ?? "");
          isReady = true;
          clearTimeout(readyTimer);
          resolveReady();
          break;
        case "segment":
          this.#events.push({ type: "segment", index: num(msg.index), startSec: num(msg.start_sec), endSec: num(msg.end_sec), text: String(msg.text ?? "") });
          break;
        case "configured":
          this.#events.push({ type: "configured", hotwords: Array.isArray(msg.hotwords) ? msg.hotwords.map(String) : [] });
          break;
        case "done":
          this.#doneSeen = true;
          this.#events.push({ type: "done", totalSegments: num(msg.total_segments) });
          for (const f of this.#onDone.splice(0)) f();
          break;
        case "error":
          this.#lastError = { code: String(msg.code ?? "error"), message: String(msg.message ?? "") };
          if (isReady) this.#events.push({ type: "error", ...this.#lastError });
          break;
        default:
        // Unknown frame types are ignored.
      }
    });

    ws.on("close", (code: number) => {
      this.#closed = true;
      clearTimeout(readyTimer);
      let error: StreamError | undefined;
      if (this.#lastError && !this.#doneSeen) {
        error = new StreamError(this.#lastError.code, this.#lastError.message, code);
      } else if (!this.#doneSeen && !this.#closedByUs) {
        error = new StreamError("stream_closed", "The server closed the session before done.", code);
      }
      this.#terminal = error;
      if (!isReady) rejectReady(error ?? new StreamError("stream_closed", "The session closed before it was ready.", code));
      this.#events.end(error);
      for (const f of this.#onDone.splice(0)) f();
      for (const f of this.#onClose.splice(0)) f();
    });
  }

  /** True once the socket has closed. */
  get ended(): boolean {
    return this.#closed;
  }

  /** Sends raw PCM (16 kHz, mono, s16le). Frames above 65,536 bytes are split. */
  send(pcm: Uint8Array): void {
    this.#assertOpen();
    if (this.#finished) throw new ValidationError("Cannot send audio after finish().");
    const buf = Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength);
    for (let off = 0; off < buf.length; off += MAX_FRAME_BYTES) {
      this.#ws.send(buf.subarray(off, Math.min(off + MAX_FRAME_BYTES, buf.length)), { binary: true });
    }
  }

  /** Replaces the hotwords. The answer arrives as a `configured` (or `error`) event. */
  configure(hotwords: string[]): void {
    this.#assertOpen();
    this.#ws.send(JSON.stringify({ type: "configure", hotwords }));
  }

  /** Says the audio has ended; the remaining segments and `done` follow. */
  finish(): void {
    if (this.#finished) return;
    this.#assertOpen();
    this.#finished = true;
    this.#ws.send(JSON.stringify({ type: "eof" }));
  }

  /**
   * Ends the session. Without an earlier `finish()`, sends `eof` and waits up to 10 s for
   * `done`, so the last words are not lost. Events already received stay readable.
   */
  async close(): Promise<void> {
    if (this.#closed) return;
    if (!this.#finished) {
      try {
        this.finish();
      } catch {
        // Closing anyway.
      }
    }
    if (!this.#doneSeen) {
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, CLOSE_WAIT_MS);
        this.#onDone.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
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

  [Symbol.asyncIterator](): AsyncIterator<ListenEvent> {
    return this.#events[Symbol.asyncIterator]();
  }

  #assertOpen(): void {
    if (this.#closed || this.#ws.readyState !== this.#ws.OPEN) {
      throw this.#terminal ?? new ValidationError("The session is closed.");
    }
  }
}

/** Opens the socket and resolves once `ready` arrives. */
export async function openListenSession(http: HttpClient, options: ListenStreamOptions): Promise<ListenSession> {
  const query: Array<[string, string]> = [
    ["model", options.model],
    ["language", options.language],
  ];
  for (const h of options.hotwords ?? []) query.push(["hotwords", h]);
  for (const t of options.tags ?? []) query.push(["tag", t]);
  const timeoutMs = options.timeoutMs ?? http.config.timeoutMs;
  const { ws, opened } = connectWs(http, "/v1/listen/stream", query, { traceId: options.traceId, timeoutMs });
  const session = new ListenSession(ws, timeoutMs);
  try {
    await opened;
    await session.ready;
  } catch (err) {
    ws.terminate();
    throw err;
  }
  return session;
}

export interface TranscribeStreamOptions extends ListenStreamOptions {
  /** Called for each event as it arrives (segments, configured, errors). */
  onEvent?: (event: ListenEvent) => void;
}

/**
 * Opens a session, sends every chunk, finishes, and returns all segments in order.
 * Does not pace the audio: the caller does.
 */
export async function transcribeStream(
  open: (options: ListenStreamOptions) => Promise<ListenSession>,
  pcm: AsyncIterable<Uint8Array> | Iterable<Uint8Array>,
  options: TranscribeStreamOptions,
): Promise<Segment[]> {
  const session = await open(options);
  const segments: Segment[] = [];
  const reading = (async () => {
    for await (const ev of session) {
      options.onEvent?.(ev);
      if (ev.type === "segment") segments.push({ index: ev.index, startSec: ev.startSec, endSec: ev.endSec, text: ev.text });
    }
  })();
  reading.catch(() => undefined);
  try {
    try {
      for await (const chunk of pcm as AsyncIterable<Uint8Array>) session.send(chunk);
      session.finish();
    } catch (sendError) {
      // A session that failed while we sent: its StreamError (from the reader) is the real cause.
      await reading;
      throw sendError;
    }
    await reading;
    return segments;
  } finally {
    await session.close();
  }
}
