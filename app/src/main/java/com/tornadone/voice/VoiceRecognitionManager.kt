package com.tornadone.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val transcriber: Transcriber,
) {
    companion object {
        private const val TAG = "VoiceRecognition"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_SEC = 10
        private const val SILENCE_THRESHOLD = 0.005f
        private const val SILENCE_DURATION_MS = 2000
        private const val MIN_SPEECH_SEC = 3
    }

    private val lock = Any()
    private val recordingsDir = File(context.filesDir, "recordings").also { it.mkdirs() }
    @Volatile
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    val isModelReady: Boolean get() = modelManager.isReady

    fun initModel(): Boolean = transcriber.init()

    fun startListening(
        onResult: (text: String, audio: FloatArray) -> Unit,
        onError: (String) -> Unit,
        onRecordingDone: () -> Unit = {},
    ) {
        synchronized(lock) {
            if (!transcriber.isInitialized) {
                onError("Model not loaded")
                return
            }
            if (isRecording) {
                Log.w(TAG, "Already recording, ignoring")
                return
            }

            isRecording = true

            recordJob = scope.launch {
                try {
                    val audio = recordAudio()
                    isRecording = false
                    if (audio.isNotEmpty()) saveRecording(audio)
                    if (audio.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            onRecordingDone()
                            onError("No audio recorded")
                        }
                        return@launch
                    }
                    Log.i(TAG, "Recorded ${audio.size} samples (${audio.size / SAMPLE_RATE}s), preprocessing...")
                    val preprocessed = AudioPreprocessor.normalizeGain(AudioPreprocessor.highPassFilter(audio))
                    withContext(Dispatchers.Main) { onRecordingDone() }
                    Log.i(TAG, "Preprocessed, transcribing...")
                    val text = transcriber.transcribe(preprocessed)
                    withContext(Dispatchers.Main) {
                        if (text.isNotBlank()) onResult(text, audio) else onError("No speech detected")
                    }
                } catch (e: Exception) {
                    isRecording = false
                    Log.e(TAG, "Recognition error", e)
                    withContext(Dispatchers.Main) { onError(e.message ?: "Recognition error") }
                }
            }
        } // synchronized
    }

    fun stopListening() {
        recordJob?.cancel()
        recordJob = null
    }

    fun shutdown() {
        stopListening()
        transcriber.close()
    }

    val lastRecordingPath: String
        get() = File(recordingsDir, "last_recording.wav").absolutePath

    private fun saveRecording(audio: FloatArray) {
        try {
            WavWriter.write(File(recordingsDir, "last_recording.wav"), audio)
            Log.i(TAG, "Saved last recording: ${lastRecordingPath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recording", e)
        }
    }

    fun transcribeFile(
        path: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        synchronized(lock) {
            if (!transcriber.isInitialized) {
                onError("Model not loaded")
                return
            }
            val file = File(path)
            if (!file.exists()) {
                onError("File not found: $path")
                return
            }

            scope.launch {
                try {
                    val audio = WavWriter.read(file)
                    Log.i(TAG, "Read ${audio.size} samples from $path")
                    val preprocessed = AudioPreprocessor.normalizeGain(AudioPreprocessor.highPassFilter(audio))
                    val text = transcriber.transcribe(preprocessed)
                    withContext(Dispatchers.Main) {
                        if (text.isNotBlank()) onResult(text) else onError("No speech detected")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription from file error", e)
                    withContext(Dispatchers.Main) { onError(e.message ?: "Transcription error") }
                }
            }
        }
    }

    // RECORD_AUDIO permission is checked at the service/activity layer before reaching this code
    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): FloatArray {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE * 2) // at least 1 second buffer

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )

        val maxSamples = SAMPLE_RATE * MAX_RECORD_SEC
        val pcm = ShortArray(maxSamples)
        var totalSamples = 0

        val silenceSamples = SAMPLE_RATE * SILENCE_DURATION_MS / 1000
        var silenceCount = 0

        try {
            recorder.startRecording()
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100ms chunks

            while (totalSamples < maxSamples && coroutineContext.isActive) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break

                val copyLen = read.coerceAtMost(maxSamples - totalSamples)
                System.arraycopy(chunk, 0, pcm, totalSamples, copyLen)
                totalSamples += copyLen

                var energy = 0.0
                for (i in 0 until read) {
                    val s = chunk[i].toFloat() / Short.MAX_VALUE
                    energy += s * s
                }
                energy /= read
                if (energy < SILENCE_THRESHOLD) {
                    silenceCount += read
                    if (silenceCount >= silenceSamples && totalSamples > SAMPLE_RATE * MIN_SPEECH_SEC) {
                        break
                    }
                } else {
                    silenceCount = 0
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        if (totalSamples == 0) return FloatArray(0)

        // Convert PCM16 to float32 [-1, 1]
        val result = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            result[i] = pcm[i].toFloat() / Short.MAX_VALUE
        }
        return result
    }
}
