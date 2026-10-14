import { inspect } from "node:util";
import { ConfigurationError } from "./errors.js";
import { HttpClient } from "./transport/http.js";
import { Listen } from "./listen/index.js";
import { Speak } from "./speak/index.js";
import { VERSION } from "./version.js";

export interface ClientOptions {
  /** Default: env `VERBEX_API_KEY`. Must start with `vbx_`. */
  apiKey?: string;
  /** Default: env `VERBEX_BASE_URL`. A trailing slash is ignored. WebSockets use the same host. */
  baseUrl?: string;
  /** Per HTTP call, except where a call's own default is longer. Default 30 000 ms. */
  timeoutMs?: number;
  /** Automatic retries (spec §8). Default 2; 0 turns them off. */
  maxRetries?: number;
  /** Appended to `verbex-speech-node/<version>` in the User-Agent. */
  userAgent?: string;
}

/** Test hooks; not part of the public API. */
interface InternalOptions {
  _sleep?: (ms: number) => Promise<void>;
  _now?: () => number;
  _random?: () => number;
}

const realSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** The Verbex Speech API client. */
export class VerbexSpeech {
  /** Speech-to-text. */
  readonly listen: Listen;
  /** Text-to-speech. */
  readonly speak: Speak;
  readonly baseUrl: string;
  readonly timeoutMs: number;
  readonly maxRetries: number;

  constructor(options: ClientOptions = {}) {
    const opts = options as ClientOptions & InternalOptions;
    const apiKey = opts.apiKey ?? process.env.VERBEX_API_KEY;
    if (!apiKey) throw new ConfigurationError("No API key: pass `apiKey` or set VERBEX_API_KEY.");
    if (typeof apiKey !== "string" || !apiKey.startsWith("vbx_")) throw new ConfigurationError("The API key must start with vbx_.");

    const rawBase = opts.baseUrl ?? process.env.VERBEX_BASE_URL;
    if (!rawBase) throw new ConfigurationError("No base URL: pass `baseUrl` or set VERBEX_BASE_URL.");
    let parsed: URL;
    try {
      parsed = new URL(rawBase);
    } catch {
      throw new ConfigurationError("The base URL is not a valid URL.");
    }
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") throw new ConfigurationError("The base URL must be http(s).");
    if (parsed.search || parsed.hash || parsed.username || parsed.password) {
      throw new ConfigurationError("The base URL must not carry a query, fragment or credentials.");
    }
    this.baseUrl = rawBase.replace(/\/+$/, "");

    this.timeoutMs = opts.timeoutMs ?? 30_000;
    if (!(Number.isFinite(this.timeoutMs) && this.timeoutMs > 0)) throw new ConfigurationError("timeoutMs must be a positive number.");
    this.maxRetries = opts.maxRetries ?? 2;
    if (!(Number.isInteger(this.maxRetries) && this.maxRetries >= 0)) throw new ConfigurationError("maxRetries must be a non-negative integer.");

    const http = new HttpClient({
      apiKey,
      baseUrl: this.baseUrl,
      timeoutMs: this.timeoutMs,
      maxRetries: this.maxRetries,
      userAgent: `verbex-speech-node/${VERSION}${opts.userAgent ? ` ${opts.userAgent}` : ""}`,
      sleep: opts._sleep ?? realSleep,
      now: opts._now ?? Date.now,
      random: opts._random ?? Math.random,
    });
    this.listen = new Listen(http);
    this.speak = new Speak(http);
  }

  toString(): string {
    return `VerbexSpeech(${this.baseUrl})`;
  }

  toJSON(): Record<string, unknown> {
    return { baseUrl: this.baseUrl, timeoutMs: this.timeoutMs, maxRetries: this.maxRetries };
  }

  [inspect.custom](): string {
    return this.toString();
  }
}
