package com.tornadone.gesture

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GestureModelDownloader {
    private const val TAG = "GestureModelDownloader"
    const val MODEL_URL = "https://huggingface.co/ravenwing/cheedeh-model/resolve/main/gesture_classifier.onnx"

    suspend fun download(
        destFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connect()
        val contentLength = connection.contentLength
        try {
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        bytesRead += count
                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        Log.i(TAG, "Downloaded gesture model to ${destFile.absolutePath}")
    }
}
