"""A real WebSocket server on an ephemeral port that plays a conformance script."""
import asyncio
import json
import threading

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed


class ScriptServer:
    """Plays `script`: server steps are sent, client steps are awaited and recorded."""

    def __init__(self, script, path_prefix="/v1/"):
        self.script = script
        self.received = []
        self.request_path = None
        self.request_headers = None
        self._loop = asyncio.new_event_loop()
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    async def _handler(self, socket):
        self.request_path = socket.request.path
        self.request_headers = socket.request.headers
        try:
            for step in self.script:
                if "server" in step:
                    frame = step["server"]
                    if "binary_bytes" in frame:
                        await socket.send(bytes(frame["binary_bytes"]))
                    else:
                        # speak-stream.json wraps a text frame as {"text": {...}}; listen-stream.json does not,
                        # and a segment's own "text" is a string.
                        body = frame["text"] if isinstance(frame.get("text"), dict) else frame
                        await socket.send(json.dumps(body, ensure_ascii=False))
                elif "client" in step:
                    message = await socket.recv()
                    self.received.append(message)
                    expected = step["client"]
                    while "binary_bytes" in expected and isinstance(message, bytes) and \
                            sum(len(m) for m in self.received if isinstance(m, bytes)) < expected["binary_bytes"]:
                        message = await socket.recv()
                        self.received.append(message)
                elif "close" in step:
                    code = step["close"]
                    if code == 1006:
                        socket.transport.abort()
                    else:
                        await socket.close(code)
                    return
            await socket.wait_closed()
        except ConnectionClosed:
            pass

    def _run(self):
        asyncio.set_event_loop(self._loop)

        async def main():
            async with serve(self._handler, "127.0.0.1", 0) as server:
                self.port = server.sockets[0].getsockname()[1]
                self._stop = self._loop.create_future()
                self._ready.set()
                await self._stop

        self._loop.run_until_complete(main())

    def __enter__(self):
        self._thread.start()
        self._ready.wait(5)
        return self

    def __exit__(self, *exc):
        self._loop.call_soon_threadsafe(lambda: self._stop.done() or self._stop.set_result(None))
        self._thread.join(5)

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.port}"

    def texts(self):
        return [json.loads(m) for m in self.received if isinstance(m, str)]
