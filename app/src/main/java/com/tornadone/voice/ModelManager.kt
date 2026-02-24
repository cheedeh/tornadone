package com.tornadone.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class WhisperLanguage(
    val code: String,
    val label: String,
    val tokenId: Int,
) {
    companion object {
        // All 99 Whisper languages in token order (50259..50357)
        val ALL: List<WhisperLanguage> = listOf(
            WhisperLanguage("en", "English", 50259),
            WhisperLanguage("zh", "Chinese", 50260),
            WhisperLanguage("de", "German", 50261),
            WhisperLanguage("es", "Spanish", 50262),
            WhisperLanguage("ru", "Russian", 50263),
            WhisperLanguage("ko", "Korean", 50264),
            WhisperLanguage("fr", "French", 50265),
            WhisperLanguage("ja", "Japanese", 50266),
            WhisperLanguage("pt", "Portuguese", 50267),
            WhisperLanguage("tr", "Turkish", 50268),
            WhisperLanguage("pl", "Polish", 50269),
            WhisperLanguage("ca", "Catalan", 50270),
            WhisperLanguage("nl", "Dutch", 50271),
            WhisperLanguage("ar", "Arabic", 50272),
            WhisperLanguage("sv", "Swedish", 50273),
            WhisperLanguage("it", "Italian", 50274),
            WhisperLanguage("id", "Indonesian", 50275),
            WhisperLanguage("hi", "Hindi", 50276),
            WhisperLanguage("fi", "Finnish", 50277),
            WhisperLanguage("vi", "Vietnamese", 50278),
            WhisperLanguage("he", "Hebrew", 50279),
            WhisperLanguage("uk", "Ukrainian", 50280),
            WhisperLanguage("el", "Greek", 50281),
            WhisperLanguage("ms", "Malay", 50282),
            WhisperLanguage("cs", "Czech", 50283),
            WhisperLanguage("ro", "Romanian", 50284),
            WhisperLanguage("da", "Danish", 50285),
            WhisperLanguage("hu", "Hungarian", 50286),
            WhisperLanguage("ta", "Tamil", 50287),
            WhisperLanguage("no", "Norwegian", 50288),
            WhisperLanguage("th", "Thai", 50289),
            WhisperLanguage("ur", "Urdu", 50290),
            WhisperLanguage("hr", "Croatian", 50291),
            WhisperLanguage("bg", "Bulgarian", 50292),
            WhisperLanguage("lt", "Lithuanian", 50293),
            WhisperLanguage("la", "Latin", 50294),
            WhisperLanguage("mi", "Maori", 50295),
            WhisperLanguage("ml", "Malayalam", 50296),
            WhisperLanguage("cy", "Welsh", 50297),
            WhisperLanguage("sk", "Slovak", 50298),
            WhisperLanguage("te", "Telugu", 50299),
            WhisperLanguage("fa", "Persian", 50300),
            WhisperLanguage("lv", "Latvian", 50301),
            WhisperLanguage("bn", "Bengali", 50302),
            WhisperLanguage("sr", "Serbian", 50303),
            WhisperLanguage("az", "Azerbaijani", 50304),
            WhisperLanguage("sl", "Slovenian", 50305),
            WhisperLanguage("kn", "Kannada", 50306),
            WhisperLanguage("et", "Estonian", 50307),
            WhisperLanguage("mk", "Macedonian", 50308),
            WhisperLanguage("br", "Breton", 50309),
            WhisperLanguage("eu", "Basque", 50310),
            WhisperLanguage("is", "Icelandic", 50311),
            WhisperLanguage("hy", "Armenian", 50312),
            WhisperLanguage("ne", "Nepali", 50313),
            WhisperLanguage("mn", "Mongolian", 50314),
            WhisperLanguage("bs", "Bosnian", 50315),
            WhisperLanguage("kk", "Kazakh", 50316),
            WhisperLanguage("sq", "Albanian", 50317),
            WhisperLanguage("sw", "Swahili", 50318),
            WhisperLanguage("gl", "Galician", 50319),
            WhisperLanguage("mr", "Marathi", 50320),
            WhisperLanguage("pa", "Punjabi", 50321),
            WhisperLanguage("si", "Sinhala", 50322),
            WhisperLanguage("km", "Khmer", 50323),
            WhisperLanguage("sn", "Shona", 50324),
            WhisperLanguage("yo", "Yoruba", 50325),
            WhisperLanguage("so", "Somali", 50326),
            WhisperLanguage("af", "Afrikaans", 50327),
            WhisperLanguage("oc", "Occitan", 50328),
            WhisperLanguage("ka", "Georgian", 50329),
            WhisperLanguage("be", "Belarusian", 50330),
            WhisperLanguage("tg", "Tajik", 50331),
            WhisperLanguage("sd", "Sindhi", 50332),
            WhisperLanguage("gu", "Gujarati", 50333),
            WhisperLanguage("am", "Amharic", 50334),
            WhisperLanguage("yi", "Yiddish", 50335),
            WhisperLanguage("lo", "Lao", 50336),
            WhisperLanguage("uz", "Uzbek", 50337),
            WhisperLanguage("fo", "Faroese", 50338),
            WhisperLanguage("ht", "Haitian Creole", 50339),
            WhisperLanguage("ps", "Pashto", 50340),
            WhisperLanguage("tk", "Turkmen", 50341),
            WhisperLanguage("nn", "Nynorsk", 50342),
            WhisperLanguage("mt", "Maltese", 50343),
            WhisperLanguage("sa", "Sanskrit", 50344),
            WhisperLanguage("lb", "Luxembourgish", 50345),
            WhisperLanguage("my", "Myanmar", 50346),
            WhisperLanguage("bo", "Tibetan", 50347),
            WhisperLanguage("tl", "Tagalog", 50348),
            WhisperLanguage("mg", "Malagasy", 50349),
            WhisperLanguage("as", "Assamese", 50350),
            WhisperLanguage("tt", "Tatar", 50351),
            WhisperLanguage("haw", "Hawaiian", 50352),
            WhisperLanguage("ln", "Lingala", 50353),
            WhisperLanguage("ha", "Hausa", 50354),
            WhisperLanguage("ba", "Bashkir", 50355),
            WhisperLanguage("jw", "Javanese", 50356),
            WhisperLanguage("su", "Sundanese", 50357),
        )

        private val byCode = ALL.associateBy { it.code }

        fun fromCode(code: String): WhisperLanguage? = byCode[code]

        fun default(): WhisperLanguage = ALL.first() // English
    }
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

        private val REQUIRED_FILES = listOf(
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

    /** Active download job, tracked so we can cancel on re-download or model switch. */
    private var downloadJob: Job? = null

    init {
        // Synchronous cleanup is acceptable here — these are quick stat+delete operations
        // on app-private storage, not heavy I/O.
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
        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
    }

    fun setModel(model: WhisperModel) {
        // Cancel any in-progress download — it targets the old model's directory.
        downloadJob?.cancel()
        downloadJob = null
        _selectedModel.value = model
        prefs.edit().putString(KEY_MODEL, model.id).apply()
        refreshState()
    }

    private fun modelDir(model: WhisperModel): File =
        File(context.filesDir, "whisper-${model.id}")

    // Called from the main thread but only performs stat() syscalls (exists()) on
    // app-private storage — these are fast metadata-only checks, not actual I/O reads.
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
        val saved = prefs.getString(KEY_LANGUAGE, null) ?: return WhisperLanguage.default()
        return WhisperLanguage.fromCode(saved) ?: WhisperLanguage.default()
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
        if (model == _selectedModel.value) {
            downloadJob?.cancel()
            downloadJob = null
        }
        val dir = modelDir(model)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "Deleted model ${model.displayName}")
        }
        if (model == _selectedModel.value) {
            refreshState()
        }
    }

    private suspend fun downloadModel() = coroutineScope {
        // Cancel any previous download before starting a new one.
        downloadJob?.cancel()
        val job = launch(Dispatchers.IO) {
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
            } catch (e: CancellationException) {
                Log.i(TAG, "Download of ${model.displayName} was cancelled")
                // Clean up partial files on cancellation
                dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model ${model.displayName}", e)
                // Clean up partial files so we don't leave 200MB+ of garbage on disk.
                dir.deleteRecursively()
                _state.value = ModelState.Error(e.message ?: "Unknown error")
            }
        }
        downloadJob = job
        job.join()
    }

    private suspend fun downloadFile(
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
                        // Check for cancellation between chunks so we stop promptly.
                        coroutineContext.ensureActive()
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
