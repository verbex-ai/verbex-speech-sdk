import { afterEach, describe, expect, it } from "vitest";
import type { ServerResponse } from "node:http";
import { shouldRetry, backoffDelayMs } from "../../src/transport/retry.js";
import { RateLimitError, ServerError, APITimeoutError, APIConnectionError } from "../../src/index.js";
import { apiError, fakeClock, fixture, json, startHttp, testClient, type RecordedRequest, type TestServer } from "../helpers.js";

const cases = fixture("retry-decisions.json").cases as any[];

let server: TestServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

describe("retry decisions, pure (conformance/retry-decisions.json)", () => {
  for (const c of cases) {
    it(`${c.call} ${c.outcome} ${c.code ?? ""} -> ${c.retry}`, () => {
      expect(shouldRetry(c.call, c.outcome, c.code ?? undefined)).toBe(c.retry);
    });
  }
});

/** Which request path the call under test hits, and how to make the call. */
const calls: Record<string, { path: string; run: (url: string, opts: Record<string, unknown>) => Promise<unknown> }> = {
  get: { path: "/v1/listen/abc", run: (url, o) => testClient(url, o).listen.get("abc") },
  upload_start: {
    path: "/v1/listen/uploads",
    run: (url, o) => testClient(url, o).listen.submit({ source: Buffer.alloc(1_048_577), model: "m", language: "ja" }),
  },
  speak: { path: "/v1/speak", run: (url, o) => testClient(url, o).speak.synthesize({ model: "m", voice: "v", text: "hi", timeoutMs: 300 }) },
  submit: {
    path: "/v1/listen",
    run: (url, o) => testClient(url, o).listen.submit({ source: Buffer.from("RIFF"), model: "m", language: "ja", timeoutMs: 300 }),
  },
};

function respond(outcome: number | string, code: string | null, req: RecordedRequest, res: ServerResponse): void {
  if (outcome === "connection_error") {
    res.socket?.destroy();
    return;
  }
  if (outcome === "timeout") return; // never answer
  apiError(res, outcome as number, code ?? "x");
}

describe("retry decisions, end to end against a real server", () => {
  for (const c of cases) {
    it(`${c.call} ${c.outcome} ${c.code ?? ""}: ${c.retry ? "2 attempts" : "1 attempt"}`, async () => {
      const call = calls[c.call];
      server = await startHttp((req, res) => {
        if (req.path === call.path) return respond(c.outcome, c.code, req, res);
        return json(res, 404, {});
      });
      const err = await call.run(server.url, { maxRetries: 1, timeoutMs: 300 }).then(
        () => undefined,
        (e) => e,
      );
      expect(err).toBeDefined();
      if (c.outcome === "timeout") expect(err).toBeInstanceOf(APITimeoutError);
      if (c.outcome === "connection_error") expect(err).toBeInstanceOf(APIConnectionError);
      const hits = server.requests.filter((r) => r.path === call.path).length;
      if (c.outcome === "connection_error" && c.retry) expect(hits).toBeGreaterThanOrEqual(2);
      else expect(hits).toBe(c.retry ? 2 : 1);
    });
  }
});

describe("backoff", () => {
  it("is full jitter from 0.5 s, doubling, capped at 8 s", () => {
    expect([0, 1, 2, 3, 4, 5, 9].map((a) => backoffDelayMs(a, () => 0.999999))).toEqual([500, 1000, 2000, 4000, 8000, 8000, 8000].map((x) => Math.floor(x * 0.999999)));
    expect(backoffDelayMs(3, () => 0)).toBe(0);
  });

  it("waits Retry-After when present", async () => {
    let n = 0;
    server = await startHttp((_req, res) => (n++ === 0 ? apiError(res, 429, "rate_limited", { "Retry-After": "17" }) : json(res, 200, { items: [] })));
    const clock = fakeClock();
    await testClient(server.url, {}, clock).listen.models();
    expect(clock.sleeps).toEqual([17_000]);
  });

  it("raises instead of waiting a Retry-After over 60 s", async () => {
    server = await startHttp((_req, res) => apiError(res, 429, "rate_limited", { "Retry-After": "61" }));
    const clock = fakeClock();
    const err: any = await testClient(server.url, {}, clock).listen.models().catch((e) => e);
    expect(err).toBeInstanceOf(RateLimitError);
    expect(err.retryAfter).toBe(61);
    expect(server.requests.length).toBe(1);
    expect(clock.sleeps).toEqual([]);
  });

  it("uses jittered backoff without Retry-After, up to maxRetries (default 2)", async () => {
    server = await startHttp((_req, res) => apiError(res, 503, "service_unavailable"));
    const clock = fakeClock();
    const err = await testClient(server.url, { _random: () => 0.5 }, clock).listen.models().catch((e) => e);
    expect(err).toBeInstanceOf(ServerError);
    expect(server.requests.length).toBe(3);
    expect(clock.sleeps).toEqual([250, 500]);
  });

  it("maxRetries 0 turns retries off", async () => {
    server = await startHttp((_req, res) => apiError(res, 503, "service_unavailable"));
    await testClient(server.url, { maxRetries: 0 }).listen.models().catch(() => undefined);
    expect(server.requests.length).toBe(1);
  });

  it("each retry carries a fresh trace id", async () => {
    server = await startHttp((_req, res) => apiError(res, 502, "upstream_unavailable"));
    await testClient(server.url, { maxRetries: 1 }).listen.models().catch(() => undefined);
    expect(server.requests[0].headers["x-trace-id"]).not.toBe(server.requests[1].headers["x-trace-id"]);
  });
});
