"""Every speak carries an Idempotency-Key, the same one across its retries (spec section 5.2)."""
import json
import re
import uuid

import httpx
import pytest

from verbex_speech import AsyncVerbexSpeech, BadRequestError, ValidationError

from .conftest import BASE, KEY

UUID4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
PCM = bytes(3200)


def error(status, code):
    return httpx.Response(status, json={"error": {"code": code, "message": "m", "request_id": "r"}})


def spoken(**headers):
    return httpx.Response(200, headers={"X-Request-Id": "r", **headers}, content=PCM)


def test_each_synthesize_sends_a_new_uuid_key(make_client):
    client, recorder = make_client(lambda request: spoken())

    client.speak.synthesize(model="m", voice="v", text="t")
    client.speak.synthesize(model="m", voice="v", text="t")

    first, second = (r.headers["idempotency-key"] for r in recorder.requests)
    assert UUID4.match(first) and UUID4.match(second)
    assert first != second, "one key per synthesize call"


def test_the_same_key_goes_with_every_retry(make_client):
    answers = [error(503, "service_unavailable"), httpx.ConnectError("reset"), spoken()]

    def handler(request):
        answer = answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer

    client, recorder = make_client(handler)
    assert client.speak.synthesize(model="m", voice="v", text="t").data == PCM

    assert len(recorder.requests) == 3
    assert len({r.headers["idempotency-key"] for r in recorder.requests}) == 1


def test_a_speak_whose_first_request_still_speaks_is_retried(make_client):
    answers = [error(409, "request_in_progress"), spoken()]
    client, recorder = make_client(lambda request: answers.pop(0))
    assert client.speak.synthesize(model="m", voice="v", text="t").data == PCM
    assert len(recorder.requests) == 2


def test_the_callers_own_key_is_sent_as_given_and_not_in_the_body(make_client):
    client, recorder = make_client(lambda request: spoken())
    client.speak.synthesize(model="m", voice="v", text="t", idempotency_key="turn-42:utt-7")
    (request,) = recorder.requests
    assert request.headers["idempotency-key"] == "turn-42:utt-7"
    assert "turn-42" not in json.dumps(json.loads(request.content))


def test_synthesize_stream_sends_the_key_too(make_client):
    client, recorder = make_client(lambda request: spoken())
    with client.speak.synthesize_stream(model="m", voice="v", text="t", idempotency_key="k-1") as stream:
        b"".join(stream)
    assert recorder.requests[0].headers["idempotency-key"] == "k-1"


@pytest.mark.parametrize("bad", ["", "k" * 256, "has space", "キー"])
def test_a_bad_key_is_refused_before_anything_is_sent(make_client, bad):
    client, recorder = make_client(lambda request: spoken())
    with pytest.raises(ValidationError) as raised:
        client.speak.synthesize(model="m", voice="v", text="t", idempotency_key=bad)
    assert raised.value.code == "validation_failed"
    assert recorder.requests == []


def test_replayed_audio_says_so(make_client):
    client, _ = make_client(lambda request: spoken(**{"Idempotent-Replayed": "true"}))
    assert client.speak.synthesize(model="m", voice="v", text="t").replayed is True
    with client.speak.synthesize_stream(model="m", voice="v", text="t") as stream:
        assert stream.replayed is True

    client, _ = make_client(lambda request: spoken())
    assert client.speak.synthesize(model="m", voice="v", text="t").replayed is False


@pytest.mark.parametrize("code", ["idempotency_key_reused", "idempotency_replays_exhausted"])
def test_a_refused_key_is_a_bad_request_and_not_retried(make_client, code):
    client, recorder = make_client(lambda request: error(422, code))
    with pytest.raises(BadRequestError) as raised:
        client.speak.synthesize(model="m", voice="v", text="t", idempotency_key="k-1")
    assert raised.value.status == 422 and raised.value.code == code
    assert len(recorder.requests) == 1


@pytest.mark.asyncio
async def test_async_synthesize_keeps_one_key_across_retries():
    answers = [error(503, "service_unavailable"), spoken(**{"Idempotent-Replayed": "true"})]
    keys = []

    def handler(request):
        keys.append(request.headers["idempotency-key"])
        return answers.pop(0)

    async with AsyncVerbexSpeech(api_key=KEY, base_url=BASE, transport=httpx.MockTransport(handler)) as client:
        async def no_sleep(seconds):
            return None
        client._sleep = no_sleep
        audio = await client.speak.synthesize(model="m", voice="v", text="t")

    assert audio.replayed is True and audio.data == PCM
    assert len(keys) == 2 and keys[0] == keys[1] and uuid.UUID(keys[0]).version == 4
