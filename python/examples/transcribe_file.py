"""Transcribe a WAV or MP3 file of up to 100 MiB: python transcribe_file.py call.wav"""
import sys

from verbex_speech import VerbexSpeech

with VerbexSpeech() as client:  # VERBEX_API_KEY and VERBEX_BASE_URL from the environment
    job = client.listen.transcribe(sys.argv[1], model="verbex_jp_stt_x1", language="ja")
    if not job.completed:
        sys.exit(f"{job.status}: {job.error.code if job.error else ''}")
    for segment in job.segments:
        print(f"{segment.start_sec:7.2f}-{segment.end_sec:7.2f}  {segment.text}")
