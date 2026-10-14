import { createHmac, timingSafeEqual } from "node:crypto";
import { VerbexError } from "../errors.js";
import { parseJob, type Job } from "./jobs.js";

/** `parseWebhook` was given a body whose signature does not verify. */
export class WebhookVerificationError extends VerbexError {}

/** A header map: a plain object (any key case) or a `Headers` instance. */
export type HeaderMap = Record<string, string | string[] | undefined> | { get(name: string): string | null };

export interface VerifyWebhookOptions {
  /** The API key that submitted the job. */
  apiKey: string;
  /** The exact raw request body. */
  rawBody: string | Uint8Array;
  /** `X-Verbex-Timestamp`. Taken from `headers` when omitted. */
  timestamp?: string;
  /** `X-Verbex-Signature`. Taken from `headers` when omitted. */
  signature?: string;
  /** The request's headers; the two above are found case-insensitively. */
  headers?: HeaderMap;
  /** Seconds. Default 300. */
  toleranceSec?: number;
  /** The verifier's clock in unix seconds. Default: now. */
  now?: number;
}

const CONTEXT = "verbex-webhook-v1";

function header(headers: HeaderMap | undefined, name: string): string | undefined {
  if (!headers || typeof headers !== "object") return undefined;
  if (typeof (headers as any).get === "function") {
    const v = (headers as { get(n: string): string | null }).get(name);
    return typeof v === "string" ? v : undefined;
  }
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(headers as Record<string, unknown>)) {
    if (k.toLowerCase() !== lower) continue;
    if (typeof v === "string") return v;
    if (Array.isArray(v) && v.length === 1 && typeof v[0] === "string") return v[0];
    return undefined;
  }
  return undefined;
}

/**
 * Verifies a webhook callback's signature (spec §4.5). Returns false, never throws, for any
 * bad input.
 */
export function verifyWebhook(options: VerifyWebhookOptions): boolean {
  try {
    if (!options || typeof options !== "object") return false;
    const { apiKey, rawBody } = options;
    const timestamp = options.timestamp ?? header(options.headers, "X-Verbex-Timestamp");
    const signature = options.signature ?? header(options.headers, "X-Verbex-Signature");
    if (typeof apiKey !== "string" || !apiKey) return false;
    if (typeof rawBody !== "string" && !(rawBody instanceof Uint8Array)) return false;
    if (typeof timestamp !== "string" || !/^-?\d+$/.test(timestamp)) return false;
    if (typeof signature !== "string" || !signature.startsWith("v1=")) return false;

    const tolerance = typeof options.toleranceSec === "number" ? options.toleranceSec : 300;
    const now = typeof options.now === "number" ? options.now : Date.now() / 1000;
    if (Math.abs(now - Number(timestamp)) > tolerance) return false;

    const signingKey = createHmac("sha256", Buffer.from(apiKey, "utf8")).update(CONTEXT, "utf8").digest("hex");
    const mac = createHmac("sha256", Buffer.from(signingKey, "utf8"));
    mac.update(Buffer.from(timestamp + ".", "utf8"));
    mac.update(typeof rawBody === "string" ? Buffer.from(rawBody, "utf8") : rawBody);
    const expected = Buffer.from(mac.digest("hex"), "utf8");
    const given = Buffer.from(signature.slice(3), "utf8");
    if (given.length !== expected.length) return false;
    return timingSafeEqual(given, expected);
  } catch {
    return false;
  }
}

/** Verifies, then parses the body as a job. Raises `WebhookVerificationError` on a bad signature. */
export function parseWebhook(options: VerifyWebhookOptions): Job {
  if (!verifyWebhook(options)) {
    throw new WebhookVerificationError("The webhook signature does not verify (wrong key, altered body, or a timestamp outside the tolerance).");
  }
  const text = typeof options.rawBody === "string" ? options.rawBody : Buffer.from(options.rawBody).toString("utf8");
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new VerbexError("The webhook body is not JSON.");
  }
  return parseJob(body);
}
