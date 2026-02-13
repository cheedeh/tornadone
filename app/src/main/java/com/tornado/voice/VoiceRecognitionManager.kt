package com.tornado.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    companion object {
        private const val TAG = "VoiceRecognition"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_SEC = 10
        private const val SILENCE_THRESHOLD = 0.01f
        private const val SILENCE_DURATION_MS = 1200
        private const val MIN_SPEECH_SEC = 2
    }

    private val whisper = WhisperRecognizer()
    private val tokenizer by lazy { WhisperTokenizer(context) }
    private var sessionsLoaded = false
    private var loadedModelPath: String? = null
    private var loadedModel: WhisperModel? = null
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    val isModelReady: Boolean get() = modelManager.isReady

    fun initModel(): Boolean {
        val path = modelManager.modelPath ?: return false
        val model = modelManager.currentModel ?: return false
        if (sessionsLoaded && loadedModelPath == path && loadedModel == model) return true
        return try {
            whisper.initSessions(path, model)
            loadedModelPath = path
            loadedModel = model
            sessionsLoaded = true
            Log.i(TAG, "Whisper sessions loaded from $path (${model.displayName})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Whisper sessions", e)
            sessionsLoaded = false
            false
        }
    }

    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!sessionsLoaded) {
            onError("Model not loaded")
            return
        }
        if (recordJob?.isActive == true) {
            Log.w(TAG, "Already listening, ignoring")
            return
        }

        val langTokenId = modelManager.language.value.tokenId

        recordJob = scope.launch {
            try {
                val audio = recordAudio()
                if (audio.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("No audio recorded") }
                    return@launch
                }
                Log.i(TAG, "Recorded ${audio.size} samples (${audio.size / SAMPLE_RATE}s), transcribing...")
                val text = whisper.transcribe(audio, langTokenId, tokenizer)
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) onResult(text) else onError("No speech detected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Recognition error") }
            }
        }
    }

    fun stopListening() {
        recordJob?.cancel()
        recordJob = null
    }

    fun shutdown() {
        stopListening()
        whisper.close()
        sessionsLoaded = false
        loadedModelPath = null
        loadedModel = null
    }

    @SuppressLint("MissingPermission")
    private fun recordAudio(): FloatArray {
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

            while (totalSamples < maxSamples) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break

                val copyLen = read.coerceAtMost(maxSamples - totalSamples)
                System.arraycopy(chunk, 0, pcm, totalSamples, copyLen)
                totalSamples += copyLen

                // Check for silence
                val energy = chunk.take(read).sumOf { (it.toFloat() / Short.MAX_VALUE).let { s -> (s * s).toDouble() } } / read
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
