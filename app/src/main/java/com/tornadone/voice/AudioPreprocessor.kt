package com.tornadone.voice

object AudioPreprocessor {

    fun highPassFilter(samples: FloatArray): FloatArray {
        val alpha = 0.969f // ~80Hz cutoff at 16kHz sample rate
        val result = FloatArray(samples.size)
        if (samples.isEmpty()) return result
        result[0] = samples[0]
        for (i in 1 until samples.size) {
            result[i] = alpha * (result[i - 1] + samples[i] - samples[i - 1])
        }
        return result
    }

    fun normalizeGain(samples: FloatArray): FloatArray {
        var peak = 0f
        for (s in samples) {
            val abs = if (s >= 0) s else -s
            if (abs > peak) peak = abs
        }
        if (peak < 0.001f) return samples // pure silence, skip
        val scale = 0.9f / peak
        return FloatArray(samples.size) { samples[it] * scale }
    }
}
