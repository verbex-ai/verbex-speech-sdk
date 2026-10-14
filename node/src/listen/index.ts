/**
 * Speech-to-text: `client.listen`, also importable as `@verbex-ai/verbex-speech-sdk/listen`.
 */
import type { HttpClient, RequestOptions } from "../transport/http.js";
import { Jobs, type Job, type Receipt, type Segment, type SubmitParams, type TranscribeParams, type WaitOptions } from "./jobs.js";
import { listModels, type Model } from "./models.js";
import { ListenSession, openListenSession, transcribeStream, type ListenStreamOptions, type TranscribeStreamOptions } from "./stream.js";

/** Speech-to-text: `client.listen`. */
export class Listen {
  readonly #http: HttpClient;
  readonly #jobs: Jobs;

  constructor(http: HttpClient) {
    this.#http = http;
    this.#jobs = new Jobs(http);
  }

  /** `GET /v1/listen/models`. */
  models(options: RequestOptions = {}): Promise<Model[]> {
    return listModels(this.#http, options);
  }

  /** `GET /v1/listen/{job_id}`. */
  get(jobId: string, options: RequestOptions = {}): Promise<Job> {
    return this.#jobs.get(jobId, options);
  }

  /** Submits a file or URL for transcription and returns the receipt. */
  submit(params: SubmitParams): Promise<Receipt> {
    return this.#jobs.submit(params);
  }

  /** `submit` then `wait`: returns the finished job (completed, failed or cancelled). */
  transcribe(params: TranscribeParams): Promise<Job> {
    return this.#jobs.transcribe(params);
  }

  /**
   * Polls the job until it is completed, failed or cancelled, and returns it. Does not raise
   * for failed or cancelled jobs; read `status` and `error`. A 429 while polling waits
   * `Retry-After` and continues.
   */
  wait(jobId: string, options: WaitOptions = {}): Promise<Job> {
    return this.#jobs.wait(jobId, options);
  }

  /** Opens a live transcription session; resolves once the server's `ready` frame arrives. */
  stream(options: ListenStreamOptions): Promise<ListenSession> {
    return openListenSession(this.#http, options);
  }

  /**
   * Opens a session, sends every chunk, finishes, and returns all segments in order.
   * Does not pace the audio: the caller does.
   */
  transcribeStream(pcm: AsyncIterable<Uint8Array> | Iterable<Uint8Array>, options: TranscribeStreamOptions): Promise<Segment[]> {
    return transcribeStream((o) => this.stream(o), pcm, options);
  }
}

export type { Model, ModelMode, ModelFeature } from "./models.js";
export { UploadError, WaitTimeoutError } from "./jobs.js";
export type { Receipt, Job, JobStatus, JobError, Segment, SubmitParams, TranscribeParams, WaitOptions } from "./jobs.js";
export { DIRECT_MAX_BYTES, UPLOAD_MAX_BYTES } from "./source.js";
export type { AudioSource } from "./source.js";
export { ListenSession } from "./stream.js";
export type { ListenStreamOptions, ListenEvent, TranscribeStreamOptions } from "./stream.js";
export { verifyWebhook, parseWebhook, WebhookVerificationError } from "./webhook.js";
export type { VerifyWebhookOptions, HeaderMap } from "./webhook.js";
