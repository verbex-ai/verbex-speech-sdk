/**
 * Text-to-speech: `client.speak`, also importable as `@verbex-ai/verbex-speech-sdk/speak`.
 */
import type { HttpClient, RequestOptions } from "../transport/http.js";
import { openSpeakSession, type SpeakSession, type SpeakStreamOptions } from "./stream.js";
import { synthesize, synthesizeStream, type SpeechAudio, type SpeechStream, type SynthesizeParams } from "./synthesis.js";
import { listVoices, type Voice } from "./voices.js";

/** Text-to-speech: `client.speak`. */
export class Speak {
  readonly #http: HttpClient;

  constructor(http: HttpClient) {
    this.#http = http;
  }

  /** `GET /v1/speak/voices`. */
  voices(options: RequestOptions = {}): Promise<Voice[]> {
    return listVoices(this.#http, options);
  }

  /** Speaks one utterance and returns the whole audio. A body cut short is a `SpeakError`, never short audio. */
  synthesize(params: SynthesizeParams): Promise<SpeechAudio> {
    return synthesize(this.#http, params);
  }

  /** Speaks one utterance and yields the audio chunks as they arrive. */
  synthesizeStream(params: SynthesizeParams): Promise<SpeechStream> {
    return synthesizeStream(this.#http, params);
  }

  /** Opens a socket for many utterances, one at a time. */
  stream(options: SpeakStreamOptions = {}): Promise<SpeakSession> {
    return openSpeakSession(this.#http, options);
  }
}

export { SpeakError } from "./errors.js";
export type { Voice } from "./voices.js";
export type { SynthesizeParams, SpeechAudio, SpeechStream } from "./synthesis.js";
export { SpeakSession, Utterance } from "./stream.js";
export type { SpeakStreamOptions, SayParams, SpeakResult } from "./stream.js";
