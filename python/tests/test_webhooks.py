import json

import pytest

from verbex_speech import WebhookVerificationError, parse_webhook, verify_webhook
from verbex_speech.listen.webhook import signing_key

from .conftest import fixture

VECTORS = fixture("webhook-vectors.json")


def test_signing_key_is_derived_from_the_api_key():
    assert signing_key(VECTORS["api_key"]) == VECTORS["signing_key"]


@pytest.mark.parametrize("case", VECTORS["cases"], ids=[c["name"] for c in VECTORS["cases"]])
def test_vectors(case):
    assert verify_webhook(VECTORS["api_key"], case["body_utf8"].encode(), case["timestamp"], case["signature"],
                          now=case["now"]) is case["valid"]


def test_parse_reads_the_headers_case_insensitively_and_returns_the_job():
    case = VECTORS["cases"][0]
    job = parse_webhook(VECTORS["api_key"], case["body_utf8"].encode(),
                        {"x-verbex-timestamp": case["timestamp"], "X-VERBEX-SIGNATURE": case["signature"]},
                        now=case["now"])
    assert job.job_id == json.loads(case["body_utf8"])["job_id"]
    assert job.status == "completed"
    assert job.segments[0].text == "こんにちは。"


def test_parse_refuses_a_bad_signature_and_missing_headers():
    case = VECTORS["cases"][0]
    with pytest.raises(WebhookVerificationError):
        parse_webhook(VECTORS["api_key"], b"{}", {"X-Verbex-Timestamp": case["timestamp"],
                                                   "X-Verbex-Signature": case["signature"]}, now=case["now"])
    with pytest.raises(WebhookVerificationError):
        parse_webhook(VECTORS["api_key"], case["body_utf8"].encode(), {}, now=case["now"])
