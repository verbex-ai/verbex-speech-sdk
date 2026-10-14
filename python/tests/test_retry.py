import json

import httpx
import pytest

from verbex_speech import APIConnectionError, APITimeoutError, RateLimitError, ServerError
from verbex_speech._transport.retry import should_retry, backoff

from .conftest import fixture

CASES = fixture("retry-decisions.json")["cases"]


@pytest.mark.parametrize("case", CASES, ids=[f'{c["call"]}-{c["outcome"]}-{c["code"]}' for c in CASES])
def test_retry_decisions(case):
    outcome = case["outcome"]
    if outcome == "connection_error":
        decided = should_retry(case["call"], error=APIConnectionError("x"))
    elif outcome == "timeout":
        decided = should_retry(case["call"], error=APITimeoutError("x"))
    else:
        decided = should_retry(case["call"], status=outcome, code=case["code"])
    assert decided is case["retry"]


def test_backoff_is_capped_and_honours_retry_after():
    assert 0 <= backoff(0) <= 0.5
    assert 0 <= backoff(10) <= 8
    assert backoff(3, retry_after=17) == 17
    assert backoff(3, retry_after=120) is None  # longer than 60 s: not waited, the error is raised


def body(code):
    return json.dumps({"error": {"code": code, "message": "m", "request_id": "r"}}).encode()


def test_a_get_is_retried_after_retry_after_then_succeeds(make_client):
    answers = [httpx.Response(429, headers={"Retry-After": "2"}, content=body("rate_limited")),
               httpx.Response(200, json={"items": []})]
    client, recorder = make_client(lambda request: answers.pop(0))
    waits = []
    client._sleep = waits.append
    assert client.listen.models() == []
    assert len(recorder.requests) == 2 and waits == [2]
    # A retry is a new attempt of the same request: same trace id, so support sees one request.
    assert recorder.requests[0].headers["x-trace-id"] == recorder.requests[1].headers["x-trace-id"]


def test_retries_stop_at_max_retries(make_client):
    client, recorder = make_client(lambda request: httpx.Response(503, content=body("service_unavailable")),
                                   max_retries=2)
    with pytest.raises(ServerError):
        client.speak.voices()
    assert len(recorder.requests) == 3


def test_a_submit_is_retried_on_504_with_its_key_until_the_retries_run_out(make_client):
    client, recorder = make_client(lambda request: httpx.Response(504, content=body("upstream_timeout")))
    with pytest.raises(ServerError):
        client.listen.submit(b"RIFF" + bytes(100), model="m", language="ja")
    assert len(recorder.requests) == 3
    assert len({r.headers["idempotency-key"] for r in recorder.requests}) == 1


def test_a_submit_is_retried_on_engine_busy(make_client):
    answers = [httpx.Response(503, content=body("engine_busy")),
               httpx.Response(202, json={"job_id": "a" * 32, "status": "queued"})]
    client, recorder = make_client(lambda request: answers.pop(0))
    assert client.listen.submit(b"RIFF" + bytes(100), model="m", language="ja").job_id == "a" * 32
    assert len(recorder.requests) == 2


def test_a_submit_is_retried_after_a_connection_error_with_its_key(make_client):
    def fail(request):
        raise httpx.ReadError("reset")
    client, recorder = make_client(fail)
    with pytest.raises(APIConnectionError):
        client.listen.submit(b"RIFF" + bytes(100), model="m", language="ja")
    assert len(recorder.requests) == 3
    assert len({r.headers["idempotency-key"] for r in recorder.requests}) == 1


def test_concurrency_limit_is_not_retried(make_client):
    client, recorder = make_client(lambda request: httpx.Response(429, content=body("concurrency_limit")))
    with pytest.raises(RateLimitError):
        client.listen.models()
    assert len(recorder.requests) == 1
