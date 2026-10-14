from verbex_speech.listen.jobs import Job, SubmitReceipt, UploadStarted
from verbex_speech.listen.models import ListenModel
from verbex_speech.speak.voices import Voice

from .conftest import fixture

R = fixture("responses.json")


def test_models():
    model = ListenModel.from_dict(R["models"]["items"][0])
    assert model.model == "verbex_jp_stt_x1" and model.language == "ja"
    assert [m.mode for m in model.modes] == ["async", "stream"]
    assert model.modes[0].features[0].param_name == "hotwords"
    assert model.modes[0].features[0].max_items == 20
    assert model.modes[0].features[0].description is None
    assert model.serves("stream") and not model.serves("other")


def test_voices():
    voices = [Voice.from_dict(v) for v in R["voices"]["items"]]
    assert voices[0].code == "maria_2" and voices[0].sample_url == "https://cdn.example/maria.wav"
    assert voices[1].code == "Aoi" and voices[1].display_name is None and voices[1].gender is None


def test_receipts():
    assert SubmitReceipt.from_dict(R["receipt"]).queue_position == 1
    assert SubmitReceipt.from_dict(R["receipt_without_position"]).queue_position is None


def test_jobs():
    done = Job.from_dict(R["job_completed"])
    assert done.completed and done.finished
    assert done.audio_duration_sec == 19.2 and done.metadata == {"call_id": "c-42"}
    assert [s.index for s in done.segments] == [0, 1] and done.segments[1].end_sec == 19.68
    failed = Job.from_dict(R["job_failed"])
    assert failed.finished and not failed.completed
    assert failed.error.code == "invalid_audio" and failed.language is None and failed.text is None
    assert not Job.from_dict(R["job_processing"]).finished


def test_upload_url_is_never_in_repr():
    started = UploadStarted.from_dict(R["upload_started"])
    assert "secret" not in repr(started) and "X-Goog-Signature" not in repr(started)
    assert started.upload_url.endswith("secret")
