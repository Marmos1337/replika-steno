#!/bin/sh
# Downloads the engines and models that get bundled into the APK.
# Everything ends up in app/libs (engines) and app/src/main/assets/models (models).
set -eu
cd "$(dirname "$0")/.."
CACHE=models-cache
ASSETS=app/src/main/assets/models
mkdir -p "$CACHE" "$ASSETS/asr" "$ASSETS/diar" "$ASSETS/llm" app/libs

get() { # url file
  [ -s "$2" ] && { echo "have $2"; return; }
  echo "get  $1"
  curl -fL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"
}

REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
get $REL/v2.0.1/sherpa-onnx-2.0.1.aar app/libs/sherpa-onnx-2.0.1.aar
get https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-genai/0.10.35/tasks-genai-0.10.35.aar "$CACHE/tasks-genai-0.10.35.aar"

# ASR: GigaAM v3 (Russian, CTC, with punctuation and casing), int8
get $REL/asr-models/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16.tar.bz2 "$CACHE/gigaam.tar.bz2"
if [ ! -s "$ASSETS/asr/model.int8.onnx" ]; then
  tar -xjf "$CACHE/gigaam.tar.bz2" -C "$CACHE"
  d=$(find "$CACHE" -maxdepth 1 -type d -name "*giga-am-v3*" | head -1)
  cp "$d/model.int8.onnx" "$d/tokens.txt" "$ASSETS/asr/"
fi

# Diarization: pyannote segmentation 3.0 + 3D-Speaker CAM++ embeddings
get $REL/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 "$CACHE/pyannote.tar.bz2"
if [ ! -s "$ASSETS/diar/segmentation.onnx" ]; then
  tar -xjf "$CACHE/pyannote.tar.bz2" -C "$CACHE"
  d=$(find "$CACHE" -maxdepth 1 -type d -name "*pyannote-segmentation-3-0*" | head -1)
  if [ -s "$d/model.int8.onnx" ]; then cp "$d/model.int8.onnx" "$ASSETS/diar/segmentation.onnx"; else cp "$d/model.onnx" "$ASSETS/diar/segmentation.onnx"; fi
fi
get $REL/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx "$ASSETS/diar/embedding.onnx"

# Local LLM: Qwen2.5-0.5B-Instruct for MediaPipe LLM Inference (apache-2.0, ungated)
get https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task "$ASSETS/llm/qwen2.5-0.5b-instruct-q8.task"

ls -la app/libs "$ASSETS"/*
