import { randomUUID } from "node:crypto";
import { APIConnectionError, APITimeoutError, ApiError } from "../errors.js";
import { errorFromResponse } from "./errors.js";
import { RETRY_AFTER_CAP_SEC, backoffDelayMs, shouldRetry, type Outcome, type RetryCall } from "./retry.js";

export interface HttpConfig {
  apiKey: string;
  /** Without a trailing slash. */
  baseUrl: string;
  timeoutMs: number;
  maxRetries: number;
  userAgent: string;
  sleep: (ms: number) => Promise<void>;
  now: () => number;
  random: () => number;
}

export interface RequestSpec {
  method: "GET" | "POST";
  path: string;
  query?: Array<[string, string]>;
  /** A JSON body. */
  json?: unknown;
  /** A multipart body, built afresh for each attempt. */
  form?: () => FormData;
  /** Which retry rule applies (spec §8). */
  call: RetryCall;
  /** This call's timeout. Defaults to the client's. */
  timeoutMs?: number;
  /** The caller's own X-Trace-Id, else a fresh UUID per attempt. */
  traceId?: string;
  /** Extra headers, the same on every attempt (the submit's or speak's Idempotency-Key). */
  headers?: Record<string, string>;
}

/** A response whose body has not been read yet. `release` stops its timeout timer. */
export interface OpenResponse {
  response: Response;
  signal: AbortSignal;
  timedOut: () => boolean;
  release: () => void;
}

/** Per-call options every method accepts. */
export interface RequestOptions {
  /** Overrides this call's timeout. */
  timeoutMs?: number;
  /** Sent as X-Trace-Id instead of a fresh UUID. Must be a UUID for the API to keep it. */
  traceId?: string;
}

/**
 * The API's HTTP transport: auth and tracing headers, timeouts, error mapping and the
 * retry loop. The API key is held in a private field so it never shows in `inspect`.
 */
export class HttpClient {
  readonly #apiKey: string;
  readonly config: Omit<HttpConfig, "apiKey">;

  constructor(config: HttpConfig) {
    const { apiKey, ...rest } = config;
    this.#apiKey = apiKey;
    this.config = rest;
  }

  /** Headers for an API request (HTTP or WebSocket handshake). Never used for the storage PUT. */
  authHeaders(traceId?: string): Record<string, string> {
    return {
      Authorization: `Token ${this.#apiKey}`,
      "User-Agent": this.config.userAgent,
      "X-Trace-Id": traceId ?? randomUUID(),
    };
  }

  /** Replaces the API key in text that came from elsewhere (an error body, a cause). */
  redact(text: string): string {
    return text.split(this.#apiKey).join("[redacted]");
  }

  url(path: string, query?: Array<[string, string]>, scheme?: "ws"): string {
    let base = this.config.baseUrl;
    if (scheme === "ws") base = base.replace(/^http/, "ws");
    const qs = query && query.length ? "?" + new URLSearchParams(query).toString() : "";
    return base + path + qs;
  }

  /** Sends a request and returns its JSON body, retrying per spec §8. */
  async requestJson(spec: RequestSpec): Promise<unknown> {
    return (await this.requestJsonWithHeaders(spec)).body;
  }

  /** As `requestJson`, also returning the response headers. */
  async requestJsonWithHeaders(spec: RequestSpec): Promise<{ body: unknown; headers: Headers }> {
    const open = await this.open(spec);
    try {
      const text = await open.response.text();
      return { body: text ? JSON.parse(text) : undefined, headers: open.response.headers };
    } catch (err) {
      if (open.timedOut()) throw new APITimeoutError(`No complete response within ${this.timeoutFor(spec)} ms (${spec.method} ${spec.path}).`);
      throw new APIConnectionError(`The response to ${spec.method} ${spec.path} could not be read.`, { cause: err });
    } finally {
      open.release();
    }
  }

  /**
   * Sends a request and returns the response with its body unread, once a 2xx status has
   * arrived. Retries (per spec §8) happen only before that point. The timeout keeps running
   * while the body is read, until `release`.
   */
  async open(spec: RequestSpec): Promise<OpenResponse> {
    for (let attempt = 0; ; attempt++) {
      let outcome: Outcome;
      let error: Error;
      try {
        return await this.attempt(spec);
      } catch (err) {
        if (err instanceof ApiError) {
          outcome = err.status;
        } else if (err instanceof APITimeoutError) {
          outcome = "timeout";
        } else if (err instanceof APIConnectionError) {
          outcome = "connection_error";
        } else {
          throw err;
        }
        error = err as Error;
      }
      if (attempt >= this.config.maxRetries || !shouldRetry(spec.call, outcome, (error as ApiError).code)) throw error;
      const retryAfter = error instanceof ApiError ? error.retryAfter : undefined;
      if (retryAfter !== undefined && retryAfter > RETRY_AFTER_CAP_SEC) throw error;
      await this.config.sleep(retryAfter !== undefined ? retryAfter * 1000 : backoffDelayMs(attempt, this.config.random));
    }
  }

  timeoutFor(spec: RequestSpec): number {
    return spec.timeoutMs ?? this.config.timeoutMs;
  }

  private async attempt(spec: RequestSpec): Promise<OpenResponse> {
    const controller = new AbortController();
    let timedOut = false;
    const timeoutMs = this.timeoutFor(spec);
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs);
    const release = () => clearTimeout(timer);

    const headers: Record<string, string> = { ...spec.headers, ...this.authHeaders(spec.traceId), Accept: "application/json" };
    let body: BodyInit | undefined;
    if (spec.json !== undefined) {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(spec.json);
    } else if (spec.form) {
      body = spec.form();
    }

    let response: Response;
    try {
      response = await fetch(this.url(spec.path, spec.query), { method: spec.method, headers, body, signal: controller.signal });
    } catch (err) {
      release();
      if (timedOut) throw new APITimeoutError(`No response within ${timeoutMs} ms (${spec.method} ${spec.path}).`);
      throw new APIConnectionError(`Could not reach the API (${spec.method} ${spec.path}): ${this.redact(describe(err))}`, { cause: err });
    }

    if (response.status >= 200 && response.status < 300) {
      return { response, signal: controller.signal, timedOut: () => timedOut, release };
    }
    let text = "";
    try {
      text = await response.text();
    } catch {
      // The status alone still classifies the error.
    } finally {
      release();
    }
    throw errorFromResponse(response.status, response.headers, this.redact(text));
  }
}

function describe(err: unknown): string {
  const cause = (err as { cause?: { code?: string; message?: string } })?.cause;
  return cause?.code ?? cause?.message ?? (err as Error)?.message ?? "connection failed";
}
