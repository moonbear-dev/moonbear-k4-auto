#!/usr/bin/env bash
# Fetch the model weights the APK bundles into assets/models/.
#
# The weights are far too large for git (GitHub caps a file at 100 MB), so a
# fresh clone runs this once before building. Everything comes from the
# official sherpa-onnx Hugging Face repos.
#
#   ./scripts/fetch_models.sh            # fetch anything missing
#   ./scripts/fetch_models.sh --check    # report what is missing, download nothing
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/assets/models"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

PARAKEET_REPO="csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
KOKORO_REPO="csukuangfj/kokoro-int8-multi-lang-v1_0"

# model_dir|repo|file|expected_bytes (0 = don't care)
FILES=(
  "parakeet-v3-int8|$PARAKEET_REPO|encoder.int8.onnx|652184281"
  "parakeet-v3-int8|$PARAKEET_REPO|decoder.int8.onnx|11845275"
  "parakeet-v3-int8|$PARAKEET_REPO|joiner.int8.onnx|6355277"
  "parakeet-v3-int8|$PARAKEET_REPO|tokens.txt|93939"
  "kokoro-int8-multi-lang-v1_0|$KOKORO_REPO|model.int8.onnx|114203756"
  "kokoro-int8-multi-lang-v1_0|$KOKORO_REPO|voices.bin|28200960"
  "kokoro-int8-multi-lang-v1_0|$KOKORO_REPO|tokens.txt|687"
  "kokoro-int8-multi-lang-v1_0|$KOKORO_REPO|lexicon-us-en.txt|5956885"
)

missing=0
for row in "${FILES[@]}"; do
  IFS='|' read -r dir repo file want <<<"$row"
  out="$DEST/$dir/$file"
  if [ -f "$out" ] && [ "$(stat -c%s "$out")" -gt 0 ]; then
    printf '  ok       %-40s %10d bytes\n' "$dir/$file" "$(stat -c%s "$out")"
    continue
  fi
  missing=$((missing + 1))
  if [ "$CHECK_ONLY" = "1" ]; then
    printf '  MISSING  %s\n' "$dir/$file"
    continue
  fi
  mkdir -p "$DEST/$dir"
  printf '  fetch    %-40s ' "$dir/$file"
  url="https://huggingface.co/$repo/resolve/main/$file"
  if curl -fL --retry 3 --retry-delay 2 -o "$out.part" "$url"; then
    mv "$out.part" "$out"
    printf 'ok (%d bytes)\n' "$(stat -c%s "$out")"
  else
    rm -f "$out.part"
    printf 'FAILED\n'
    exit 1
  fi
done

# Silero VAD is small and lives in two places (res/raw for the packaged copy,
# assets/ because the build copies assets wholesale).
if [ ! -f "$ROOT/assets/silero_vad.onnx" ]; then
  missing=$((missing + 1))
  if [ "$CHECK_ONLY" = "1" ]; then
    echo "  MISSING  assets/silero_vad.onnx"
  else
    printf '  fetch    %-40s ' "silero_vad.onnx"
    curl -fL --retry 3 -o "$ROOT/assets/silero_vad.onnx" \
      "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" \
      && printf 'ok\n' || { printf 'FAILED\n'; exit 1; }
  fi
else
  printf '  ok       %-40s %10d bytes\n' "silero_vad.onnx" "$(stat -c%s "$ROOT/assets/silero_vad.onnx")"
fi

if [ "$CHECK_ONLY" = "1" ]; then
  echo
  [ "$missing" -eq 0 ] && echo "all model files present" || echo "$missing file(s) missing — run ./scripts/fetch_models.sh"
  exit 0
fi

total=$(du -sh "$DEST" 2>/dev/null | cut -f1)
echo
echo "models ready in assets/models ($total)"
echo "note: the walked bear voice lives in assets/bear_voice.bin and is injected"
echo "      into voices.bin speaker 0 on first run on the phone."
