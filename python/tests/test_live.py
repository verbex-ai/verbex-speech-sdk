"""Against a real API, only when VERBEX_API_KEY and VERBEX_BASE_URL are set. Never run against production in CI."""
import os
import pathlib
import time
import wave

import pytest

from verbex_speech import VerbexSpeech

pytestmark = [pytest.mark.live, pytest.mark.skipif(
    not (os.environ.get("VERBEX_API_KEY") and os.environ.get("VERBEX_BASE_URL")),
    reason="needs VERBEX_API_KEY and VERBEX_BASE_URL")]

SAMPLE = pathlib.Path(__file__).resolve().parents[3] / "documents/stt-engine-documents/sample-ja-16k-mono.wav"


@pytest.fixture(scope="module")
def client():
    with VerbexSpeech() as c:
        yield c


def test_models_and_voices(client):
    assert any(m.serves("async") for m in client.listen.models())
    assert client.speak.voices()


def test_transcribe_the_sample(client):
    job = client.listen.transcribe(SAMPLE, model="verbex_jp_stt_x1", language="ja", wait_timeout=300)
    assert job.completed and job.text


def test_live_session_at_real_time(client):
    with wave.open(str(SAMPLE)) as w:
        pcm = w.readframes(w.getnframes())
    with client.listen.stream(model="verbex_jp_stt_x1", language="ja") as session:
        for i in range(0, len(pcm), 3200):
            session.send(pcm[i:i + 3200])
            time.sleep(0.1)
        session.finish()
        events = list(session)
    assert events[-1].type == "done" and any(e.type == "segment" and e.text for e in events)


def test_speak_one_sentence(client):
    voice = client.speak.voices()[0]
    audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="テスト", format="wav")
    assert len(audio.data) > 44
