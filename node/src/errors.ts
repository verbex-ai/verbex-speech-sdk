/**
 * The errors shared by every part of the SDK. Errors that belong to one product live with it:
 * `UploadError` and `WaitTimeoutError` in `listen/jobs`, `WebhookVerificationError` in
 * `listen/webhook`, `SpeakError` in `speak`.
 */

/** A field the API refused, as listed in an error body. */
export interface FieldError {
  name: string;
  reason: string;
}

/** Base of every error the SDK raises. */
export class VerbexError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Missing or invalid API key or base URL at construction. */
export class ConfigurationError extends VerbexError {}

/**
 * The SDK refused a call locally and sent nothing: an argument out of range (a file over
 * 100 MiB, a stream without a size), or a call the session's state does not allow
 * (`send` after `finish`, a second `say` while one is in progress).
 */
export class ValidationError extends VerbexError {}

export interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  requestId?: string;
  fields?: FieldError[];
  retryAfter?: number;
}

/** An HTTP error response from the API. */
export class ApiError extends VerbexError {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | undefined;
  readonly fields: FieldError[];
  /** Seconds, from the `Retry-After` header. */
  readonly retryAfter: number | undefined;

  constructor(init: ApiErrorInit) {
    super(`${init.status} ${init.code}: ${init.message}${init.requestId ? ` (request_id ${init.requestId})` : ""}`);
    this.status = init.status;
    this.code = init.code;
    this.requestId = init.requestId;
    this.fields = init.fields ?? [];
    this.retryAfter = init.retryAfter;
  }
}

/** 400, 405, 406, 409, 413, 415. */
export class BadRequestError extends ApiError {}
/** 401. */
export class AuthenticationError extends ApiError {}
/** 402. */
export class PaymentRequiredError extends ApiError {}
/** 403. */
export class PermissionDeniedError extends ApiError {}
/** 404. */
export class NotFoundError extends ApiError {}
/** 429. */
export class RateLimitError extends ApiError {}
/** 5xx. */
export class ServerError extends ApiError {}

/** No response: DNS, connect, TLS, reset. */
export class APIConnectionError extends VerbexError {}

/** No response within the timeout. */
export class APITimeoutError extends VerbexError {}

const RETRYABLE_STREAM_CODES = new Set(["stream_failed", "service_unavailable", "credit_unavailable", "engine_unavailable", "stream_closed"]);
const RETRYABLE_CLOSE_CODES = new Set([1011, 1013]);

/** A WebSocket session ended by an error. */
export class StreamError extends VerbexError {
  readonly code: string;
  readonly closeCode: number | undefined;
  /** True when reconnecting and resuming makes sense. */
  readonly retryable: boolean;

  constructor(code: string, message: string, closeCode?: number) {
    super(`${code}: ${message}${closeCode !== undefined ? ` (close ${closeCode})` : ""}`);
    this.code = code;
    this.closeCode = closeCode;
    this.retryable = RETRYABLE_STREAM_CODES.has(code) || (closeCode !== undefined && RETRYABLE_CLOSE_CODES.has(closeCode));
  }
}
