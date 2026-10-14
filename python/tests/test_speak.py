import json

import httpx
import pytest

from verbex_speech import SpeakError, ValidationError

from .conftest import fixture


def test_synthesize_sends_settings_top_level_and_returns_the_audio(make_client):
    pcm = bytes(range(256)) * 10
    client, recorder = make_client(lambda request: httpx.Response(
        200, headers={"Content-Type": "audio/wav", "X-Request-Id": "turn-1"}, content=pcm))
    audio = client.speak.synthesize(model="verbex_v1_bn", voice="maria_2", text="hello", format="wav",
                                    request_id="turn-1", settings={"temperature": 0.7}, tags=["t1"])
    assert audio.data == pcm and audio.request_id == "turn-1" and audio.content_type == "audio/wav"
    (request,) = recorder.requests
    assert request.url.path == "/v1/speak"
    assert request.url.params["format"] == "wav" and request.url.params.get_list("tag") == ["t1"]
    assert json.loads(request.content) == {"model": "verbex_v1_bn", "voice": "maria_2", "text": "hello",
                                           "request_id": "turn-1", "temperature": 0.7}


def test_synthesize_stream_yields_chunks(make_client):
    client, _ = make_client(lambda request: httpx.Response(200, headers={"X-Request-Id": "r"}, content=bytes(70000)))
    with client.speak.synthesize_stream(model="m", voice="v", text="t") as stream:
        assert stream.request_id == "r"
        assert sum(len(chunk) for chunk in stream) == 70000


def test_a_setting_cannot_replace_a_field(make_client):
    client, recorder = make_client(lambda request: httpx.Response(200))
    with pytest.raises(ValidationError):
        client.speak.synthesize(model="m", voice="v", text="t", settings={"text": "other"})
    assert recorder.requests == []


def test_voices(make_client):
    client, _ = make_client(lambda request: httpx.Response(200, json=fixture("responses.json")["voices"]))
    assert [v.code for v in client.speak.voices()] == ["maria_2", "Aoi"]


def test_a_body_that_ends_early_is_a_failed_utterance_not_short_audio(make_client):
    class Broken(httpx.SyncByteStream):
        def __iter__(self):
            yield bytes(3200)
            raise httpx.ReadError("reset")

    client, _ = make_client(lambda request: httpx.Response(200, headers={"X-Request-Id": "r"}, stream=Broken()))
    with pytest.raises(SpeakError) as raised:
        client.speak.synthesize(model="m", voice="v", text="t")
    assert raised.value.code == "audio_incomplete" and raised.value.request_id == "r"


def test_local_refusals_are_value_errors_too(make_client):
    client, _ = make_client(lambda request: httpx.Response(200))
    with pytest.raises(ValueError):
        client.speak.synthesize(model="m", voice="v", text="t", format="mp3")
