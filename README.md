# Tornadone

An Android app that creates tasks by air-gesture + voice. Slash a Z in the air with your phone, speak your task, done. No app opening, no typing.

**Gesture → Voice → Task.**

## How It Works

1. Background service listens to accelerometer/gyroscope sensors
2. ONNX SVM classifier detects Z gesture (also O, S, M)
3. Beep confirms detection, voice recording starts
4. Whisper ONNX transcribes speech offline (99 languages)
5. Task is dispatched via Android intents (Share, OpenTasks, Tasker)

## Tech Stack

- **Kotlin** + Jetpack Compose + Material 3
- **ONNX Runtime** for gesture classification and Whisper inference
- **Whisper** models: Tiny (~144MB), Base (~278MB), Small (~923MB) — all offline, no cloud
- **Hilt** for dependency injection
- **Min SDK 26** (Android 8.0)

## Build

Requires Java 21 and Android SDK:

```bash
ANDROID_HOME=~/devel/android/sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
```

## Transcription Testing

Instrumented tests evaluate Whisper transcription quality on-device using the real app audio pipeline (high-pass filter → normalization → mel spectrogram → ONNX inference).

### Running Tests

1. Place 16kHz mono WAV files in `app/src/androidTest/assets/test_recordings/`
2. Create `manifest.json`:
   ```json
   [{"file": "sample.wav", "expected": "the expected text", "language": "pl"}]
   ```
3. Run:
   ```bash
   ANDROID_HOME=~/devel/android/sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
     ./gradlew connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.tornadone.voice.TranscriptionTest
   ```

### Key Findings (Pixel 7 Pro, greedy decode)

| Model | No prompt | Specific prompt | Speed |
|-------|-----------|-----------------|-------|
| Tiny  | 83% WER   | 50% WER         | ~3s   |
| Base  | 83% WER   | 28% WER         | ~9s   |
| Small | 61% WER   | 11% WER         | ~26s  |

- **Initial prompt is critical for Polish** — specific vocabulary drops WER dramatically
- **Beam search is 12x slower than greedy** and hallucinates on short audio — greedy is the right choice
- **Generic prompts don't help** — prompt must contain vocabulary the user will actually say

## License

Private project.
