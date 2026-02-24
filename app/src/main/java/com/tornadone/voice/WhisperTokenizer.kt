package com.tornadone.voice

import android.content.Context
import org.json.JSONObject

/**
 * Decodes Whisper token IDs to text using vocab.json (GPT-2 BPE vocabulary).
 * Replaces the Whisper_detokenizer.onnx session.
 */
class WhisperTokenizer(context: Context) {

    private val vocab: Map<Int, String> // token ID → decoded string
    private val reverseVocab: Map<String, Int> // GPT-2 encoded string → token ID
    private val byteEncoder: Map<Int, Char> = buildByteEncoderMap()

    init {
        try {
            val json = context.assets.open("vocab.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val byteDecoder = buildByteDecoder()
            val map = mutableMapOf<Int, String>()
            val reverse = mutableMapOf<String, Int>()

            val keys = obj.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = obj.getInt(token)
                // Convert GPT-2 unicode representation back to bytes, then decode as UTF-8
                val bytes = ByteArray(token.length) { i ->
                    (byteDecoder[token[i]] ?: token[i].code).toByte()
                }
                map[id] = String(bytes, Charsets.UTF_8)
                if (id < SPECIAL_TOKEN_START) {
                    reverse[token] = id // keep GPT-2 encoded form for encoding
                }
            }
            vocab = map
            reverseVocab = reverse
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to load vocab.json from assets: ${e.message}. " +
                    "Ensure vocab.json is present in the app's assets directory.",
                e,
            )
        }
    }

    fun decode(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id >= SPECIAL_TOKEN_START) continue // skip special tokens
            vocab[id]?.let { sb.append(it) }
        }
        return sb.toString().trim()
    }

    /**
     * Encode text to token IDs using greedy longest-match on the GPT-2 vocabulary.
     * Not identical to BPE but sufficient for initial prompt conditioning.
     */
    fun encode(text: String): IntArray {
        if (text.isBlank()) return IntArray(0)
        // Convert UTF-8 bytes to GPT-2 unicode representation
        val encoded = buildString {
            for (b in text.toByteArray(Charsets.UTF_8)) {
                append(byteEncoder[b.toInt() and 0xFF])
            }
        }
        val tokens = mutableListOf<Int>()
        var pos = 0
        while (pos < encoded.length) {
            var bestLen = 0
            var bestId = -1
            val maxLen = minOf(encoded.length - pos, 20) // tokens rarely exceed 20 chars
            for (len in maxLen downTo 1) {
                val substr = encoded.substring(pos, pos + len)
                val id = reverseVocab[substr]
                if (id != null) {
                    bestLen = len
                    bestId = id
                    break
                }
            }
            if (bestId >= 0) {
                tokens.add(bestId)
                pos += bestLen
            } else {
                pos++ // skip unrecognized byte
            }
        }
        return tokens.toIntArray()
    }

    companion object {
        private const val SPECIAL_TOKEN_START = 50257

        /**
         * GPT-2 uses a byte-to-unicode mapping where printable ASCII/Latin characters
         * map to themselves, and other byte values map to Unicode chars starting at U+0100.
         * This builds the reverse: unicode char → byte value.
         */
        private fun buildByteDecoder(): Map<Char, Int> {
            val byteEncoder = buildByteEncoderMap()
            return byteEncoder.entries.associate { (byte, char) -> char to byte }
        }

        /** byte value → unicode char */
        private fun buildByteEncoderMap(): Map<Int, Char> {
            val map = mutableMapOf<Int, Char>()
            for (b in 33..126) { map[b] = b.toChar() }
            for (b in 161..172) { map[b] = b.toChar() }
            for (b in 174..255) { map[b] = b.toChar() }
            var n = 0
            for (b in 0..255) {
                if (b !in map) {
                    map[b] = (256 + n).toChar()
                    n++
                }
            }
            return map
        }

        /** byte value → unicode char (for encoding text) */
        fun buildByteEncoder(): Map<Int, Char> = buildByteEncoderMap()
    }
}
