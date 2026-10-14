import httpx
import pytest

from verbex_speech import AsyncVerbexSpeech, StreamError

from .conftest import BASE, KEY, fixture
from .socket_server import ScriptServer

R = fixture("responses.json")
LISTEN = fixture("listen-stream.json")


@pytest.mark.asyncio
async def test_async_models_and_transcribe():
    def handler(request):
        if request.url.path == "/v1/listen/models":
            return httpx.Response(200, json=R["models"])
        if request.method == "POST":
            return httpx.Response(202, json=R["receipt"])
        return httpx.Response(200, json=R["job_completed"])

    async with AsyncVerbexSpeech(api_key=KEY, base_url=BASE, transport=httpx.MockTransport(handler)) as client:
        async def no_sleep(seconds):
            return None
        client._sleep = no_sleep
        assert (await client.listen.models())[0].model == "verbex_jp_stt_x1"
        job = await client.listen.transcribe(b"RIFF" + bytes(10), model="m", language="ja")
        assert job.completed


@pytest.mark.asyncio
async def test_async_live_session():
    with ScriptServer(LISTEN["normal"]["script"]) as server:
        client = AsyncVerbexSpeech(api_key=KEY, base_url=server.base_url)
        async with client.listen.stream(model="verbex_jp_stt_x1", language="ja", hotwords=["かいみょう"]) as session:
            await session.send(bytes(3200))
            await session.configure([])
            types = []
            async for event in session:
                types.append(event.type)
                if event.type == "segment" and event.index == 0:
                    await session.finish()
        assert types == ["configured", "segment", "segment", "done"]
        await client.aclose()


@pytest.mark.asyncio
async def test_async_refused_session():
    with ScriptServer(LISTEN["refused"]["script"]) as server:
        client = AsyncVerbexSpeech(api_key=KEY, base_url=server.base_url)
        with pytest.raises(StreamError) as raised:
            async with client.listen.stream(model="m", language="ja") as session:
                async for _ in session:
                    pass
        assert raised.value.code == "insufficient_credit" and raised.value.close_code == 1008
        await client.aclose()
