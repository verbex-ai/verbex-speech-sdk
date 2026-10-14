"""Every submit carries an Idempotency-Key, the same one across its retries (spec section 4.2)."""
import json
import re
import uuid

import httpx
import pytest

from verbex_speech import AsyncVerbexSpeech, BadRequestError, ValidationError

from .conftest import BASE, KEY, fixture
from .test_listen import multipart_fields

R = fixture("responses.json")
RECEIPT = {"job_id": "9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", "status": "queued", "queue_position": 1}
UUID4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


def busy():
    return httpx.Response(503, json={"error": {"code": "engine_unavailable", "message": "m", "request_id": "r"}})


def test_a_file_submit_sends_a_new_uuid_key(make_client):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))

    client.listen.submit(b"RIFF" + bytes(100), model="m", language="ja")
    client.listen.submit(b"RIFF" + bytes(100), model="m", language="ja")

    first, second = (r.headers["idempotency-key"] for r in recorder.requests)
    assert UUID4.match(first) and UUID4.match(second)
    assert first != second, "one key per submit call"


def test_a_url_submit_sends_a_key(make_client):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    client.listen.submit(url="https://audio.example/a.wav", model="m", language="ja")
    assert UUID4.match(recorder.requests[0].headers["idempotency-key"])


def test_the_same_key_and_the_whole_file_go_with_every_retry(make_client):
    answers = [busy(), httpx.ConnectError("reset"), httpx.Response(202, json=RECEIPT)]

    def handler(request):
        answer = answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer

    client, recorder = make_client(handler)
    audio = b"RIFF" + bytes(5000)
    assert client.listen.submit(audio, model="m", language="ja").job_id == RECEIPT["job_id"]

    assert len(recorder.requests) == 3
    assert len({r.headers["idempotency-key"] for r in recorder.requests}) == 1
    for request in recorder.requests:
        assert [p[2] for p in multipart_fields(request) if p[0] == "file"] == [audio]


def test_a_submit_whose_first_request_still_runs_is_retried(make_client):
    answers = [httpx.Response(409, json={"error": {"code": "request_in_progress", "message": "m", "request_id": "r"}}),
               httpx.Response(202, json=RECEIPT)]
    client, recorder = make_client(lambda request: answers.pop(0))
    assert client.listen.submit(url="https://audio.example/a.wav", model="m", language="ja").job_id == RECEIPT["job_id"]
    assert len(recorder.requests) == 2


def test_the_upload_flow_submits_with_one_key_across_upload_incomplete(make_client):
    submits = [httpx.Response(409, json={"error": {"code": "upload_incomplete", "message": "m", "request_id": "r"}}),
               busy(), httpx.Response(202, json=RECEIPT)]

    def handler(request):
        if request.url.path == "/v1/listen/uploads":
            return httpx.Response(201, json=R["upload_started"])
        if request.method == "PUT":
            return httpx.Response(200)
        return submits.pop(0)

    client, recorder = make_client(handler)
    client.listen.submit(bytes(2_000_000), model="m", language="ja")

    submitted = [r for r in recorder.requests if r.method == "POST" and r.url.path == "/v1/listen"]
    assert len(submitted) == 3
    assert len({r.headers["idempotency-key"] for r in submitted}) == 1
    start, put = recorder.requests[:2]
    assert "idempotency-key" not in start.headers and "idempotency-key" not in put.headers


def test_the_callers_own_key_is_sent_as_given(make_client):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    client.listen.submit(b"RIFF", model="m", language="ja", idempotency_key="order-42:call-7")
    assert recorder.requests[0].headers["idempotency-key"] == "order-42:call-7"


@pytest.mark.parametrize("bad", ["", "k" * 256, "has space", "キー"])
def test_a_bad_key_is_refused_before_anything_is_sent(make_client, bad):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    with pytest.raises(ValidationError) as raised:
        client.listen.submit(b"RIFF", model="m", language="ja", idempotency_key=bad)
    assert raised.value.code == "validation_failed"
    assert recorder.requests == []


def test_a_replayed_receipt_says_so(make_client):
    client, _ = make_client(lambda request: httpx.Response(202, json=RECEIPT, headers={"Idempotent-Replayed": "true"}))
    assert client.listen.submit(b"RIFF", model="m", language="ja").replayed is True

    client, _ = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    assert client.listen.submit(b"RIFF", model="m", language="ja").replayed is False


def test_a_key_reused_for_another_request_is_a_bad_request_and_not_retried(make_client):
    body = {"error": {"code": "idempotency_key_reused", "message": "m", "request_id": "r"}}
    client, recorder = make_client(lambda request: httpx.Response(422, json=body))
    with pytest.raises(BadRequestError) as raised:
        client.listen.submit(b"RIFF", model="m", language="ja", idempotency_key="k-1")
    assert raised.value.status == 422 and raised.value.code == "idempotency_key_reused"
    assert len(recorder.requests) == 1


def test_transcribe_passes_the_key_on(make_client):
    def handler(request):
        if request.method == "POST":
            return httpx.Response(202, json=RECEIPT)
        return httpx.Response(200, json=R["job_completed"])

    client, recorder = make_client(handler)
    client.listen.transcribe(b"RIFF", model="m", language="ja", idempotency_key="k-2")
    assert recorder.requests[0].headers["idempotency-key"] == "k-2"


@pytest.mark.asyncio
async def test_async_submit_keeps_one_key_across_retries():
    answers = [busy(), httpx.Response(202, json=RECEIPT, headers={"Idempotent-Replayed": "true"})]
    keys = []

    def handler(request):
        keys.append(request.headers["idempotency-key"])
        return answers.pop(0)

    async with AsyncVerbexSpeech(api_key=KEY, base_url=BASE, transport=httpx.MockTransport(handler)) as client:
        async def no_sleep(seconds):
            return None
        client._sleep = no_sleep
        receipt = await client.listen.submit(url="https://audio.example/a.wav", model="m", language="ja")

    assert receipt.replayed is True
    assert len(keys) == 2 and keys[0] == keys[1] and uuid.UUID(keys[0]).version == 4


def test_the_key_is_not_in_the_body(make_client):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    client.listen.submit(url="https://audio.example/a.wav", model="m", language="ja", idempotency_key="k-3")
    assert "k-3" not in json.dumps(json.loads(recorder.requests[0].content))
