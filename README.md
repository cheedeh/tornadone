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

## Testing

```bash
ANDROID_HOME=~/devel/android/sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:test
```

JVM unit tests cover audio preprocessing (`AudioPreprocessor`) and mel spectrogram computation (`MelSpectrogram`) with no device required.