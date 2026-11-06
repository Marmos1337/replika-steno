#!/bin/sh
# Downloads the engines and models that get bundled into the APK.
# Everything ends up in src/mobile/android/app/libs (engine) and .../assets/models (models).
set -eu
cd "$(dirname "$0")/.."
CACHE=models-cache
APP=src/mobile/android/app
ASSETS=$APP/src/main/assets/models
mkdir -p "$CACHE" "$ASSETS/asr" "$ASSETS/asr_en" "$ASSETS/diar" "$ASSETS/llm" "$APP/libs"

get() { # url file
  [ -s "$2" ] && { echo "have $2"; return; }
  echo "get  $1"
  curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}

REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
get $REL/v2.0.1/sherpa-onnx-2.0.1.aar "$APP/libs/sherpa-onnx-2.0.1.aar"
get https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-genai/0.10.35/tasks-genai-0.10.35.aar "$CACHE/tasks-genai-0.10.35.aar"

# ASR: GigaAM v3 (Russian, CTC, with punctuation and casing), int8
get $REL/asr-models/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16.tar.bz2 "$CACHE/gigaam.tar.bz2"
if [ ! -s "$ASSETS/asr/model.int8.onnx" ]; then
  tar -xjf "$CACHE/gigaam.tar.bz2" -C "$CACHE"
  d=$(find "$CACHE" -maxdepth 1 -type d -name "*giga-am-v3*" | head -1)
  cp "$d/model.int8.onnx" "$d/tokens.txt" "$ASSETS/asr/"
fi

# English ASR: NeMo Parakeet TDT-CTC 110M (punctuation + casing), int8; used for phrases GigaAM writes in Latin
get $REL/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2 "$CACHE/parakeet.tar.bz2"
if [ ! -s "$ASSETS/asr_en/model.int8.onnx" ]; then
  mkdir -p "$ASSETS/asr_en"
  tar -xjf "$CACHE/parakeet.tar.bz2" -C "$CACHE"
  d=$(find "$CACHE" -maxdepth 1 -type d -name "*parakeet_tdt_ctc_110m*" | head -1)
  cp "$d/model.int8.onnx" "$d/tokens.txt" "$ASSETS/asr_en/"
fi

# Diarization: Silero VAD (phrases) + NeMo TitaNet-small (voice embeddings); clustering is in app code
get $REL/asr-models/silero_vad.onnx "$ASSETS/diar/silero_vad.onnx"
get $REL/speaker-recongition-models/nemo_en_titanet_small.onnx "$ASSETS/diar/titanet_small.onnx"

# Local LLM: Qwen2.5-0.5B-Instruct for MediaPipe LLM Inference (apache-2.0, ungated)
get https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task "$ASSETS/llm/qwen2.5-0.5b-instruct-q8.task"

ls -la "$APP/libs" "$ASSETS"/*
