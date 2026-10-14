/**
 * Text-to-speech voices: `GET /v1/speak/voices` (spec §5.1, §6).
 */
import type { HttpClient, RequestOptions } from "../transport/http.js";
import { arr, nullStr, obj, str } from "../transport/json.js";

export interface Voice {
  model: string;
  modelDisplayName: string;
  code: string;
  displayName: string | null;
  language: string;
  gender: string | null;
  style: string | null;
  sampleUrl: string | null;
}

export function parseVoices(body: unknown): Voice[] {
  return arr(obj(body).items).map((raw) => {
    const v = obj(raw);
    return {
      model: str(v.model),
      modelDisplayName: str(v.model_display_name),
      code: str(v.code),
      displayName: nullStr(v.display_name),
      language: str(v.language),
      gender: nullStr(v.gender),
      style: nullStr(v.style),
      sampleUrl: nullStr(v.sample_url),
    };
  });
}

/** `GET /v1/speak/voices`. */
export async function listVoices(http: HttpClient, options: RequestOptions = {}): Promise<Voice[]> {
  return parseVoices(await http.requestJson({ method: "GET", path: "/v1/speak/voices", call: "get", ...options }));
}
