#!/usr/bin/env bash
# Verbex Speech sample with curl: ./sample.sh call.wav   (needs curl and jq)
# Reads VERBEX_API_KEY (and VERBEX_BASE_URL, if set) from the environment.
# A file sent this way is at most 1 MiB; the SDKs handle larger files.
set -euo pipefail

FILE=${1:?usage: ./sample.sh <audio.wav|audio.mp3>}
API=${VERBEX_BASE_URL:-https://jp-speech-api.verbex.ai}
API=${API%/}
KEY=${VERBEX_API_KEY:?set VERBEX_API_KEY}
MODEL=verbex_jp_stt_x1
LANGUAGE=ja
AUTH=(-H "Authorization: Token $KEY")

echo "API: $API"
curl -fsS "${AUTH[@]}" "$API/v1/listen/models" | jq -r '.items[] | "stt \(.model) \(.language)"'
VOICES=$(curl -fsS "${AUTH[@]}" "$API/v1/speak/voices")
echo "$VOICES" | jq -r '.items[] | "tts \(.model) \(.code) \(.language)"'

JOB_ID=$(curl -fsS "${AUTH[@]}" -X POST "$API/v1/listen" \
  -F "file=@$FILE" -F "model=$MODEL" -F "language=$LANGUAGE" | jq -r .job_id)
echo "job $JOB_ID"
while :; do
  JOB=$(curl -fsS "${AUTH[@]}" "$API/v1/listen/$JOB_ID")
  STATUS=$(echo "$JOB" | jq -r .status)
  case "$STATUS" in
    queued|processing) sleep 2 ;;
    *) break ;;
  esac
done
echo "job $STATUS"
echo "$JOB" | jq -r '.text // empty'

VOICE=$(echo "$VOICES" | jq -c --arg l "$LANGUAGE" '[.items[] | select(.language == $l)][0]')
[ "$VOICE" != "null" ] || { echo "no voice for $LANGUAGE" >&2; exit 1; }
jq -n --argjson v "$VOICE" '{model: $v.model, voice: $v.code, text: "こんにちは", format: "wav"}' |
  curl -fsS "${AUTH[@]}" -X POST "$API/v1/speak" -H "Content-Type: application/json" --data-binary @- -o hello.wav
echo "wrote hello.wav"
