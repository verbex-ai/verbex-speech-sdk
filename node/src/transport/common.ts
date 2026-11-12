/**
 * Request pieces shared by the calls that carry an `Idempotency-Key` (submit and speak).
 */
import { randomUUID } from "node:crypto";
import { ValidationError } from "../errors.js";

const IDEMPOTENCY_KEY_RE = /^[\x21-\x7E]{1,255}$/;

/**
 * The `Idempotency-Key` header for one call: the caller's key, else a new UUID v4. Made once
 * per call and sent on every retry of it. A key that is not 1 to 255 visible ASCII characters
 * is refused here, before anything is sent.
 */
export function idempotencyHeaders(key: string | undefined): Record<string, string> {
  const idempotencyKey = key ?? randomUUID();
  if (typeof idempotencyKey !== "string" || !IDEMPOTENCY_KEY_RE.test(idempotencyKey)) {
    throw new ValidationError("The idempotency key must be 1 to 255 visible ASCII characters.");
  }
  return { "Idempotency-Key": idempotencyKey };
}

/** True when the API answered `Idempotent-Replayed: true` (any case, surrounding spaces ignored). */
export function isReplayed(headers?: { get(name: string): string | null }): boolean {
  return (headers?.get("idempotent-replayed") ?? "").trim().toLowerCase() === "true";
}
