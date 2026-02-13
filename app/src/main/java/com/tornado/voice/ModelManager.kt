package com.tornado.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

enum class WhisperLanguage(
    val label: String,
    val code: String,
    val tokenId: Int,
) {
    EN("English", "en", 50259),
    PL("Polski", "pl", 50269);
}

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data class Ready(val path: String, val model: WhisperModel) : ModelState
    data class Error(val message: String) : ModelState
}

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS = "tornado_prefs"
        private const val KEY_LANGUAGE = "voice_language"
        private const val KEY_MODEL = "voice_model"
        private const val LEGACY_DIR_NAME = "whisper-model"

        val REQUIRED_FILES = listOf(
            WhisperModel.ENCODER_FILE,
            WhisperModel.DECODER_FILE,
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(loadSavedLanguage())
    val language: StateFlow<WhisperLanguage> = _language.asStateFlow()

    private val _selectedModel = MutableStateFlow(loadSavedModel())
    val selectedModel: StateFlow<WhisperModel> = _selectedModel.asStateFlow()

    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        cleanupLegacyDir()
        refreshState()
    }

    val isReady: Boolean
        get() = _state.value is ModelState.Ready

    val modelPath: String?
        get() = (_state.value as? ModelState.Ready)?.path

    val currentModel: WhisperModel?
        get() = (_state.value as? ModelState.Ready)?.model

    fun setLanguage(lang: WhisperLanguage) {
        _language.value = lang
        prefs.edit().putString(KEY_LANGUAGE, lang.name).apply()
    }

    fun setModel(model: WhisperModel) {
        _selectedModel.value = model
        prefs.edit().putString(KEY_MODEL, model.id).apply()
        refreshState()
    }

    private fun modelDir(model: WhisperModel): File =
        File(context.filesDir, "whisper-${model.id}")

    private fun refreshState() {
        val model = _selectedModel.value
        val dir = modelDir(model)
        _state.value = if (allFilesPresent(dir)) {
            ModelState.Ready(dir.absolutePath, model)
        } else {
            ModelState.NotDownloaded
        }
    }

    private fun allFilesPresent(dir: File): Boolean {
        if (!dir.exists()) return false
        return REQUIRED_FILES.all { File(dir, it).exists() }
    }

    private fun loadSavedLanguage(): WhisperLanguage {
        val saved = prefs.getString(KEY_LANGUAGE, null)
        return saved?.let {
            try { WhisperLanguage.valueOf(it) } catch (_: Exception) { null }
        } ?: WhisperLanguage.EN
    }

    private fun loadSavedModel(): WhisperModel {
        val saved = prefs.getString(KEY_MODEL, null)
        return saved?.let { WhisperModel.fromId(it) } ?: WhisperModel.TINY
    }

    private fun cleanupLegacyDir() {
        val legacyDir = File(context.filesDir, LEGACY_DIR_NAME)
        if (legacyDir.exists()) {
            Log.i(TAG, "Cleaning up legacy model directory: ${legacyDir.absolutePath}")
            legacyDir.deleteRecursively()
        }
        // Clean up old int8 files from per-model dirs
        val int8Files = listOf("encoder_model_int8.onnx", "decoder_model_merged_int8.onnx")
        for (model in WhisperModel.entries) {
            val dir = modelDir(model)
            for (f in int8Files) {
                val old = File(dir, f)
                if (old.exists()) {
                    old.delete()
                    Log.i(TAG, "Cleaned up legacy int8 file: ${old.name}")
                }
            }
        }
    }

    suspend fun ensureModel() {
        if (_state.value is ModelState.Ready) return
        downloadModel()
    }

    suspend fun deleteModel(model: WhisperModel) = withContext(Dispatchers.IO) {
        val dir = modelDir(model)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "Deleted model ${model.displayName}")
        }
        if (model == _selectedModel.value) {
            refreshState()
        }
    }

    private suspend fun downloadModel() = withContext(Dispatchers.IO) {
        val model = _selectedModel.value
        val dir = modelDir(model)
        try {
            _state.value = ModelState.Downloading(0f)
            dir.mkdirs()

            val encoderSize = model.encoderSizeMB.toLong()
            val decoderSize = model.decoderSizeMB.toLong()
            val totalSize = encoderSize + decoderSize

            // Download encoder
            downloadFile(
                model.encoderUrl,
                File(dir, WhisperModel.ENCODER_FILE),
                progressOffset = 0f,
                progressWeight = encoderSize.toFloat() / totalSize,
            )

            // Download decoder
            downloadFile(
                model.decoderUrl,
                File(dir, WhisperModel.DECODER_FILE),
                progressOffset = encoderSize.toFloat() / totalSize,
                progressWeight = decoderSize.toFloat() / totalSize,
            )

            if (allFilesPresent(dir)) {
                _state.value = ModelState.Ready(dir.absolutePath, model)
                Log.i(TAG, "Model ${model.displayName} ready at ${dir.absolutePath}")
            } else {
                val missing = REQUIRED_FILES.filter { !File(dir, it).exists() }
                _state.value = ModelState.Error("Missing files: ${missing.joinToString()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model ${model.displayName}", e)
            _state.value = ModelState.Error(e.message ?: "Unknown error")
        }
    }

    private fun downloadFile(
        urlStr: String,
        dest: File,
        progressOffset: Float,
        progressWeight: Float,
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L

            BufferedInputStream(conn.inputStream, 8192).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                        downloaded += len
                        if (totalBytes > 0) {
                            val fileProgress = downloaded.toFloat() / totalBytes
                            _state.value = ModelState.Downloading(
                                progressOffset + fileProgress * progressWeight,
                            )
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
