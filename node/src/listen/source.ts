import { createReadStream } from "node:fs";
import { stat, readFile } from "node:fs/promises";
import { basename } from "node:path";
import { Readable } from "node:stream";
import { ValidationError } from "../errors.js";

/** A file path, the bytes themselves, or a readable stream (with `size`). */
export type AudioSource = string | Uint8Array | Readable;

/** Files up to this size go in the request (multipart). */
export const DIRECT_MAX_BYTES = 1_048_576;
/** Files up to this size go by the upload flow; larger ones are refused locally. */
export const UPLOAD_MAX_BYTES = 104_857_600;

export interface ResolvedSource {
  size: number;
  filename: string;
  /** The whole file in memory (used only for files up to DIRECT_MAX_BYTES). */
  readAll(): Promise<Buffer>;
  /** The file as a stream or buffer, for the upload PUT. */
  body(): Readable | Buffer;
}

function isReadable(v: unknown): v is Readable {
  return v instanceof Readable || (!!v && typeof v === "object" && typeof (v as Readable).pipe === "function" && typeof (v as Readable).on === "function");
}

export async function resolveSource(source: AudioSource, size?: number, filename?: string): Promise<ResolvedSource> {
  if (typeof source === "string") {
    let info;
    try {
      info = await stat(source);
    } catch {
      throw new ValidationError(`The audio file could not be read: ${source}`);
    }
    if (!info.isFile()) throw new ValidationError(`Not a file: ${source}`);
    return {
      size: info.size,
      filename: filename ?? basename(source),
      readAll: () => readFile(source),
      body: () => createReadStream(source),
    };
  }
  if (source instanceof Uint8Array) {
    const buf = Buffer.from(source.buffer, source.byteOffset, source.byteLength);
    return { size: buf.length, filename: filename ?? "audio.wav", readAll: async () => buf, body: () => buf };
  }
  if (isReadable(source)) {
    if (typeof size !== "number" || !Number.isSafeInteger(size) || size < 0) {
      throw new ValidationError("A stream source needs its exact size in bytes (the `size` option).");
    }
    return {
      size,
      filename: filename ?? "audio.wav",
      readAll: async () => {
        const chunks: Buffer[] = [];
        let total = 0;
        for await (const chunk of source) {
          const b = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
          total += b.length;
          if (total > size) throw new ValidationError(`The stream is longer than its declared size (${size} bytes).`);
          chunks.push(b);
        }
        if (total !== size) throw new ValidationError(`The stream ended after ${total} bytes; ${size} were declared.`);
        return Buffer.concat(chunks);
      },
      body: () => source,
    };
  }
  throw new ValidationError("source must be a file path, a Buffer/Uint8Array, or a readable stream with a size.");
}
