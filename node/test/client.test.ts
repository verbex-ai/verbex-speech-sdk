import { afterEach, describe, expect, it } from "vitest";
import { inspect } from "node:util";
import { VerbexSpeech, ConfigurationError, ApiError, VERSION } from "../src/index.js";
import { TEST_KEY, apiError, json, startHttp, testClient, type TestServer } from "./helpers.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

function withEnv<T>(env: Record<string, string | undefined>, fn: () => T): T {
  const saved: Record<string, string | undefined> = {};
  for (const k of Object.keys(env)) {
    saved[k] = process.env[k];
    if (env[k] === undefined) delete process.env[k];
    else process.env[k] = env[k];
  }
  try {
    return fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

describe("construction", () => {
  it("refuses a missing api key", () => {
    withEnv({ VERBEX_API_KEY: undefined }, () => {
      expect(() => new VerbexSpeech({ baseUrl: "http://127.0.0.1:1" })).toThrow(ConfigurationError);
    });
  });

  it("refuses a key that does not start with vbx_, without echoing it", () => {
    try {
      new VerbexSpeech({ apiKey: "sk_secret_value_123", baseUrl: "http://127.0.0.1:1" });
      expect.unreachable();
    } catch (e: any) {
      expect(e).toBeInstanceOf(ConfigurationError);
      expect(String(e)).not.toContain("sk_secret_value_123");
    }
  });

  it("refuses a missing or invalid base url", () => {
    withEnv({ VERBEX_BASE_URL: undefined }, () => {
      expect(() => new VerbexSpeech({ apiKey: TEST_KEY })).toThrow(ConfigurationError);
    });
    expect(() => new VerbexSpeech({ apiKey: TEST_KEY, baseUrl: "not a url" })).toThrow(ConfigurationError);
    expect(() => new VerbexSpeech({ apiKey: TEST_KEY, baseUrl: "ftp://example.com" })).toThrow(ConfigurationError);
  });

  it("refuses a negative maxRetries or non-positive timeout", () => {
    expect(() => new VerbexSpeech({ apiKey: TEST_KEY, baseUrl: "http://127.0.0.1:1", maxRetries: -1 })).toThrow(ConfigurationError);
    expect(() => new VerbexSpeech({ apiKey: TEST_KEY, baseUrl: "http://127.0.0.1:1", timeoutMs: 0 })).toThrow(ConfigurationError);
  });

  it("falls back to VERBEX_API_KEY and VERBEX_BASE_URL", async () => {
    server = await startHttp((_req, res) => json(res, 200, { items: [] }));
    const client = withEnv({ VERBEX_API_KEY: TEST_KEY, VERBEX_BASE_URL: server.url }, () => new VerbexSpeech());
    await client.listen.models();
    expect(server.requests[0].headers.authorization).toBe(`Token ${TEST_KEY}`);
  });

  it("ignores a trailing slash on the base url", async () => {
    server = await startHttp((_req, res) => json(res, 200, { items: [] }));
    await testClient(server.url + "/").listen.models();
    expect(server.requests[0].url).toBe("/v1/listen/models");
  });

  it("never shows the api key in inspect, toString or JSON", () => {
    const client = new VerbexSpeech({ apiKey: TEST_KEY, baseUrl: "http://127.0.0.1:1" });
    expect(inspect(client, { depth: 10, showHidden: true })).not.toContain(TEST_KEY);
    expect(String(client)).not.toContain(TEST_KEY);
    expect(JSON.stringify(client) ?? "").not.toContain(TEST_KEY);
  });
});

describe("request headers", () => {
  it("sends Authorization, User-Agent and a fresh UUID X-Trace-Id on every request", async () => {
    server = await startHttp((_req, res) => json(res, 200, { items: [] }));
    const client = testClient(server.url);
    await client.listen.models();
    await client.speak.voices();
    const [a, b] = server.requests;
    expect(a.headers.authorization).toBe(`Token ${TEST_KEY}`);
    expect(a.headers["user-agent"]).toBe(`verbex-speech-node/${VERSION}`);
    expect(a.headers["x-trace-id"]).toMatch(UUID_RE);
    expect(b.headers["x-trace-id"]).toMatch(UUID_RE);
    expect(a.headers["x-trace-id"]).not.toBe(b.headers["x-trace-id"]);
    expect(b.path).toBe("/v1/speak/voices");
  });

  it("appends the user agent suffix and uses a caller-supplied trace id", async () => {
    server = await startHttp((_req, res) => json(res, 200, { items: [] }));
    const client = testClient(server.url, { userAgent: "my-app/1.2" });
    await client.listen.models({ traceId: "6f1c2a3e-9b7d-4c1e-8a2f-0d5e6b7c8d9e" });
    expect(server.requests[0].headers["user-agent"]).toBe(`verbex-speech-node/${VERSION} my-app/1.2`);
    expect(server.requests[0].headers["x-trace-id"]).toBe("6f1c2a3e-9b7d-4c1e-8a2f-0d5e6b7c8d9e");
  });

  it("is version 0.1.0", () => {
    expect(VERSION).toBe("0.1.0");
  });
});

describe("secrets in errors", () => {
  it("redacts the api key even if an error body echoes it", async () => {
    server = await startHttp((req, res) =>
      json(res, 401, { error: { code: "unauthenticated", message: `bad header ${req.headers.authorization}`, request_id: "r" } }),
    );
    try {
      await testClient(server.url).listen.models();
      expect.unreachable();
    } catch (e: any) {
      expect(e).toBeInstanceOf(ApiError);
      expect(e.message).not.toContain(TEST_KEY);
      expect(inspect(e, { depth: 10, showHidden: true })).not.toContain(TEST_KEY);
    }
  });

  it("does not put the key in a connection error", async () => {
    const client = testClient("http://127.0.0.1:1", { maxRetries: 0 });
    const err: any = await client.listen.models().catch((e) => e);
    expect(err.name).toBe("APIConnectionError");
    expect(inspect(err, { depth: 10, showHidden: true })).not.toContain(TEST_KEY);
  });

  it("unused helper keeps apiError importable", () => {
    expect(typeof apiError).toBe("function");
  });
});
