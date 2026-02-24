package com.tornadone.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.tornadone.data.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class RemoteTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val preferencesManager: PreferencesManager,
) {
    companion object {
        private const val TAG = "RemoteTranscriber"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_SEC = 10
        private const val SILENCE_THRESHOLD = 0.005f
        private const val SILENCE_DURATION_MS = 2000
        private const val MIN_SPEECH_SEC = 3
        private const val BOUNDARY = "----TornadoneBoundary"
    }

    private val recordingsDir = File(context.filesDir, "recordings").also { it.mkdirs() }
    @Volatile
    private var isRecording = false
    private var recordJob: Job? = null
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    val lastRecordingPath: String
        get() = File(recordingsDir, "last_recording.wav").absolutePath

    fun startListening(
        onResult: (text: String, audio: FloatArray) -> Unit,
        onError: (String) -> Unit,
        onRecordingDone: () -> Unit = {},
    ) {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring")
            return
        }

        isRecording = true
        val engine = preferencesManager.voiceEngine

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
                Log.i(TAG, "Recorded ${audio.size} samples (${audio.size / SAMPLE_RATE}s)")
                val preprocessed = AudioPreprocessor.normalizeGain(AudioPreprocessor.highPassFilter(audio))
                withContext(Dispatchers.Main) { onRecordingDone() }

                // Save preprocessed audio as WAV for upload
                val tempWav = File(context.cacheDir, "upload_temp.wav")
                WavWriter.write(tempWav, preprocessed)

                val text = transcribeRemote(tempWav, engine)
                tempWav.delete()

                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) onResult(text, audio) else onError("No speech detected")
                }
            } catch (e: Exception) {
                isRecording = false
                Log.e(TAG, "Remote transcription error", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Transcription error") }
            }
        }
    }

    fun stopListening() {
        recordJob?.cancel()
        recordJob = null
    }

    private fun transcribeRemote(wavFile: File, engine: String): String {
        val langCode = modelManager.language.value.code

        return when (engine) {
            "openai" -> {
                val apiKey = preferencesManager.openaiApiKey
                if (apiKey.isBlank()) throw IllegalStateException("OpenAI API key not set")
                sendMultipart(
                    url = "https://api.openai.com/v1/audio/transcriptions",
                    authHeader = "Bearer $apiKey",
                    wavFile = wavFile,
                    extraFields = mapOf("model" to "whisper-1", "language" to langCode),
                )
            }
            "custom" -> {
                val url = preferencesManager.customTranscriptionUrl
                if (url.isBlank()) throw IllegalStateException("Custom transcription URL not set")
                if (!url.startsWith("https://")) throw IllegalStateException("Custom transcription URL must use HTTPS")
                val auth = preferencesManager.customTranscriptionAuthHeader
                sendMultipart(
                    url = url,
                    authHeader = auth.ifBlank { null },
                    wavFile = wavFile,
                    extraFields = mapOf("language" to langCode),
                )
            }
            else -> throw IllegalArgumentException("Unsupported remote engine: $engine")
        }
    }

    private fun sendMultipart(
        url: String,
        authHeader: String?,
        wavFile: File,
        extraFields: Map<String, String>,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader)
        }

        try {
            conn.outputStream.use { os ->
                val writer = OutputStreamWriter(os, Charsets.UTF_8)

                // Write text fields
                for ((key, value) in extraFields) {
                    writer.append("--$BOUNDARY\r\n")
                    writer.append("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                    writer.append("$value\r\n")
                }

                // Write file field
                writer.append("--$BOUNDARY\r\n")
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                writer.append("Content-Type: audio/wav\r\n\r\n")
                writer.flush()
                wavFile.inputStream().use { it.copyTo(os) }
                os.flush()
                writer.append("\r\n--$BOUNDARY--\r\n")
                writer.flush()
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("HTTP $responseCode: $errorBody")
            }

            return parseResponse(responseBody)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(body: String): String {
        return try {
            JSONObject(body).getString("text")
        } catch (e: Exception) {
            // Fallback: treat as plain text
            body.trim()
        }
    }

    private fun saveRecording(audio: FloatArray) {
        try {
            WavWriter.write(File(recordingsDir, "last_recording.wav"), audio)
            Log.i(TAG, "Saved last recording: $lastRecordingPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recording", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): FloatArray {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE * 2)

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
            val chunk = ShortArray(SAMPLE_RATE / 10)

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

        val result = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            result[i] = pcm[i].toFloat() / Short.MAX_VALUE
        }
        return result
    }
}
