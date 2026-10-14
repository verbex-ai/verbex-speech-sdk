import { request as httpRequest, type ClientRequest, type IncomingMessage } from "node:http";
import { request as httpsRequest } from "node:https";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { VerbexError } from "../errors.js";
import { obj, optNum, optStr, str } from "../transport/json.js";

/** The storage PUT of the upload flow failed. `status` is the storage service's, if it answered. */
export class UploadError extends VerbexError {
  readonly status: number | undefined;
  constructor(message: string, status?: number) {
    super(message);
    this.status = status;
  }
}

/** Internal: the answer to `POST /v1/listen/uploads`. Holds a credential (`uploadUrl`). */
export interface UploadStarted {
  uploadId: string;
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  uploadUrlExpiresInSeconds?: number;
  submitWindowSeconds?: number;
}

export function parseUploadStarted(body: unknown): UploadStarted {
  const u = obj(body);
  const headers: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj(u.headers))) headers[k] = str(v);
  return {
    uploadId: str(u.upload_id),
    uploadUrl: str(u.upload_url),
    method: optStr(u.method) ?? "PUT",
    headers,
    uploadUrlExpiresInSeconds: optNum(u.upload_url_expires_in_seconds),
    submitWindowSeconds: optNum(u.submit_window_seconds),
  };
}

/**
 * Step 2 of the upload flow (spec §4.3): one PUT of the whole file to the signed URL.
 *
 * Sends every header from the upload response exactly, plus Content-Length, and nothing else
 * of ours: no Authorization, no X-Trace-Id, no User-Agent. Never retried (the URL allows one
 * write). Only connecting is timed; the body takes as long as it takes. The URL is a
 * credential: it never appears in an error.
 */
export async function putUpload(upload: UploadStarted, body: Readable | Buffer, size: number, connectTimeoutMs = 30_000): Promise<void> {
  let url: URL;
  try {
    url = new URL(upload.uploadUrl);
  } catch {
    throw new UploadError("The upload URL from the API is not a valid URL.");
  }
  const send = url.protocol === "https:" ? httpsRequest : url.protocol === "http:" ? httpRequest : undefined;
  if (!send) throw new UploadError("The upload URL from the API is not http(s).");

  const headers: Record<string, string> = {};
  for (const [k, v] of Object.entries(upload.headers)) {
    if (k.toLowerCase() !== "content-length") headers[k] = v;
  }
  headers["Content-Length"] = String(size);

  const req: ClientRequest = send(url, { method: upload.method || "PUT", headers, agent: false, setHost: true });
  const failure = (why: string, status?: number) => new UploadError(`The upload to storage failed: ${why}. Start a new upload.`, status);

  const connectTimer = setTimeout(() => req.destroy(Object.assign(new Error("connect timeout"), { code: "ETIMEDOUT" })), connectTimeoutMs);
  req.once("socket", (socket) => {
    if (!socket.connecting) clearTimeout(connectTimer);
    socket.once(url.protocol === "https:" ? "secureConnect" : "connect", () => clearTimeout(connectTimer));
    socket.once("close", () => clearTimeout(connectTimer));
  });

  const response = new Promise<IncomingMessage>((resolve, reject) => {
    req.once("response", resolve);
    req.once("error", reject);
  });
  // Seen by the awaits below; this only stops an unhandled rejection while the body is sent.
  response.catch(() => undefined);

  try {
    if (Buffer.isBuffer(body)) {
      req.end(body);
    } else {
      let sent = 0;
      const counter = new Transform({
        transform(chunk: Buffer, _enc, done) {
          sent += chunk.length;
          if (sent > size) done(new SizeMismatch());
          else done(null, chunk);
        },
        flush(done) {
          done(sent === size ? null : new SizeMismatch());
        },
      });
      await pipeline(body, counter, req);
    }
    const res = await response;
    res.resume();
    await new Promise<void>((resolve) => {
      res.once("end", resolve);
      res.once("error", () => resolve());
      res.once("close", () => resolve());
    });
    const status = res.statusCode ?? 0;
    if (status < 200 || status >= 300) {
      throw failure(
        status === 412 ? "HTTP 412, the upload URL was already used" : `HTTP ${status} from the storage service`,
        status,
      );
    }
  } catch (err) {
    req.destroy();
    if (err instanceof UploadError) throw err;
    if (err instanceof SizeMismatch) throw failure(`the stream did not hold exactly the declared ${size} bytes`);
    const code = (err as { code?: unknown })?.code;
    // Only the error code: a message might quote the URL.
    throw failure(typeof code === "string" ? code : "the connection failed");
  } finally {
    clearTimeout(connectTimer);
  }
}

class SizeMismatch extends Error {}
