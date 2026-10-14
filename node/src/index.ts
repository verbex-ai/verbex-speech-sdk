/**
 * `@verbex-ai/verbex-speech-sdk`: the client and every public name. `@verbex-ai/verbex-speech-sdk/listen` and
 * `@verbex-ai/verbex-speech-sdk/speak` export each product's names on their own.
 */
export { VerbexSpeech } from "./client.js";
export type { ClientOptions } from "./client.js";
export { VERSION } from "./version.js";
export * from "./errors.js";
export { errorFromResponse, parseRetryAfter } from "./transport/errors.js";
export type { RequestOptions } from "./transport/http.js";

export { Listen, UploadError, WaitTimeoutError, DIRECT_MAX_BYTES, UPLOAD_MAX_BYTES, ListenSession, verifyWebhook, parseWebhook, WebhookVerificationError } from "./listen/index.js";
export type {
  Model,
  ModelMode,
  ModelFeature,
  Receipt,
  Job,
  JobStatus,
  JobError,
  Segment,
  SubmitParams,
  TranscribeParams,
  WaitOptions,
  TranscribeStreamOptions,
  AudioSource,
  ListenStreamOptions,
  ListenEvent,
  VerifyWebhookOptions,
  HeaderMap,
} from "./listen/index.js";

export { Speak, SpeakError, SpeakSession, Utterance } from "./speak/index.js";
export type { Voice, SynthesizeParams, SpeechAudio, SpeechStream, SpeakStreamOptions, SayParams, SpeakResult } from "./speak/index.js";
