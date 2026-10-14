/**
 * Speech-to-text models: `GET /v1/listen/models` (spec §4.1, §6).
 */
import type { HttpClient, RequestOptions } from "../transport/http.js";
import { arr, nullStr, obj, optNum, str } from "../transport/json.js";

export interface ModelFeature {
  paramName: string;
  displayName: string;
  type: string;
  maxItems: number | null;
  description: string | null;
}

export interface ModelMode {
  mode: string;
  features: ModelFeature[];
}

export interface Model {
  model: string;
  displayName: string;
  language: string;
  modes: ModelMode[];
}

export function parseModels(body: unknown): Model[] {
  return arr(obj(body).items).map((raw) => {
    const m = obj(raw);
    return {
      model: str(m.model),
      displayName: str(m.display_name),
      language: str(m.language),
      modes: arr(m.modes).map((rawMode) => {
        const md = obj(rawMode);
        return {
          mode: str(md.mode),
          features: arr(md.features).map((rawF) => {
            const f = obj(rawF);
            return {
              paramName: str(f.param_name),
              displayName: str(f.display_name),
              type: str(f.type),
              maxItems: optNum(f.max_items) ?? null,
              description: nullStr(f.description),
            };
          }),
        };
      }),
    };
  });
}

/** `GET /v1/listen/models`. */
export async function listModels(http: HttpClient, options: RequestOptions = {}): Promise<Model[]> {
  return parseModels(await http.requestJson({ method: "GET", path: "/v1/listen/models", call: "get", ...options }));
}
