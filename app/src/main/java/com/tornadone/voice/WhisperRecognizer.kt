package com.tornadone.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.exp
import kotlin.math.ln

/**
 * Whisper speech recognition via 2 standard HuggingFace ONNX sessions:
 * encoder_model.onnx + decoder_model_merged.onnx.
 *
 * Implements beam search decoding at temperature 0 with repetition penalty.
 * Falls back to greedy decode if beam search hits OOM.
 */
data class DecodeConfig(
    val beamWidth: Int = 3,
    val repetitionPenalty: Float = 1.2f,
) {
    companion object {
        val DEFAULT = DecodeConfig()
        val GREEDY = DecodeConfig(beamWidth = 1, repetitionPenalty = 1.0f)
        val GREEDY_WITH_PENALTY = DecodeConfig(beamWidth = 1, repetitionPenalty = 1.2f)
    }

    val label: String get() = when {
        beamWidth == 1 && repetitionPenalty == 1.0f -> "greedy"
        beamWidth == 1 -> "greedy+rep"
        else -> "beam$beamWidth"
    }
}

class WhisperRecognizer {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val VOCAB_SIZE = 51865
        private const val MAX_TOKENS = 224
        private const val SOT = 50258
        private const val EOT = 50257
        private const val TRANSCRIBE = 50359
        private const val NO_TIMESTAMPS = 50363
        private const val STARTOFPREV = 50361
        private const val SAMPLE_RATE = 16000
        private const val MAX_AUDIO_SEC = 30
    }

    private val sessionLock = ReentrantLock()

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null

    @Volatile private var numLayers = 0
    @Volatile private var numHeads = 0
    @Volatile private var headDim = 0
    @Volatile private var dModel = 0

    fun initSessions(modelDir: String, model: WhisperModel) {
        sessionLock.withLock {
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
    }

    /**
     * Transcribe audio samples (16kHz float32, mono, range [-1,1]).
     */
    fun transcribe(
        samples: FloatArray,
        languageTokenId: Int,
        tokenizer: WhisperTokenizer,
        initialPrompt: String = "",
        config: DecodeConfig = DecodeConfig.DEFAULT,
    ): String {
        sessionLock.withLock {
            val e = env ?: throw IllegalStateException(
                "Sessions not initialized — call initSessions() before transcribe()"
            )

            val maxSamples = SAMPLE_RATE * MAX_AUDIO_SEC
            val padded = if (samples.size >= maxSamples) {
                samples.copyOf(maxSamples)
            } else {
                FloatArray(maxSamples).also { samples.copyInto(it) }
            }

            Log.i(TAG, "Transcribing ${samples.size} samples, lang token=$languageTokenId, decode=${config.label}")

            val mel = MelSpectrogram.compute(padded)
            Log.i(TAG, "Mel spectrogram: ${mel.size} floats")

            val encoderOut = runEncoder(e, mel)
            Log.i(TAG, "Encoder output: ${encoderOut.size} floats")

            val tokens = runDecoder(e, encoderOut, languageTokenId, tokenizer, initialPrompt, config)
            Log.i(TAG, "Decoder produced ${tokens.size} tokens: ${tokens.toList()}")

            val text = tokenizer.decode(tokens)
            Log.i(TAG, "Decoded: '$text'")
            return text
        }
    }

    private fun runEncoder(env: OrtEnvironment, mel: FloatArray): FloatArray {
        val shape = longArrayOf(1, 80, 3000)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), shape)
        tensor.use {
            val session = encoderSession
                ?: throw IllegalStateException("Encoder session closed during transcription")
            session.run(mapOf("input_features" to tensor)).use { result ->
                val out = result.get("last_hidden_state").get() as OnnxTensor
                return flattenFloat(out)
            }
        }
    }

    // ---- KV cache types ----

    private data class EncoderKv(
        val keys: Array<FloatArray>,
        val values: Array<FloatArray>,
        val seqLen: Int,
    )

    private data class DecoderKv(
        val keys: Array<FloatArray>,
        val values: Array<FloatArray>,
        val seqLen: Int,
    )

    private data class StepResult(
        val logits: FloatArray,
        val encoderKv: EncoderKv,
        val decoderKv: DecoderKv,
    )

    private data class DecodeResult(
        val tokens: IntArray,
        val avgLogProb: Double,
    )

    private data class Beam(
        val tokens: MutableList<Int>,
        var score: Double,
        var logits: FloatArray,
        var decoderKv: DecoderKv,
    )

    // ---- Decoder orchestrator ----

    private fun runDecoder(
        env: OrtEnvironment,
        encoderOut: FloatArray,
        languageTokenId: Int,
        tokenizer: WhisperTokenizer,
        initialPrompt: String = "",
        config: DecodeConfig = DecodeConfig.DEFAULT,
    ): IntArray {
        val encoderSeqLen = encoderOut.size / dModel

        // Build forced token sequence: optional [startofprev + prompt] then [SOT, lang, task, notimestamps]
        val promptTokens = if (initialPrompt.isNotBlank()) {
            val encoded = tokenizer.encode(initialPrompt)
            // Limit prompt to 224 tokens to leave room for output
            val trimmed = if (encoded.size > MAX_TOKENS) encoded.copyOfRange(encoded.size - MAX_TOKENS, encoded.size) else encoded
            Log.i(TAG, "Initial prompt: '$initialPrompt' → ${trimmed.size} tokens")
            intArrayOf(STARTOFPREV) + trimmed
        } else {
            IntArray(0)
        }
        val startTokens = promptTokens + intArrayOf(SOT, languageTokenId, TRANSCRIBE, NO_TIMESTAMPS)

        val forced = feedForcedTokens(env, startTokens, encoderOut, encoderSeqLen)

        val result = if (config.beamWidth <= 1) {
            greedyDecode(env, forced.encoderKv, forced.decoderKv, forced.logits.copyOf(), encoderOut, encoderSeqLen, config.repetitionPenalty)
        } else {
            try {
                beamSearch(env, forced.encoderKv, forced.decoderKv, forced.logits.copyOf(), encoderOut, encoderSeqLen, config.beamWidth, config.repetitionPenalty)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Beam search OOM, falling back to greedy")
                System.gc()
                greedyDecode(env, forced.encoderKv, forced.decoderKv, forced.logits.copyOf(), encoderOut, encoderSeqLen, config.repetitionPenalty)
            }
        }

        Log.i(TAG, "Decoded ${result.tokens.size} tokens (avgLogProb=${"%.3f".format(result.avgLogProb)})")
        return result.tokens
    }

    // ---- Forced token feeding ----

    private data class ForcedTokenResult(
        val logits: FloatArray,
        val encoderKv: EncoderKv,
        val decoderKv: DecoderKv,
    )

    private fun feedForcedTokens(
        env: OrtEnvironment,
        tokens: IntArray,
        encoderOut: FloatArray,
        encoderSeqLen: Int,
    ): ForcedTokenResult {
        var encoderKv = EncoderKv(
            keys = Array(numLayers) { FloatArray(0) },
            values = Array(numLayers) { FloatArray(0) },
            seqLen = 0,
        )
        var decoderKv = DecoderKv(
            keys = Array(numLayers) { FloatArray(0) },
            values = Array(numLayers) { FloatArray(0) },
            seqLen = 0,
        )
        var logits = FloatArray(0)
        var isFirstStep = true

        for (token in tokens) {
            val result = decoderStep(env, token.toLong(), encoderKv, decoderKv, encoderOut, encoderSeqLen, isFirstStep)
            logits = result.logits
            if (isFirstStep) encoderKv = result.encoderKv
            decoderKv = result.decoderKv
            isFirstStep = false
        }

        return ForcedTokenResult(logits, encoderKv, decoderKv)
    }

    // ---- Beam search (temperature=0) ----

    private fun beamSearch(
        env: OrtEnvironment,
        encoderKv: EncoderKv,
        baseDecoderKv: DecoderKv,
        lastLogits: FloatArray,
        encoderOut: FloatArray,
        encoderSeqLen: Int,
        beamWidth: Int = 3,
        repetitionPenalty: Float = 1.2f,
    ): DecodeResult {
        val logProbs = logSoftmax(lastLogits)
        val topK = topKIndices(logProbs, beamWidth)

        data class CompletedBeam(val tokens: List<Int>, val score: Double)
        val completed = mutableListOf<CompletedBeam>()

        // Create initial beams from top-K first tokens
        val activeBeams = mutableListOf<Beam>()
        for (tokenIdx in topK) {
            if (tokenIdx == EOT) {
                completed.add(CompletedBeam(emptyList(), logProbs[tokenIdx].toDouble()))
                continue
            }
            val result = decoderStep(env, tokenIdx.toLong(), encoderKv, baseDecoderKv, encoderOut, encoderSeqLen, false)
            activeBeams.add(Beam(
                tokens = mutableListOf(tokenIdx),
                score = logProbs[tokenIdx].toDouble(),
                logits = result.logits,
                decoderKv = result.decoderKv,
            ))
        }

        // Beam search loop
        for (step in 0 until MAX_TOKENS) {
            if (activeBeams.isEmpty()) break

            data class Candidate(val beamIdx: Int, val token: Int, val score: Double)
            val candidates = mutableListOf<Candidate>()

            for ((beamIdx, beam) in activeBeams.withIndex()) {
                val beamLogits = beam.logits.copyOf()
                if (repetitionPenalty > 1.0f) applyRepetitionPenalty(beamLogits, beam.tokens, repetitionPenalty)
                val beamLogProbs = logSoftmax(beamLogits)
                val beamTopK = topKIndices(beamLogProbs, beamWidth)
                for (tok in beamTopK) {
                    candidates.add(Candidate(beamIdx, tok, beam.score + beamLogProbs[tok]))
                }
            }

            candidates.sortByDescending { it.score }

            // Collect EOT completions
            for (c in candidates) {
                if (c.token == EOT) {
                    completed.add(CompletedBeam(activeBeams[c.beamIdx].tokens.toList(), c.score))
                }
            }

            // Expand top non-EOT candidates into next active beams
            val nextBeams = mutableListOf<Beam>()
            for (c in candidates) {
                if (nextBeams.size >= beamWidth) break
                if (c.token == EOT) continue
                val parentBeam = activeBeams[c.beamIdx]
                val result = decoderStep(env, c.token.toLong(), encoderKv, parentBeam.decoderKv, encoderOut, encoderSeqLen, false)
                val newTokens = parentBeam.tokens.toMutableList().apply { add(c.token) }
                nextBeams.add(Beam(
                    tokens = newTokens,
                    score = c.score,
                    logits = result.logits,
                    decoderKv = result.decoderKv,
                ))
            }

            activeBeams.clear()
            activeBeams.addAll(nextBeams)
        }

        // Add remaining active beams as completed (didn't produce EOT within MAX_TOKENS)
        for (beam in activeBeams) {
            completed.add(CompletedBeam(beam.tokens.toList(), beam.score))
        }

        if (completed.isEmpty()) return DecodeResult(IntArray(0), 0.0)

        // Select best by length-normalized score
        val best = completed.maxByOrNull { it.score / it.tokens.size.coerceAtLeast(1) }!!
        val avgLogProb = best.score / best.tokens.size.coerceAtLeast(1)
        return DecodeResult(best.tokens.toIntArray(), avgLogProb)
    }

    // ---- Greedy decode (argmax) ----

    private fun greedyDecode(
        env: OrtEnvironment,
        encoderKv: EncoderKv,
        baseDecoderKv: DecoderKv,
        lastLogits: FloatArray,
        encoderOut: FloatArray,
        encoderSeqLen: Int,
        repetitionPenalty: Float = 1.2f,
    ): DecodeResult {
        val generated = mutableListOf<Int>()
        var logits = lastLogits
        var decoderKv = baseDecoderKv
        var sumLogProb = 0.0

        while (generated.size < MAX_TOKENS) {
            if (repetitionPenalty > 1.0f) applyRepetitionPenalty(logits, generated, repetitionPenalty)
            val lp = logSoftmax(logits)
            val token = argmax(logits)
            if (token == EOT) break
            sumLogProb += lp[token]
            generated.add(token)
            val result = decoderStep(env, token.toLong(), encoderKv, decoderKv, encoderOut, encoderSeqLen, false)
            logits = result.logits
            decoderKv = result.decoderKv
        }

        val avgLogProb = if (generated.isNotEmpty()) sumLogProb / generated.size else 0.0
        return DecodeResult(generated.toIntArray(), avgLogProb)
    }

    // ---- Decoder step (split KV) ----

    private fun decoderStep(
        env: OrtEnvironment,
        token: Long,
        encoderKv: EncoderKv,
        decoderKv: DecoderKv,
        encoderOut: FloatArray,
        encoderSeqLen: Int,
        isFirstStep: Boolean,
    ): StepResult {
        val inputs = mutableMapOf<String, OnnxTensor>()
        val tensorsToClose = mutableListOf<OnnxTensor>()

        fun addInput(name: String, tensor: OnnxTensor) {
            inputs[name] = tensor
            tensorsToClose.add(tensor)
        }

        fun kvTensor(data: FloatArray, seqLen: Long): OnnxTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data),
            longArrayOf(1, numHeads.toLong(), seqLen, headDim.toLong()),
        )

        try {
            addInput("input_ids", OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1),
            ))

            val encShape = longArrayOf(1, encoderSeqLen.toLong(), dModel.toLong())
            addInput("encoder_hidden_states",
                OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderOut), encShape))

            addInput("use_cache_branch",
                OnnxTensor.createTensor(env, booleanArrayOf(!isFirstStep)))

            for (i in 0 until numLayers) {
                if (isFirstStep) {
                    val empty = FloatArray(0)
                    addInput("past_key_values.$i.decoder.key", kvTensor(empty, 0))
                    addInput("past_key_values.$i.decoder.value", kvTensor(empty, 0))
                    addInput("past_key_values.$i.encoder.key", kvTensor(empty, 0))
                    addInput("past_key_values.$i.encoder.value", kvTensor(empty, 0))
                } else {
                    val decSeqLen = decoderKv.seqLen.toLong()
                    val encKvSeqLen = encoderKv.seqLen.toLong()
                    addInput("past_key_values.$i.decoder.key", kvTensor(decoderKv.keys[i], decSeqLen))
                    addInput("past_key_values.$i.decoder.value", kvTensor(decoderKv.values[i], decSeqLen))
                    addInput("past_key_values.$i.encoder.key", kvTensor(encoderKv.keys[i], encKvSeqLen))
                    addInput("past_key_values.$i.encoder.value", kvTensor(encoderKv.values[i], encKvSeqLen))
                }
            }

            val session = decoderSession
                ?: throw IllegalStateException("Decoder session closed during transcription")
            session.run(inputs).use { result ->
                val logits = flattenFloat(result.get("logits").get() as OnnxTensor)

                val newDecKeys = Array(numLayers) { i ->
                    flattenFloat(result.get("present.$i.decoder.key").get() as OnnxTensor)
                }
                val newDecValues = Array(numLayers) { i ->
                    flattenFloat(result.get("present.$i.decoder.value").get() as OnnxTensor)
                }

                // Encoder KV: only populated on first step. On subsequent steps reuse input.
                val newEncKv: EncoderKv
                if (isFirstStep) {
                    val encKeys = Array(numLayers) { i ->
                        flattenFloat(result.get("present.$i.encoder.key").get() as OnnxTensor)
                    }
                    val encValues = Array(numLayers) { i ->
                        flattenFloat(result.get("present.$i.encoder.value").get() as OnnxTensor)
                    }
                    newEncKv = EncoderKv(encKeys, encValues, seqLenFromFlat(encKeys[0]))
                } else {
                    newEncKv = encoderKv
                }

                val newDecKv = DecoderKv(newDecKeys, newDecValues, seqLenFromFlat(newDecKeys[0]))
                return StepResult(logits, newEncKv, newDecKv)
            }
        } finally {
            tensorsToClose.forEach { it.close() }
        }
    }

    // ---- Token helpers ----

    private fun applyRepetitionPenalty(logits: FloatArray, generatedTokens: List<Int>, penalty: Float) {
        for (token in generatedTokens) {
            if (token < logits.size) {
                if (logits[token] > 0) logits[token] /= penalty
                else logits[token] *= penalty
            }
        }
    }

    // ---- Math helpers ----

    private fun logSoftmax(logits: FloatArray): FloatArray {
        val n = logits.size.coerceAtMost(VOCAB_SIZE)
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in 0 until n) if (logits[i] > maxVal) maxVal = logits[i]
        var sumExp = 0.0
        for (i in 0 until n) sumExp += exp((logits[i] - maxVal).toDouble())
        val logSumExp = (ln(sumExp) + maxVal).toFloat()
        return FloatArray(n) { logits[it] - logSumExp }
    }

    private fun topKIndices(values: FloatArray, k: Int): IntArray {
        val n = values.size.coerceAtMost(VOCAB_SIZE)
        val indices = IntArray(k) { -1 }
        val scores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            if (values[i] > scores[k - 1]) {
                var pos = k - 1
                while (pos > 0 && values[i] > scores[pos - 1]) pos--
                for (j in k - 1 downTo pos + 1) {
                    indices[j] = indices[j - 1]
                    scores[j] = scores[j - 1]
                }
                indices[pos] = i
                scores[pos] = values[i]
            }
        }
        return indices
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        for (i in 1 until logits.size.coerceAtMost(VOCAB_SIZE)) {
            if (logits[i] > logits[best]) best = i
        }
        return best
    }

    private fun seqLenFromFlat(data: FloatArray): Int =
        if (data.isNotEmpty()) data.size / (numHeads * headDim) else 0

    private fun flattenFloat(tensor: OnnxTensor): FloatArray {
        // Use byteBuffer (direct native view) instead of floatBuffer (heap copy)
        // to avoid doubling memory — floatBuffer allocates a full heap FloatBuffer
        val buf = tensor.byteBuffer.asFloatBuffer()
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    fun close() {
        sessionLock.withLock {
            encoderSession?.close()
            decoderSession?.close()
            encoderSession = null
            decoderSession = null
            env = null
        }
    }
}
