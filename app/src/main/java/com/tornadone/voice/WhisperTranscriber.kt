package com.tornadone.voice

import android.content.Context
import android.util.Log
import com.tornadone.data.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhisperTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val preferencesManager: PreferencesManager,
) : Transcriber {

    companion object {
        private const val TAG = "WhisperTranscriber"
    }

    private val whisper = WhisperRecognizer()
    private val tokenizer by lazy { WhisperTokenizer(context) }
    private val lock = Any()

    @Volatile private var sessionsLoaded = false
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedModel: WhisperModel? = null

    override val isInitialized: Boolean get() = sessionsLoaded

    override fun init(): Boolean {
        val path = modelManager.modelPath ?: return false
        val model = modelManager.currentModel ?: return false
        if (sessionsLoaded && loadedModelPath == path && loadedModel == model) return true
        synchronized(lock) {
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
    }

    override fun transcribe(audio: FloatArray): String {
        val langTokenId = modelManager.language.value.tokenId
        val prompt = preferencesManager.initialPrompt
        return whisper.transcribe(audio, langTokenId, tokenizer, prompt)
    }

    override fun close() {
        synchronized(lock) {
            whisper.close()
            sessionsLoaded = false
            loadedModelPath = null
            loadedModel = null
        }
    }
}
