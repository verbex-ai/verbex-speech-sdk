import { VerbexError, type FieldError } from "../errors.js";

/** One utterance failed. */
export class SpeakError extends VerbexError {
  readonly code: string;
  readonly requestId: string | undefined;
  readonly fields: FieldError[];

  constructor(code: string, message: string, requestId?: string, fields?: FieldError[]) {
    super(`${code}: ${message}${requestId ? ` (request_id ${requestId})` : ""}`);
    this.code = code;
    this.requestId = requestId;
    this.fields = fields ?? [];
  }
}
