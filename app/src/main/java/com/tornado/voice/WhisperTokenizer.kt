package com.tornado.voice

import android.content.Context
import org.json.JSONObject

/**
 * Decodes Whisper token IDs to text using vocab.json (GPT-2 BPE vocabulary).
 * Replaces the Whisper_detokenizer.onnx session.
 */
class WhisperTokenizer(context: Context) {

    private val vocab: Map<Int, String> // token ID → decoded string

    init {
        val json = context.assets.open("vocab.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val byteDecoder = buildByteDecoder()
        val map = mutableMapOf<Int, String>()

        val keys = obj.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val id = obj.getInt(token)
            // Convert GPT-2 unicode representation back to bytes, then decode as UTF-8
            val bytes = ByteArray(token.length) { i ->
                (byteDecoder[token[i]] ?: token[i].code).toByte()
            }
            map[id] = String(bytes, Charsets.UTF_8)
        }
        vocab = map
    }

    fun decode(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id >= SPECIAL_TOKEN_START) continue // skip special tokens
            vocab[id]?.let { sb.append(it) }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val SPECIAL_TOKEN_START = 50257

        /**
         * GPT-2 uses a byte-to-unicode mapping where printable ASCII/Latin characters
         * map to themselves, and other byte values map to Unicode chars starting at U+0100.
         * This builds the reverse: unicode char → byte value.
         */
        private fun buildByteDecoder(): Map<Char, Int> {
            val byteEncoder = mutableMapOf<Int, Char>()
            // Ranges that map to themselves: 33..126, 161..172, 174..255
            var n = 0
            for (b in 33..126) { byteEncoder[b] = b.toChar() }
            for (b in 161..172) { byteEncoder[b] = b.toChar() }
            for (b in 174..255) { byteEncoder[b] = b.toChar() }
            n = 0
            for (b in 0..255) {
                if (b !in byteEncoder) {
                    byteEncoder[b] = (256 + n).toChar()
                    n++
                }
            }
            // Reverse: char → byte
            return byteEncoder.entries.associate { (byte, char) -> char to byte }
        }
    }
}
