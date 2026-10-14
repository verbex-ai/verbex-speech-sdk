import { describe, expect, it } from "vitest";
import { verifyWebhook, parseWebhook, WebhookVerificationError } from "../../src/index.js";
import { fixture } from "../helpers.js";

const vectors = fixture("webhook-vectors.json");

describe("verifyWebhook (conformance/webhook-vectors.json)", () => {
  for (const c of vectors.cases) {
    it(`${c.name} -> ${c.valid}`, () => {
      const ok = verifyWebhook({
        apiKey: vectors.api_key,
        rawBody: c.body_utf8,
        timestamp: c.timestamp,
        signature: c.signature,
        now: c.now,
      });
      expect(ok).toBe(c.valid);
    });

    it(`${c.name} -> ${c.valid} (raw body as bytes)`, () => {
      expect(
        verifyWebhook({
          apiKey: vectors.api_key,
          rawBody: Buffer.from(c.body_utf8, "utf8"),
          timestamp: c.timestamp,
          signature: c.signature,
          now: c.now,
        }),
      ).toBe(c.valid);
    });
  }

  const valid = vectors.cases.find((c: any) => c.name === "valid");

  it("finds the headers in a header map case-insensitively", () => {
    expect(
      verifyWebhook({
        apiKey: vectors.api_key,
        rawBody: valid.body_utf8,
        headers: { "x-verbex-timestamp": valid.timestamp, "X-VERBEX-SIGNATURE": valid.signature },
        now: valid.now,
      }),
    ).toBe(true);
    const h = new Headers({ "X-Verbex-Timestamp": valid.timestamp, "X-Verbex-Signature": valid.signature });
    expect(verifyWebhook({ apiKey: vectors.api_key, rawBody: valid.body_utf8, headers: h, now: valid.now })).toBe(true);
  });

  it("honours a custom tolerance", () => {
    expect(
      verifyWebhook({ apiKey: vectors.api_key, rawBody: valid.body_utf8, timestamp: valid.timestamp, signature: valid.signature, now: valid.now, toleranceSec: 5 }),
    ).toBe(false);
  });

  it("never throws for bad input", () => {
    const bad: any[] = [
      {},
      { apiKey: vectors.api_key },
      { apiKey: null, rawBody: 5, timestamp: {}, signature: [] },
      { apiKey: vectors.api_key, rawBody: valid.body_utf8, headers: { "x-verbex-timestamp": ["a", "b"] } },
      { apiKey: vectors.api_key, rawBody: valid.body_utf8, timestamp: "1e3", signature: valid.signature },
      undefined,
    ];
    for (const b of bad) expect(verifyWebhook(b)).toBe(false);
  });

  it("defaults now to the current time", () => {
    expect(verifyWebhook({ apiKey: vectors.api_key, rawBody: valid.body_utf8, timestamp: valid.timestamp, signature: valid.signature })).toBe(
      Math.abs(Date.now() / 1000 - Number(valid.timestamp)) <= 300,
    );
  });
});

describe("parseWebhook", () => {
  const valid = vectors.cases.find((c: any) => c.name === "valid");

  it("returns the job when the signature verifies", () => {
    const job = parseWebhook({
      apiKey: vectors.api_key,
      rawBody: valid.body_utf8,
      headers: { "X-Verbex-Timestamp": valid.timestamp, "X-Verbex-Signature": valid.signature },
      now: valid.now,
    });
    expect(job.jobId).toBe("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b");
    expect(job.status).toBe("completed");
    expect(job.completed).toBe(true);
    expect(job.segments[0]).toEqual({ index: 0, startSec: 0.1, endSec: 1.2, text: "こんにちは。" });
    expect(job.metadata).toEqual({ call_id: "c-42" });
  });

  it("raises WebhookVerificationError for a bad signature", () => {
    const tampered = vectors.cases.find((c: any) => c.name === "body changed by one byte");
    expect(() =>
      parseWebhook({ apiKey: vectors.api_key, rawBody: tampered.body_utf8, timestamp: tampered.timestamp, signature: tampered.signature, now: tampered.now }),
    ).toThrow(WebhookVerificationError);
  });

  it("does not leak the api key in the verification error", () => {
    try {
      parseWebhook({ apiKey: vectors.api_key, rawBody: "{}", timestamp: "1", signature: "v1=00" });
      expect.unreachable();
    } catch (e: any) {
      expect(String(e)).not.toContain(vectors.api_key);
      expect(e.message).not.toContain(vectors.api_key);
    }
  });
});
