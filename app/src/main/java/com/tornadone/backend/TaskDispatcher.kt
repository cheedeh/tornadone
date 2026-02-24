package com.tornadone.backend

import android.content.Context
import android.util.Log
import com.tornadone.voice.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class TaskDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intentBackend: IntentBackend,
) {
    private val rejectedDir = File(context.filesDir, "recordings/rejected").also { it.mkdirs() }

    suspend fun createTask(description: String): DispatchResult {
        return try {
            intentBackend.notifyTaskCreated(description)
        } catch (e: Exception) {
            Log.e("TaskDispatcher", "IntentBackend failed", e)
            DispatchResult(DispatchMethod.NONE, false, "Exception: ${e.message}")
        }
    }

    suspend fun saveRejectedRecording(audio: FloatArray): String? = withContext(Dispatchers.IO) {
        try {
            val name = "rejected_${System.currentTimeMillis()}"
            val file = File(rejectedDir, "$name.wav")
            WavWriter.write(file, audio)
            Log.i("TaskDispatcher", "Saved rejected recording: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e("TaskDispatcher", "Failed to save recording", e)
            null
        }
    }
}
