# Moonbear Auto

Android Auto voice line for a 2025 Kia K4: on-device Parakeet STT and Kokoro TTS, with only the Hermes turn going to the server.

## Building

The model weights are not in git (GitHub caps a file at 100 MB), so fetch them first:

```sh
./scripts/fetch_models.sh          # downloads ~800 MB from the sherpa-onnx HF repos
./scripts/fetch_models.sh --check  # verify what is present
```

Then build:

```sh
./build_apk.sh     # needs ANDROID_HOME, build-tools 35.0.1, and the signing password
```

The APK bundles Parakeet TDT 0.6B v3 (int8) and Kokoro-82M (int8) plus the walked
bear voice tensor (`assets/bear_voice.bin`), which is injected into `voices.bin`
speaker 0 on first run. Nothing is downloaded at runtime; first launch merely
unpacks the assets into app storage.
