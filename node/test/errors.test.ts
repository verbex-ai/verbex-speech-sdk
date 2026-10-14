import { afterAll, beforeAll, describe, expect, it } from "vitest";
import * as sdk from "../src/index.js";
import { fixture, startHttp, testClient, type TestServer } from "./helpers.js";

const cases = fixture("errors.json").cases as any[];

let server: TestServer;
let current: any;

beforeAll(async () => {
  server = await startHttp((_req, res) => {
    res.writeHead(current.status, {
      "Content-Type": current.body_text !== undefined ? "text/html" : "application/json",
      ...current.headers,
    });
    res.end(current.body_text !== undefined ? current.body_text : JSON.stringify(current.body));
  });
});
afterAll(() => server.close());

describe("error mapping (conformance/errors.json)", () => {
  for (const c of cases) {
    it(`${c.status} ${c.code} -> ${c.type}`, async () => {
      current = c;
      const err: any = await testClient(server.url, { maxRetries: 0 }).listen.get("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b").catch((e) => e);
      const Expected = (sdk as any)[c.type];
      expect(Expected).toBeTypeOf("function");
      expect(err).toBeInstanceOf(Expected);
      expect(err).toBeInstanceOf(sdk.ApiError);
      expect(err).toBeInstanceOf(sdk.VerbexError);
      expect(err.name).toBe(c.type);
      expect(err.status).toBe(c.status);
      expect(err.code).toBe(c.code);
      expect(err.requestId).toBe(c.request_id);
      expect(err.fields).toEqual(c.fields);
      expect(err.retryAfter ?? null).toBe(c.retry_after);
      expect(typeof err.message).toBe("string");
    });
  }
});

describe("status to type", () => {
  const table: Array<[number, string]> = [
    [400, "BadRequestError"],
    [405, "BadRequestError"],
    [406, "BadRequestError"],
    [409, "BadRequestError"],
    [413, "BadRequestError"],
    [415, "BadRequestError"],
    [401, "AuthenticationError"],
    [402, "PaymentRequiredError"],
    [403, "PermissionDeniedError"],
    [404, "NotFoundError"],
    [429, "RateLimitError"],
    [500, "ServerError"],
    [502, "ServerError"],
    [503, "ServerError"],
    [504, "ServerError"],
  ];
  for (const [status, type] of table) {
    it(`${status} -> ${type}, also for a non-JSON body`, () => {
      const err = sdk.errorFromResponse(status, new Headers(), "<html>proxy</html>");
      expect(err.name).toBe(type);
      expect(err.code).toBe(`http_${status}`);
      expect(err.message).not.toContain("<html>");
    });
  }

  it("parses Retry-After as an HTTP date too", () => {
    const now = Date.parse("Wed, 14 Oct 2026 10:00:00 GMT");
    expect(sdk.parseRetryAfter("Wed, 14 Oct 2026 10:00:30 GMT", now)).toBe(30);
    expect(sdk.parseRetryAfter("soon", now)).toBeUndefined();
  });
});
