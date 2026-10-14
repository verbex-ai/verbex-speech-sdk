"""Speak a sentence to a WAV file: python speak_to_wav.py out.wav"""
import sys

from verbex_speech import VerbexSpeech

with VerbexSpeech() as client:
    voice = next(v for v in client.speak.voices() if v.language == "ja")
    audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="お電話ありがとうございます。",
                                    format="wav")
    with open(sys.argv[1], "wb") as f:
        f.write(audio.data)
    print(f"{len(audio.data)} bytes, request {audio.request_id}")
