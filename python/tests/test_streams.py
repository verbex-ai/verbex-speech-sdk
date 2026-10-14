import pytest

from verbex_speech import SpeakError, StreamError, VerbexSpeech

from .conftest import KEY, fixture
from .socket_server import ScriptServer

LISTEN = fixture("listen-stream.json")
SPEAK = fixture("speak-stream.json")


def client_for(server):
    return VerbexSpeech(api_key=KEY, base_url=server.base_url)


def test_a_live_session_end_to_end():
    case = LISTEN["normal"]
    with ScriptServer(case["script"]) as server:
        with client_for(server).listen.stream(model="verbex_jp_stt_x1", language="ja",
                                              hotwords=["かいみょう"], tags=["t1"]) as session:
            assert session.session_id == "314ba9e8-d962-4a04-88b9-06bc53dd8036"
            session.send(bytes(3200))
            session.configure([])
            events = []
            for event in session:
                events.append(event)
                if event.type == "segment" and event.index == 0:
                    session.finish()
        assert [e.type for e in events] == ["configured", "segment", "segment", "done"]
        assert events[-1].total_segments == 2
        assert events[2].text == "おりかえしごれんらくいたします。"
        assert server.request_headers["Authorization"] == "Token " + KEY
        assert server.request_path.startswith("/v1/listen/stream?")
        assert "hotwords=%E3%81%8B%E3%81%84%E3%81%BF%E3%82%87%E3%81%86" in server.request_path
        assert "tag=t1" in server.request_path
        assert server.texts() == [{"type": "configure", "hotwords": []}, {"type": "eof"}]


@pytest.mark.parametrize("name", ["refused", "failed_mid_session", "closed_without_done"])
def test_a_session_ended_by_the_server(name):
    case = LISTEN[name]
    expect = case["expect"]
    segments = []
    with ScriptServer(case["script"]) as server:
        with pytest.raises(StreamError) as raised:
            with client_for(server).listen.stream(model="m", language="ja") as session:
                for event in session:
                    if event.type == "segment":
                        segments.append(event)
    assert raised.value.code == expect["code"]
    assert raised.value.close_code == expect["close_code"]
    assert raised.value.retryable is expect["retryable"]
    assert len(segments) == expect.get("segments_before_error", 0)


def test_large_audio_is_split_into_frames_of_at_most_64_kib():
    script = [{"server": {"type": "ready", "session_id": "s"}}, {"client": {"binary_bytes": 100_000}},
              {"server": {"type": "done", "total_segments": 0}}, {"close": 1000}]
    with ScriptServer(script) as server:
        with client_for(server).listen.stream(model="m", language="ja") as session:
            session.send(bytes(100_000))
            list(session)
    assert [len(m) for m in server.received] == [65536, 100_000 - 65536]


def test_leaving_the_block_without_finish_sends_eof():
    script = [{"server": {"type": "ready", "session_id": "s"}}, {"client": {"text": {"type": "eof"}}},
              {"server": {"type": "done", "total_segments": 0}}, {"close": 1000}]
    with ScriptServer(script) as server:
        with client_for(server).listen.stream(model="m", language="ja"):
            pass
    assert server.texts() == [{"type": "eof"}]


def test_a_speak_session_survives_an_utterance_error():
    expect = SPEAK["expect"]
    with ScriptServer(SPEAK["script"]) as server:
        with client_for(server).speak.stream() as session:
            first = session.say(model="verbex_v1_bn", voice="maria_2", text="one", request_id="turn-1")
            assert len(b"".join(first)) == expect["turn-1"]["audio_bytes"]
            assert first.done.audio_seconds == expect["turn-1"]["audio_seconds"]
            with pytest.raises(SpeakError) as raised:
                b"".join(session.say(model="verbex_v1_bn", voice="maria_2", text="", request_id="turn-2"))
            assert raised.value.code == "validation_failed" and raised.value.request_id == "turn-2"
            assert [{"name": f.name, "reason": f.reason} for f in raised.value.fields] == expect["turn-2"]["fields"]
            third = session.say(model="verbex_v1_bn", voice="maria_2", text="three", request_id="turn-3")
            assert len(third.read()) == expect["turn-3"]["audio_bytes"]
    assert [t["request_id"] for t in server.texts()] == ["turn-1", "turn-2", "turn-3"]


def test_one_utterance_at_a_time():
    script = [{"client": {"text": {}}}, {"server": {"binary_bytes": 10}}]
    with ScriptServer(script) as server:
        with client_for(server).speak.stream() as session:
            session.say(model="m", voice="v", text="a")
            with pytest.raises(RuntimeError):
                session.say(model="m", voice="v", text="b")
