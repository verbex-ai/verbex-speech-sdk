/**
 * Transcription jobs (spec §4.2 to §4.4): submit a file or URL, poll, wait.
 * Response types follow spec §6: camelCase fields mapped from the API's snake_case, unknown
 * fields ignored, fields the API may leave out `undefined`, documented nullable fields `null`.
 */
import { randomUUID } from "node:crypto";
import { BadRequestError, RateLimitError, ValidationError, VerbexError } from "../errors.js";
import type { HttpClient, RequestOptions, RequestSpec } from "../transport/http.js";
import { arr, num, obj, optNum, optStr, str } from "../transport/json.js";
import { DIRECT_MAX_BYTES, UPLOAD_MAX_BYTES, resolveSource, type AudioSource } from "./source.js";
import { parseUploadStarted, putUpload } from "./upload.js";

export { UploadError } from "./upload.js";

export type JobStatus = "queued" | "processing" | "completed" | "failed" | "cancelled";

export interface Receipt {
  jobId: string;
  status: JobStatus;
  queuePosition?: number;
  /**
   * True when the API answered `Idempotent-Replayed: true`: the job was made by an earlier
   * request with the same idempotency key, and nothing was submitted now.
   */
  replayed: boolean;
}

export interface Segment {
  index: number;
  startSec: number;
  endSec: number;
  text: string;
}

export interface JobError {
  code: string;
  message: string;
}

export interface Job {
  jobId: string;
  status: JobStatus;
  model: string;
  language?: string;
  audioDurationSec?: number;
  /** 0 to 1. Present on every poll; a webhook body may leave it out. */
  progress?: number;
  /** Present on every poll; a webhook body may leave it out. */
  segmentCount?: number;
  failedSegmentCount: number;
  text?: string;
  segments: Segment[];
  error?: JobError;
  metadata: Record<string, unknown>;
  /** `status === "completed"`. */
  completed: boolean;
}

/** `wait` ran past its timeout. `job` is the last job seen. */
export class WaitTimeoutError extends VerbexError {
  readonly job: Job | undefined;
  constructor(message: string, job?: Job) {
    super(message);
    this.job = job;
  }
}

export function parseReceipt(body: unknown, headers?: { get(name: string): string | null }): Receipt {
  const r = obj(body);
  const replayed = (headers?.get("idempotent-replayed") ?? "").trim().toLowerCase() === "true";
  const receipt: Receipt = { jobId: str(r.job_id), status: str(r.status) as JobStatus, replayed };
  const pos = optNum(r.queue_position);
  if (pos !== undefined) receipt.queuePosition = pos;
  return receipt;
}

export function parseSegment(raw: unknown): Segment {
  const s = obj(raw);
  return { index: num(s.index), startSec: num(s.start_sec), endSec: num(s.end_sec), text: str(s.text) };
}

export function parseJob(body: unknown): Job {
  const j = obj(body);
  const status = str(j.status) as JobStatus;
  const job: Job = {
    jobId: str(j.job_id),
    status,
    model: str(j.model),
    failedSegmentCount: num(j.failed_segment_count),
    segments: arr(j.segments).map(parseSegment),
    metadata: obj(j.metadata),
    completed: status === "completed",
  };
  const language = optStr(j.language);
  if (language !== undefined) job.language = language;
  const duration = optNum(j.audio_duration_sec);
  if (duration !== undefined) job.audioDurationSec = duration;
  const progress = optNum(j.progress);
  if (progress !== undefined) job.progress = progress;
  const segmentCount = optNum(j.segment_count);
  if (segmentCount !== undefined) job.segmentCount = segmentCount;
  const text = optStr(j.text);
  if (text !== undefined) job.text = text;
  if (j.error && typeof j.error === "object") job.error = { code: str(j.error.code), message: str(j.error.message) };
  return job;
}

/** The gateway's limit for a submission. */
export const SUBMIT_TIMEOUT_MS = 330_000;
const TERMINAL = new Set(["completed", "failed", "cancelled"]);
const MIN_POLL_MS = 500;
const UPLOAD_INCOMPLETE_RETRY_MS = 1_000;

export interface SubmitParams extends RequestOptions {
  /** A file path, bytes, or a readable stream (then `size` is required). Exactly one of `source` and `url`. */
  source?: AudioSource;
  /** Audio at an https URL the API fetches. */
  url?: string;
  /** The exact byte size of a stream `source`. */
  size?: number;
  model: string;
  language: string;
  /** Defaults to the path's name, else `audio.wav`. */
  filename?: string;
  hotwords?: string[];
  /** At most 4096 bytes serialised (checked by the API). */
  metadata?: Record<string, unknown>;
  webhookUrl?: string;
  /** Sent as repeated `tag` query parameters. */
  tags?: string[];
  /**
   * Sent as `Idempotency-Key`, 1 to 255 visible ASCII characters. Default: a random UUID made
   * once per `submit`/`transcribe` call and reused by every retry of it. Pass your own (saved
   * with the work) to submit again safely from another process; the API keeps it 24 hours.
   */
  idempotencyKey?: string;
}

export interface WaitOptions {
  /** Milliseconds between polls. Default 2000, never below 500. */
  pollIntervalMs?: number;
  /** Give up after this many milliseconds with a `WaitTimeoutError`. Default: no limit. */
  timeoutMs?: number;
}

export interface TranscribeParams extends SubmitParams {
  pollIntervalMs?: number;
  /** Limit for the wait after submitting (see `WaitOptions.timeoutMs`). */
  waitTimeoutMs?: number;
}

const JOB_ID_RE = /^[A-Za-z0-9_-]{1,128}$/;
const IDEMPOTENCY_KEY_RE = /^[\x21-\x7E]{1,255}$/;

/** Internal: the job calls behind `client.listen`. */
export class Jobs {
  readonly #http: HttpClient;

  constructor(http: HttpClient) {
    this.#http = http;
  }

  /** `GET /v1/listen/{job_id}`. */
  async get(jobId: string, options: RequestOptions = {}): Promise<Job> {
    if (typeof jobId !== "string" || !JOB_ID_RE.test(jobId)) throw new ValidationError("The job id must be letters, digits, _ or - only.");
    return parseJob(await this.#http.requestJson({ method: "GET", path: `/v1/listen/${jobId}`, call: "get", ...options }));
  }

  /** Submits a file or URL for transcription and returns the receipt. */
  async submit(params: SubmitParams): Promise<Receipt> {
    const hasSource = params.source !== undefined && params.source !== null;
    const hasUrl = params.url !== undefined && params.url !== null;
    if (hasSource === hasUrl) throw new ValidationError("Give exactly one of `source` and `url`.");
    const idempotencyKey = params.idempotencyKey ?? randomUUID();
    if (typeof idempotencyKey !== "string" || !IDEMPOTENCY_KEY_RE.test(idempotencyKey)) {
      throw new ValidationError("The idempotency key must be 1 to 255 visible ASCII characters.");
    }
    // One key for every attempt of this call, so a retry gets the first request's receipt.
    const headers = { "Idempotency-Key": idempotencyKey };
    const query = (params.tags ?? []).map((t): [string, string] => ["tag", t]);
    const timeoutMs = params.timeoutMs ?? Math.max(SUBMIT_TIMEOUT_MS, this.#http.config.timeoutMs);

    if (hasUrl) {
      return this.#postSubmit({ query, json: jsonOptions(params, { url: params.url }), timeoutMs, traceId: params.traceId, headers });
    }

    const src = await resolveSource(params.source!, params.size, params.filename);
    if (src.size > UPLOAD_MAX_BYTES) {
      throw new ValidationError(`The file is ${src.size} bytes; the largest accepted is ${UPLOAD_MAX_BYTES} bytes (100 MiB).`, "audio_too_large");
    }

    if (src.size <= DIRECT_MAX_BYTES) {
      const bytes = await src.readAll();
      const form = () => {
        const f = new FormData();
        f.append("file", new Blob([new Uint8Array(bytes)]), src.filename);
        f.append("model", params.model);
        f.append("language", params.language);
        for (const h of params.hotwords ?? []) f.append("hotwords", h);
        if (params.metadata !== undefined) f.append("metadata", JSON.stringify(params.metadata));
        if (params.webhookUrl !== undefined) f.append("webhook_url", params.webhookUrl);
        return f;
      };
      return this.#postSubmit({ query, form, timeoutMs, traceId: params.traceId, headers });
    }

    // The upload flow (spec §4.3).
    const started = parseUploadStarted(
      await this.#http.requestJson({
        method: "POST",
        path: "/v1/listen/uploads",
        query,
        json: { size: src.size, filename: src.filename },
        call: "upload_start",
        traceId: params.traceId,
      }),
    );
    await putUpload(started, src.body(), src.size, this.#http.config.timeoutMs);

    for (let attempt = 0; ; attempt++) {
      try {
        return await this.#postSubmit({ query, json: jsonOptions(params, { upload_id: started.uploadId }), timeoutMs, traceId: params.traceId, headers });
      } catch (err) {
        const incomplete = err instanceof BadRequestError && err.status === 409 && err.code === "upload_incomplete";
        if (!incomplete || attempt >= this.#http.config.maxRetries) throw err;
        await this.#http.config.sleep(UPLOAD_INCOMPLETE_RETRY_MS);
      }
    }
  }

  /** `POST /v1/listen`, retried per spec §8 with the same headers (the idempotency key) each time. */
  async #postSubmit(spec: Pick<RequestSpec, "query" | "json" | "form" | "timeoutMs" | "traceId" | "headers">): Promise<Receipt> {
    const { body, headers } = await this.#http.requestJsonWithHeaders({ method: "POST", path: "/v1/listen", call: "submit", ...spec });
    return parseReceipt(body, headers);
  }

  /** `submit` then `wait`: returns the finished job (completed, failed or cancelled). */
  async transcribe(params: TranscribeParams): Promise<Job> {
    const { pollIntervalMs, waitTimeoutMs, ...submit } = params;
    const receipt = await this.submit(submit);
    return this.wait(receipt.jobId, { pollIntervalMs, timeoutMs: waitTimeoutMs });
  }

  /**
   * Polls the job until it is completed, failed or cancelled, and returns it. Does not raise
   * for failed or cancelled jobs; read `status` and `error`. A 429 while polling waits
   * `Retry-After` and continues.
   */
  async wait(jobId: string, options: WaitOptions = {}): Promise<Job> {
    const { sleep, now } = this.#http.config;
    const interval = Math.max(MIN_POLL_MS, options.pollIntervalMs ?? 2_000);
    const deadline = options.timeoutMs !== undefined ? now() + options.timeoutMs : Infinity;
    let last: Job | undefined;
    for (;;) {
      let pause = interval;
      try {
        last = await this.get(jobId);
        if (TERMINAL.has(last.status)) return last;
      } catch (err) {
        if (!(err instanceof RateLimitError)) throw err;
        pause = Math.max(interval, (err.retryAfter ?? 0) * 1000);
      }
      const remaining = deadline - now();
      if (remaining <= 0) throw new WaitTimeoutError(`Job ${jobId} did not finish within ${options.timeoutMs} ms.`, last);
      await sleep(Math.min(pause, remaining));
    }
  }
}

function jsonOptions(params: SubmitParams, base: Record<string, unknown>): Record<string, unknown> {
  const body: Record<string, unknown> = { ...base, model: params.model, language: params.language };
  if (params.hotwords !== undefined) body.hotwords = params.hotwords;
  if (params.metadata !== undefined) body.metadata = params.metadata;
  if (params.webhookUrl !== undefined) body.webhook_url = params.webhookUrl;
  return body;
}
