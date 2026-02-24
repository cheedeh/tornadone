package com.tornadone.voice

interface Transcriber {
    val isInitialized: Boolean
    fun init(): Boolean
    fun transcribe(audio: FloatArray): String
    fun close()
}
