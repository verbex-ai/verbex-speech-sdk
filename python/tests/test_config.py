import httpx
import pytest

from verbex_speech import ConfigurationError, VerbexSpeech, __version__

from .conftest import BASE, KEY


def test_key_and_base_url_are_required(monkeypatch):
    monkeypatch.delenv("VERBEX_API_KEY", raising=False)
    monkeypatch.delenv("VERBEX_BASE_URL", raising=False)
    with pytest.raises(ConfigurationError):
        VerbexSpeech(base_url=BASE)
    with pytest.raises(ConfigurationError):
        VerbexSpeech(api_key=KEY)
    with pytest.raises(ConfigurationError):
        VerbexSpeech(api_key="not-a-key", base_url=BASE)
    with pytest.raises(ConfigurationError):
        VerbexSpeech(api_key=KEY, base_url="ftp://x")


def test_environment_fallbacks(monkeypatch):
    monkeypatch.setenv("VERBEX_API_KEY", KEY)
    monkeypatch.setenv("VERBEX_BASE_URL", BASE + "/")
    client = VerbexSpeech()
    assert client.base_url == BASE
    assert KEY not in repr(client)


def test_every_request_carries_the_key_agent_and_a_trace_id(make_client):
    client, recorder = make_client(lambda request: httpx.Response(200, json={"items": []}))
    client.speak.voices()
    request = recorder.requests[0]
    assert str(request.url) == BASE + "/v1/speak/voices"
    assert request.headers["authorization"] == "Token " + KEY
    assert request.headers["user-agent"].startswith("verbex-speech-python/" + __version__)
    assert len(request.headers["x-trace-id"]) == 36
