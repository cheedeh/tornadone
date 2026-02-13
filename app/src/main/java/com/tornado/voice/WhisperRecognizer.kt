package com.tornado.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Whisper speech recognition via 2 standard HuggingFace ONNX sessions:
 * encoder_model_int8.onnx + decoder_model_merged_int8.onnx.
 *
 * The merged decoder uses a `use_cache_branch` boolean input:
 * - false on the first step (computes cross-attention KV from encoder output)
 * - true on subsequent steps (uses cached KV values)
 */
class WhisperRecognizer {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val VOCAB_SIZE = 51865
        private const val MAX_TOKENS = 224
        private const val SOT = 50258
        private const val EOT = 50257
        private const val TRANSCRIBE = 50359
        private const val NO_TIMESTAMPS = 50363
        private const val SAMPLE_RATE = 16000
        private const val MAX_AUDIO_SEC = 30
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private var numLayers = 0
    private var numHeads = 0
    private var headDim = 0
    private var dModel = 0

    fun initSessions(modelDir: String, model: WhisperModel) {
        close()
        numLayers = model.numLayers
        numHeads = model.numHeads
        headDim = model.headDim
        dModel = model.dModel

        env = OrtEnvironment.getEnvironment()
        val e = env!!

        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)

        encoderSession = e.createSession(
            File(modelDir, WhisperModel.ENCODER_FILE).absolutePath, opts,
        )
        decoderSession = e.createSession(
            File(modelDir, WhisperModel.DECODER_FILE).absolutePath, opts,
        )

        Log.i(TAG, "2 ONNX sessions loaded (${model.displayName}, layers=$numLayers, heads=$numHeads, d=$dModel)")
    }

    /**
     * Transcribe audio samples (16kHz float32, mono, range [-1,1]).
     */
    fun transcribe(samples: FloatArray, languageTokenId: Int, tokenizer: WhisperTokenizer): String {
        val e = env ?: throw IllegalStateException("Sessions not initialized")

        val maxSamples = SAMPLE_RATE * MAX_AUDIO_SEC
        val padded = if (samples.size >= maxSamples) {
            samples.copyOf(maxSamples)
        } else {
            FloatArray(maxSamples).also { samples.copyInto(it) }
        }

        Log.i(TAG, "Transcribing ${samples.size} samples, lang token=$languageTokenId")

        // Step 1: Audio -> mel spectrogram (pure Kotlin)
        val mel = MelSpectrogram.compute(padded)
        Log.i(TAG, "Mel spectrogram: ${mel.size} floats")

        // Step 2: Mel -> encoder hidden states
        val encoderOut = runEncoder(e, mel)
        Log.i(TAG, "Encoder output: ${encoderOut.size} floats")

        // Step 3: Autoregressive decoder
        val tokens = runDecoder(e, encoderOut, languageTokenId)
        Log.i(TAG, "Decoder produced ${tokens.size} tokens: ${tokens.toList()}")

        // Step 4: Tokens -> text (pure Kotlin)
        val text = tokenizer.decode(tokens)
        Log.i(TAG, "Decoded: '$text'")
        return text
    }

    private fun runEncoder(env: OrtEnvironment, mel: FloatArray): FloatArray {
        val shape = longArrayOf(1, 80, 3000)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), shape)
        tensor.use {
            encoderSession!!.run(mapOf("input_features" to tensor)).use { result ->
                val out = result.get("last_hidden_state").get() as OnnxTensor
                return flattenFloat(out)
            }
        }
    }

    private data class KvCache(
        val encoderKeys: Array<FloatArray>,
        val encoderValues: Array<FloatArray>,
        val decoderKeys: Array<FloatArray>,
        val decoderValues: Array<FloatArray>,
        val decoderSeqLen: Int,
        val encoderSeqLen: Int,
    )

    private fun runDecoder(
        env: OrtEnvironment,
        encoderOut: FloatArray,
        languageTokenId: Int,
    ): IntArray {
        val encoderSeqLen = encoderOut.size / dModel
        val startTokens = intArrayOf(SOT, languageTokenId, TRANSCRIBE, NO_TIMESTAMPS)
        val generated = mutableListOf<Int>()

        // Initial empty KV cache
        var kv = KvCache(
            encoderKeys = Array(numLayers) { FloatArray(0) },
            encoderValues = Array(numLayers) { FloatArray(0) },
            decoderKeys = Array(numLayers) { FloatArray(0) },
            decoderValues = Array(numLayers) { FloatArray(0) },
            decoderSeqLen = 0,
            encoderSeqLen = 0,
        )

        // Feed forced start tokens; first call has use_cache_branch=false
        var logits = FloatArray(0)
        var isFirstStep = true
        for (token in startTokens) {
            val result = decoderStep(env, token.toLong(), kv, encoderOut, encoderSeqLen, isFirstStep)
            logits = result.first
            kv = result.second
            isFirstStep = false
        }

        // Autoregressive generation
        var nextToken = argmax(logits)
        while (nextToken != EOT && generated.size < MAX_TOKENS) {
            generated.add(nextToken)
            val result = decoderStep(env, nextToken.toLong(), kv, encoderOut, encoderSeqLen, false)
            logits = result.first
            kv = result.second
            nextToken = argmax(logits)
        }

        return generated.toIntArray()
    }

    private fun decoderStep(
        env: OrtEnvironment,
        token: Long,
        kv: KvCache,
        encoderOut: FloatArray,
        encoderSeqLen: Int,
        isFirstStep: Boolean,
    ): Pair<FloatArray, KvCache> {
        val inputs = mutableMapOf<String, OnnxTensor>()
        val tensorsToClose = mutableListOf<OnnxTensor>()

        try {
            // input_ids: int64[1, 1]
            val idTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1),
            )
            inputs["input_ids"] = idTensor
            tensorsToClose.add(idTensor)

            // encoder_hidden_states: float32[1, encoder_seq_len, d_model]
            val encShape = longArrayOf(1, encoderSeqLen.toLong(), dModel.toLong())
            val encTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderOut), encShape)
            inputs["encoder_hidden_states"] = encTensor
            tensorsToClose.add(encTensor)

            // use_cache_branch: bool tensor [1]
            val useCacheTensor = OnnxTensor.createTensor(
                env, booleanArrayOf(!isFirstStep),
            )
            inputs["use_cache_branch"] = useCacheTensor
            tensorsToClose.add(useCacheTensor)

            // Past KV cache tensors
            for (i in 0 until numLayers) {
                if (isFirstStep) {
                    // First step: empty past KV (sequence dim = 0)
                    val emptyShape = longArrayOf(1, numHeads.toLong(), 0, headDim.toLong())
                    val emptyBuf = FloatBuffer.allocate(0)
                    val dkT = OnnxTensor.createTensor(env, emptyBuf, emptyShape)
                    val dvT = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape)
                    val ekT = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape)
                    val evT = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape)
                    inputs["past_key_values.$i.decoder.key"] = dkT
                    inputs["past_key_values.$i.decoder.value"] = dvT
                    inputs["past_key_values.$i.encoder.key"] = ekT
                    inputs["past_key_values.$i.encoder.value"] = evT
                    tensorsToClose.addAll(listOf(dkT, dvT, ekT, evT))
                } else {
                    val decSeqLen = kv.decoderSeqLen.toLong()
                    val encKvSeqLen = kv.encoderSeqLen.toLong()
                    val decShape = longArrayOf(1, numHeads.toLong(), decSeqLen, headDim.toLong())
                    val encKvShape = longArrayOf(1, numHeads.toLong(), encKvSeqLen, headDim.toLong())

                    val dkT = OnnxTensor.createTensor(env, FloatBuffer.wrap(kv.decoderKeys[i]), decShape)
                    val dvT = OnnxTensor.createTensor(env, FloatBuffer.wrap(kv.decoderValues[i]), decShape)
                    val ekT = OnnxTensor.createTensor(env, FloatBuffer.wrap(kv.encoderKeys[i]), encKvShape)
                    val evT = OnnxTensor.createTensor(env, FloatBuffer.wrap(kv.encoderValues[i]), encKvShape)
                    inputs["past_key_values.$i.decoder.key"] = dkT
                    inputs["past_key_values.$i.decoder.value"] = dvT
                    inputs["past_key_values.$i.encoder.key"] = ekT
                    inputs["past_key_values.$i.encoder.value"] = evT
                    tensorsToClose.addAll(listOf(dkT, dvT, ekT, evT))
                }
            }

            decoderSession!!.run(inputs).use { result ->
                val logitsTensor = result.get("logits").get() as OnnxTensor
                val logits = flattenFloat(logitsTensor)

                val newDecKeys = Array(numLayers) { i ->
                    flattenFloat(result.get("present.$i.decoder.key").get() as OnnxTensor)
                }
                val newDecValues = Array(numLayers) { i ->
                    flattenFloat(result.get("present.$i.decoder.value").get() as OnnxTensor)
                }
                // Encoder KV: only populated on first step (use_cache_branch=false).
                // On subsequent steps the model outputs empty tensors, so reuse input.
                val newEncKeys: Array<FloatArray>
                val newEncValues: Array<FloatArray>
                val newEncSeqLen: Int
                if (isFirstStep) {
                    newEncKeys = Array(numLayers) { i ->
                        flattenFloat(result.get("present.$i.encoder.key").get() as OnnxTensor)
                    }
                    newEncValues = Array(numLayers) { i ->
                        flattenFloat(result.get("present.$i.encoder.value").get() as OnnxTensor)
                    }
                    newEncSeqLen = if (newEncKeys[0].isNotEmpty()) {
                        newEncKeys[0].size / (numHeads * headDim)
                    } else 0
                } else {
                    newEncKeys = kv.encoderKeys
                    newEncValues = kv.encoderValues
                    newEncSeqLen = kv.encoderSeqLen
                }

                val newDecSeqLen = if (newDecKeys[0].isNotEmpty()) {
                    newDecKeys[0].size / (numHeads * headDim)
                } else 0

                val newKv = KvCache(
                    encoderKeys = newEncKeys,
                    encoderValues = newEncValues,
                    decoderKeys = newDecKeys,
                    decoderValues = newDecValues,
                    decoderSeqLen = newDecSeqLen,
                    encoderSeqLen = newEncSeqLen,
                )
                return Pair(logits, newKv)
            }
        } finally {
            tensorsToClose.forEach { it.close() }
        }
    }

    private fun flattenFloat(tensor: OnnxTensor): FloatArray {
        val buf = tensor.floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        for (i in 1 until logits.size.coerceAtMost(VOCAB_SIZE)) {
            if (logits[i] > logits[best]) best = i
        }
        return best
    }


    fun close() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        env = null
    }
}
