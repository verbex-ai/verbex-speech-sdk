import json

import httpx
import pytest

from verbex_speech import UploadError, ValidationError, WaitTimeoutError

from .conftest import BASE, KEY, fixture

R = fixture("responses.json")
STORAGE = "https://storage.example"
RECEIPT = {"job_id": "9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", "status": "queued", "queue_position": 1}


def multipart_fields(request):
    """The multipart parts of a request, as (name, filename, content) tuples."""
    boundary = request.headers["content-type"].split("boundary=")[1].encode()
    parts = []
    for raw in request.content.split(b"--" + boundary)[1:-1]:
        head, _, content = raw.strip(b"\r\n").partition(b"\r\n\r\n")
        disposition = head.decode().split("\r\n")[0]
        name = disposition.split('name="')[1].split('"')[0]
        filename = disposition.split('filename="')[1].split('"')[0] if "filename=" in disposition else None
        parts.append((name, filename, content))
    return parts


def test_a_small_file_goes_in_the_request(make_client, tmp_path):
    path = tmp_path / "call.wav"
    path.write_bytes(b"RIFF" + bytes(1024 * 1024 - 4))
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))

    receipt = client.listen.submit(path, model="verbex_jp_stt_x1", language="ja", hotwords=["とうきょう", "おおさか"],
                                   metadata={"call_id": "c-42"}, webhook_url="https://example.com/hook",
                                   tags=["campaign-7", "env:prod"])

    assert receipt.job_id == RECEIPT["job_id"] and receipt.queue_position == 1
    (request,) = recorder.requests
    assert request.method == "POST" and request.url.path == "/v1/listen"
    assert request.url.params.get_list("tag") == ["campaign-7", "env:prod"]
    parts = multipart_fields(request)
    names = [p[0] for p in parts]
    assert names.count("hotwords") == 2
    assert dict((p[0], p[2]) for p in parts if p[0] != "hotwords") | {} == {
        "file": path.read_bytes(), "model": b"verbex_jp_stt_x1", "language": b"ja",
        "metadata": b'{"call_id": "c-42"}', "webhook_url": b"https://example.com/hook"}
    assert [p[2].decode() for p in parts if p[0] == "hotwords"] == ["とうきょう", "おおさか"]
    assert [p[1] for p in parts if p[0] == "file"] == ["call.wav"]


def test_a_large_file_goes_through_storage_without_the_key(make_client):
    audio = b"RIFF" + bytes(2_000_000 - 4)
    started = dict(R["upload_started"], upload_url=STORAGE + "/b/audio?X-Goog-Signature=secret")

    def handler(request):
        if request.url.path == "/v1/listen/uploads":
            return httpx.Response(201, json=started)
        if request.url.host == "storage.example":
            return httpx.Response(200)
        return httpx.Response(202, json=RECEIPT)

    client, recorder = make_client(handler)
    receipt = client.listen.submit(audio, model="verbex_jp_stt_x1", language="ja", filename="big.wav",
                                   hotwords=["かいみょう"], metadata={"call_id": "c-42"})

    assert receipt.job_id == RECEIPT["job_id"]
    start, put, submit = recorder.requests
    assert json.loads(start.content) == {"size": 2_000_000, "filename": "big.wav"}
    assert put.method == "PUT" and str(put.url) == started["upload_url"]
    assert put.content == audio
    assert put.headers["x-goog-if-generation-match"] == "0"
    assert put.headers["x-goog-content-length-range"] == "2000000,2000000"
    assert "authorization" not in put.headers and "x-trace-id" not in put.headers
    assert submit.headers["content-type"] == "application/json"
    assert json.loads(submit.content) == {"upload_id": started["upload_id"], "model": "verbex_jp_stt_x1",
                                          "language": "ja", "hotwords": ["かいみょう"],
                                          "metadata": {"call_id": "c-42"}}


def test_a_failed_put_is_an_upload_error_without_the_url_and_is_not_retried(make_client):
    started = dict(R["upload_started"], upload_url=STORAGE + "/b/audio?X-Goog-Signature=secret")

    def handler(request):
        if request.url.path == "/v1/listen/uploads":
            return httpx.Response(201, json=started)
        return httpx.Response(412)

    client, recorder = make_client(handler)
    with pytest.raises(UploadError) as raised:
        client.listen.submit(bytes(2_000_000), model="m", language="ja")
    assert raised.value.status == 412
    assert "secret" not in str(raised.value) and "secret" not in repr(raised.value)
    assert [r.method for r in recorder.requests] == ["POST", "PUT"]


def test_an_incomplete_upload_is_submitted_again(make_client):
    submits = [httpx.Response(409, json={"error": {"code": "upload_incomplete", "message": "m", "request_id": "r"}}),
               httpx.Response(202, json=RECEIPT)]

    def handler(request):
        if request.url.path == "/v1/listen/uploads":
            return httpx.Response(201, json=R["upload_started"])
        if request.method == "PUT":
            return httpx.Response(200)
        return submits.pop(0)

    client, recorder = make_client(handler)
    assert client.listen.submit(bytes(2_000_000), model="m", language="ja").job_id == RECEIPT["job_id"]
    assert [r.method for r in recorder.requests] == ["POST", "PUT", "POST", "POST"]


def test_over_100_mib_is_refused_locally(make_client):
    client, recorder = make_client(lambda request: httpx.Response(500))

    class Big:
        def read(self, n=-1):
            return b""

    with pytest.raises(ValidationError):
        client.listen.submit(Big(), size=104_857_601, model="m", language="ja")
    assert recorder.requests == []


def test_by_url(make_client):
    client, recorder = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    client.listen.submit(url="https://audio.example/a.wav?sig=1", model="m", language="ja", hotwords=["a"])
    (request,) = recorder.requests
    assert json.loads(request.content) == {"url": "https://audio.example/a.wav?sig=1", "model": "m",
                                           "language": "ja", "hotwords": ["a"]}


def test_exactly_one_of_source_and_url(make_client):
    client, _ = make_client(lambda request: httpx.Response(202, json=RECEIPT))
    with pytest.raises(ValidationError):
        client.listen.submit(model="m", language="ja")
    with pytest.raises(ValidationError):
        client.listen.submit(b"x", url="https://a.example/a.wav", model="m", language="ja")


def test_wait_polls_until_finished(make_client):
    states = [R["job_processing"], R["job_processing"], R["job_completed"]]
    client, recorder = make_client(lambda request: httpx.Response(200, json=states.pop(0)))
    slept = []
    client._sleep = slept.append
    job = client.listen.wait(RECEIPT["job_id"], poll_interval=0.1)
    assert job.completed and len(recorder.requests) == 3
    assert slept == [0.5, 0.5]  # never below 0.5 s


def test_wait_returns_a_failed_job_without_raising(make_client):
    client, _ = make_client(lambda request: httpx.Response(200, json=R["job_failed"]))
    job = client.listen.wait(R["job_failed"]["job_id"])
    assert job.status == "failed" and job.error.code == "invalid_audio"


def test_wait_timeout_carries_the_last_job(make_client):
    client, _ = make_client(lambda request: httpx.Response(200, json=R["job_processing"]))
    clock = iter(range(0, 1000, 5))
    client._monotonic = lambda: next(clock)
    with pytest.raises(WaitTimeoutError) as raised:
        client.listen.wait(RECEIPT["job_id"], timeout=12)
    assert raised.value.job.status == "processing"


def test_transcribe_submits_and_waits(make_client):
    def handler(request):
        if request.method == "POST":
            return httpx.Response(202, json=RECEIPT)
        return httpx.Response(200, json=R["job_completed"])

    client, _ = make_client(handler)
    job = client.listen.transcribe(b"RIFF" + bytes(10), model="m", language="ja")
    assert job.text == R["job_completed"]["text"]


def test_models(make_client):
    client, _ = make_client(lambda request: httpx.Response(200, json=R["models"]))
    (model,) = client.listen.models()
    assert model.model == "verbex_jp_stt_x1"
