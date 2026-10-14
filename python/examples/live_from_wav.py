"""Stream a 16 kHz mono 16-bit WAV at real time and print segments as they settle.

python live_from_wav.py call.wav    (convert first: ffmpeg -i in.mp3 -ar 16000 -ac 1 call.wav)
"""
import sys
import threading
import time
import wave

from verbex_speech import VerbexSpeech

CHUNK_SECONDS = 0.1

with wave.open(sys.argv[1]) as w:
    assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2), "16 kHz mono 16-bit only"
    pcm = w.readframes(w.getnframes())

with VerbexSpeech() as client:
    with client.listen.stream(model="verbex_jp_stt_x1", language="ja") as session:
        print("session", session.session_id)

        def feed():
            step = int(16000 * 2 * CHUNK_SECONDS)
            for i in range(0, len(pcm), step):
                session.send(pcm[i:i + step])
                time.sleep(CHUNK_SECONDS)  # at the pace audio is produced
            session.finish()

        threading.Thread(target=feed, daemon=True).start()
        for event in session:
            if event.type == "segment":
                print(f"{event.start_sec:7.2f}  {event.text}")
