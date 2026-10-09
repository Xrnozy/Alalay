#!/usr/bin/env bash
# Fetch the bundled model weights (DEC-06/07/11). Run once on a PC before
# building the APK; the files are gitignored because of size.
# Expected SHA-256 (verified by ModelStore at first launch too):
#   MiniCPM5-1B-Q4_K_M.gguf  81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa
#   ggml-small-q5_1.bin      ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets/models"

curl -fL -o MiniCPM5-1B-Q4_K_M.gguf \
  "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/main/MiniCPM5-1B-Q4_K_M.gguf"
curl -fL -o ggml-small-q5_1.bin \
  "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

sha256sum MiniCPM5-1B-Q4_K_M.gguf ggml-small-q5_1.bin
