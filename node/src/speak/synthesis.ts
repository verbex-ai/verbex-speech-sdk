/**
 * One utterance over HTTP: `POST /v1/speak`, whole or streamed (spec §5.2).
 */
import { idempotencyHeaders, isReplayed } from "../transport/common.js";
import type { HttpClient, OpenResponse, RequestOptions } from "../transport/http.js";
import { SpeakError } from "./errors.js";

/** The gateway's limit for `POST /v1/speak`. */
export const SPEAK_TIMEOUT_MS = 360_000;

export interface SynthesizeParams extends RequestOptions {
  model: string;
  voice: string;
  text: string;
  /** `pcm` (default): raw 16 kHz mono s16le. `wav`: the same with a 44-byte header. */
  format?: "pcm" | "wav";
  /** Your id for the utterance, echoed back as `requestId`. */
  requestId?: string;
  /** Generation settings the model offers (e.g. `temperature`), sent as top-level fields. */
  settings?: Record<string, unknown>;
  tags?: string[];
  /**
   * Sent as `Idempotency-Key`, 1 to 255 visible ASCII characters. Default: a random UUID made
   * once per call and reused by every retry of it. A retry with the same key after the audio
   * was spoken is spoken again without being billed (up to 3 times in 24 hours).
   */
  idempotencyKey?: string;
}

export interface SpeechAudio {
  audio: Buffer;
  contentType: string;
  /** From `X-Request-Id`. */
  requestId: string | undefined;
  /**
   * True when the API answered `Idempotent-Replayed: true`: an earlier request with the same
   * idempotency key was spoken, and this one is spoken again without being billed.
   */
  replayed: boolean;
}

/** Audio streamed as it is synthesised. The headers are known before the first chunk. */
export interface SpeechStream extends AsyncIterable<Buffer> {
  contentType: string;
  requestId: string | undefined;
  /** As `SpeechAudio.replayed`. */
  replayed: boolean;
}

/** Speaks one utterance and returns the whole audio. A body cut short is a `SpeakError`, never short audio. */
export async function synthesize(http: HttpClient, params: SynthesizeParams): Promise<SpeechAudio> {
  const stream = await synthesizeStream(http, params);
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(chunk);
  return { audio: Buffer.concat(chunks), contentType: stream.contentType, requestId: stream.requestId, replayed: stream.replayed };
}

/** Speaks one utterance and yields the audio chunks as they arrive. */
export async function synthesizeStream(http: HttpClient, params: SynthesizeParams): Promise<SpeechStream> {
  // One key for every attempt of this call, so a retry is not billed a second time.
  const headers = idempotencyHeaders(params.idempotencyKey);
  const body: Record<string, unknown> = { ...(params.settings ?? {}), model: params.model, voice: params.voice, text: params.text };
  if (params.requestId !== undefined) body.request_id = params.requestId;
  const query: Array<[string, string]> = [["format", params.format ?? "pcm"]];
  for (const t of params.tags ?? []) query.push(["tag", t]);
  const open = await http.open({
    method: "POST",
    path: "/v1/speak",
    query,
    json: body,
    call: "speak",
    timeoutMs: params.timeoutMs ?? Math.max(SPEAK_TIMEOUT_MS, http.config.timeoutMs),
    traceId: params.traceId,
    headers,
  });
  const requestId = open.response.headers.get("x-request-id") ?? params.requestId;
  return {
    contentType: open.response.headers.get("content-type") ?? "",
    requestId,
    replayed: isReplayed(open.response.headers),
    [Symbol.asyncIterator]: () => readAudio(open, requestId),
  };
}

async function* readAudio(open: OpenResponse, requestId: string | undefined): AsyncGenerator<Buffer> {
  const body = open.response.body;
  const reader = body?.getReader();
  let finished = false;
  try {
    if (!reader) return;
    for (;;) {
      let result: ReadableStreamReadResult<Uint8Array>;
      try {
        result = await reader.read();
      } catch {
        finished = true;
        const why = open.timedOut() ? "the call ran past its timeout" : "the connection failed";
        throw new SpeakError("audio_incomplete", `The audio ended early: ${why}. Treat the utterance as failed.`, requestId);
      }
      if (result.done) {
        finished = true;
        return;
      }
      yield Buffer.from(result.value.buffer, result.value.byteOffset, result.value.byteLength);
    }
  } finally {
    open.release();
    // The caller stopped early: drop the connection rather than leave it open.
    if (!finished) await reader?.cancel().catch(() => undefined);
  }
}
