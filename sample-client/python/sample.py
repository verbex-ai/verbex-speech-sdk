"""Verbex Speech sample: python sample.py call.wav

Reads VERBEX_API_KEY (and VERBEX_BASE_URL, if set) from the environment.
"""
import sys

from verbex_speech import VerbexSpeech

MODEL = "verbex_jp_stt_x1"
LANGUAGE = "ja"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: python sample.py <audio.wav|audio.mp3>")

    with VerbexSpeech() as client:
        print("API:", client.base_url)

        for m in client.listen.models():
            print("stt", m.model, m.language)
        voices = client.speak.voices()
        for v in voices:
            print("tts", v.model, v.code, v.language)

        job = client.listen.transcribe(sys.argv[1], model=MODEL, language=LANGUAGE)
        print("job", job.status)
        if job.completed:
            print(job.text)
        elif job.error:
            print("error", job.error.code)

        voice = next((v for v in voices if v.language == LANGUAGE), None)
        if voice is None:
            sys.exit(f"no voice for {LANGUAGE}")
        audio = client.speak.synthesize(model=voice.model, voice=voice.code, text="こんにちは", format="wav")
        with open("hello.wav", "wb") as f:
            f.write(audio.data)
        print("wrote hello.wav")


if __name__ == "__main__":
    main()
