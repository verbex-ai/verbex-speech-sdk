/**
 * Maps HTTP error responses to the SDK's errors (spec §7).
 */
import {
  ApiError,
  AuthenticationError,
  BadRequestError,
  NotFoundError,
  PaymentRequiredError,
  PermissionDeniedError,
  RateLimitError,
  ServerError,
  type ApiErrorInit,
  type FieldError,
} from "../errors.js";

type ApiErrorClass = new (init: ApiErrorInit) => ApiError;

function classForStatus(status: number): ApiErrorClass {
  if ([400, 405, 406, 409, 413, 415].includes(status)) return BadRequestError;
  if (status === 401) return AuthenticationError;
  if (status === 402) return PaymentRequiredError;
  if (status === 403) return PermissionDeniedError;
  if (status === 404) return NotFoundError;
  if (status === 429) return RateLimitError;
  if (status >= 500 && status <= 599) return ServerError;
  return ApiError;
}

/** The `fields` list of an error body or frame; anything not a list gives none. */
export function parseFields(v: unknown): FieldError[] {
  return Array.isArray(v) ? v.filter((f) => f && typeof f === "object").map((f: any) => ({ name: String(f.name ?? ""), reason: String(f.reason ?? "") })) : [];
}

/** Parses `Retry-After` (delta seconds or an HTTP date) into seconds. */
export function parseRetryAfter(value: string | null | undefined, nowMs: number = Date.now()): number | undefined {
  if (value == null) return undefined;
  const v = value.trim();
  if (/^\d+$/.test(v)) return Number(v);
  const date = Date.parse(v);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - nowMs) / 1000));
}

/** Builds the error for an HTTP error response (spec §7). Never throws. */
export function errorFromResponse(status: number, headers: { get(name: string): string | null }, bodyText: string): ApiError {
  let parsed: any;
  try {
    parsed = JSON.parse(bodyText);
  } catch {
    parsed = undefined;
  }
  const err = parsed && typeof parsed === "object" && parsed.error && typeof parsed.error === "object" ? parsed.error : undefined;
  const traceId = headers.get("x-trace-id") ?? undefined;
  const fields = parseFields(err?.fields);
  const Cls = classForStatus(status);
  return new Cls({
    status,
    code: typeof err?.code === "string" && err.code ? err.code : `http_${status}`,
    message: typeof err?.message === "string" && err.message ? err.message : `The API answered HTTP ${status}.`,
    requestId: typeof err?.request_id === "string" && err.request_id ? err.request_id : traceId,
    fields,
    retryAfter: parseRetryAfter(headers.get("retry-after")),
  });
}
