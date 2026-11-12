/**
 * Retry rules (spec §8). Pure functions, so the decision table can be tested on its own.
 */

/** The kinds of call that have their own retry rule. */
export type RetryCall = "get" | "upload_start" | "speak" | "submit";

/** What a failed attempt produced: an HTTP status, or no response at all. */
export type Outcome = number | "connection_error" | "timeout";

const RATE_CODES = new Set(["rate_limited", "workspace_rate_limited"]);

export const BACKOFF_BASE_MS = 500;
export const BACKOFF_CAP_MS = 8_000;
export const RETRY_AFTER_CAP_SEC = 60;

/** Whether an attempt with this outcome is retried automatically. */
export function shouldRetry(call: RetryCall, outcome: Outcome, code?: string): boolean {
  // A submit or speak carries its Idempotency-Key, so a retry gets the first request's answer
  // (a speak is spoken again, not billed); a 409 request_in_progress means that first request
  // is still running.
  if ((call === "submit" || call === "speak") && outcome === 409) return code === "request_in_progress";
  if (outcome === "connection_error" || outcome === "timeout") return true;
  if (outcome === 429) return code !== undefined && RATE_CODES.has(code);
  return outcome === 502 || outcome === 503 || outcome === 504;
}

/** Full-jitter exponential backoff: uniform in [0, min(cap, base * 2^attempt)). `attempt` starts at 0. */
export function backoffDelayMs(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.min(attempt, 30));
  return Math.floor(ceiling * random());
}
