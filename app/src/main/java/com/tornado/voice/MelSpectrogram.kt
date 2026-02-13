package com.tornado.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure-Kotlin mel spectrogram matching OpenAI Whisper's preprocessing.
 * Replaces the Whisper_initializer.onnx session (which required onnxruntime-extensions).
 *
 * Pipeline: audio (16kHz, 30s padded) → STFT → power spectrum → mel filterbank → log scale
 * Output: FloatArray(80 * 3000) row-major for tensor shape [1, 80, 3000].
 */
object MelSpectrogram {

    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 400
    private const val HOP_LENGTH = 160
    private const val N_MELS = 80
    private const val N_FRAMES = 3000 // 30s * 16000 / 160
    private const val FREQ_BINS = N_FFT / 2 + 1 // 201 (matches Whisper's n_fft=400)

    private val hannWindow: FloatArray by lazy { buildHannWindow() }
    private val melFilters: Array<FloatArray> by lazy { buildMelFilterbank() }

    // Precomputed DFT twiddle factors for 400-point DFT (201 freq bins × 400 time samples)
    private val dftCos: Array<FloatArray> by lazy {
        Array(FREQ_BINS) { k ->
            FloatArray(N_FFT) { n ->
                cos(2.0 * PI * k * n / N_FFT).toFloat()
            }
        }
    }
    private val dftSin: Array<FloatArray> by lazy {
        Array(FREQ_BINS) { k ->
            FloatArray(N_FFT) { n ->
                sin(2.0 * PI * k * n / N_FFT).toFloat()
            }
        }
    }

    /**
     * Compute log-mel spectrogram from 30-second audio (480000 samples).
     * Audio should already be padded/truncated to exactly 480000 samples.
     */
    fun compute(audio: FloatArray): FloatArray {
        require(audio.size == SAMPLE_RATE * 30) {
            "Expected ${SAMPLE_RATE * 30} samples, got ${audio.size}"
        }

        // Pad audio with N_FFT/2 = 200 samples of reflection on each side (matching librosa)
        val padded = reflectPad(audio, N_FFT / 2)

        // STFT → power spectrum → mel → log
        val output = FloatArray(N_MELS * N_FRAMES)
        val magnitudes = FloatArray(FREQ_BINS)
        val windowed = FloatArray(N_FFT)

        for (frame in 0 until N_FRAMES) {
            val offset = frame * HOP_LENGTH

            // Apply Hann window
            for (i in 0 until N_FFT) {
                windowed[i] = padded[offset + i] * hannWindow[i]
            }

            // Power spectrum via 400-point DFT (only positive frequencies)
            for (k in 0 until FREQ_BINS) {
                var real = 0f
                var imag = 0f
                val cosRow = dftCos[k]
                val sinRow = dftSin[k]
                for (n in 0 until N_FFT) {
                    val s = windowed[n]
                    real += s * cosRow[n]
                    imag -= s * sinRow[n]
                }
                magnitudes[k] = real * real + imag * imag
            }

            // Apply mel filterbank
            for (mel in 0 until N_MELS) {
                var sum = 0f
                val filter = melFilters[mel]
                for (i in 0 until FREQ_BINS) {
                    sum += filter[i] * magnitudes[i]
                }
                output[mel * N_FRAMES + frame] = sum
            }
        }

        // Log scale with Whisper's normalization
        logScale(output)
        return output
    }

    private fun reflectPad(audio: FloatArray, pad: Int): FloatArray {
        val result = FloatArray(audio.size + 2 * pad)
        // Left reflection
        for (i in 0 until pad) {
            result[i] = audio[pad - i]
        }
        // Center
        audio.copyInto(result, pad)
        // Right reflection
        for (i in 0 until pad) {
            result[pad + audio.size + i] = audio[audio.size - 2 - i]
        }
        return result
    }

    private fun logScale(data: FloatArray) {
        // Whisper's log_mel_spectrogram normalization:
        // 1. log10(max(val, 1e-10))
        // 2. clamp to max - 8.0
        // 3. (val + 4.0) / 4.0
        var maxVal = -Float.MAX_VALUE
        for (i in data.indices) {
            data[i] = log10(max(data[i], 1e-10f))
            if (data[i] > maxVal) maxVal = data[i]
        }
        for (i in data.indices) {
            data[i] = max(data[i], maxVal - 8.0f)
            data[i] = (data[i] + 4.0f) / 4.0f
        }
    }

    // --- Hann window ---

    private fun buildHannWindow(): FloatArray {
        // Periodic Hann window: np.hanning(N_FFT + 1)[:-1]
        return FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat()
        }
    }

    // --- Mel filterbank (Slaney normalization, matching librosa/Whisper) ---

    private fun buildMelFilterbank(): Array<FloatArray> {
        val fMin = 0.0
        val fMax = 8000.0
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // N_MELS + 2 evenly spaced points in mel scale
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melMin + i * (melMax - melMin) / (N_MELS + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHz(melPoints[it]) }

        // Convert Hz to FFT bin indices (fractional), using n_fft=400
        val binPoints = DoubleArray(hzPoints.size) { hzPoints[it] * N_FFT / SAMPLE_RATE }

        return Array(N_MELS) { mel ->
            val filter = FloatArray(FREQ_BINS)
            val left = binPoints[mel]
            val center = binPoints[mel + 1]
            val right = binPoints[mel + 2]

            // Slaney normalization: 2 / (right_hz - left_hz)
            val enorm = 2.0 / (hzPoints[mel + 2] - hzPoints[mel])

            for (i in 0 until FREQ_BINS) {
                val bin = i.toDouble()
                filter[i] = when {
                    bin < left -> 0f
                    bin < center -> ((bin - left) / (center - left) * enorm).toFloat()
                    bin < right -> ((right - bin) / (right - center) * enorm).toFloat()
                    else -> 0f
                }
            }
            filter
        }
    }

    // Slaney mel scale (used by librosa and Whisper)
    private fun hzToMel(hz: Double): Double {
        val f_sp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / f_sp
        val logstep = ln(6.4) / 27.0
        return if (hz >= minLogHz) {
            minLogMel + ln(hz / minLogHz) / logstep
        } else {
            hz / f_sp
        }
    }

    private fun melToHz(mel: Double): Double {
        val f_sp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / f_sp
        val logstep = ln(6.4) / 27.0
        return if (mel >= minLogMel) {
            minLogHz * exp(logstep * (mel - minLogMel))
        } else {
            f_sp * mel
        }
    }
}
