import json
import pathlib

import httpx
import pytest

from verbex_speech import VerbexSpeech

CONFORMANCE = pathlib.Path(__file__).resolve().parents[2] / "conformance"
KEY = "vbx_test0000_abcdefghijklmnopqrstuvwxyz0123456789AB"
BASE = "https://api.test"


def fixture(name):
    return json.loads((CONFORMANCE / name).read_text(encoding="utf-8"))


class Recorder:
    """An httpx transport that answers from a handler and remembers every request."""

    def __init__(self, handler):
        self.handler = handler
        self.requests = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        request.read()
        self.requests.append(request)
        return self.handler(request)


@pytest.fixture
def make_client():
    def make(handler, **options):
        recorder = Recorder(handler)
        client = VerbexSpeech(api_key=KEY, base_url=BASE, transport=httpx.MockTransport(recorder), **options)
        client._sleep = lambda seconds: None
        return client, recorder

    return make
