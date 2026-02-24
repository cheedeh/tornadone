package com.tornadone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MelSpectrogramTest {

    private val SAMPLE_RATE = 16000
    private val N_MELS = 80
    private val N_FRAMES = 3000
    private val SILENCE = FloatArray(SAMPLE_RATE * 30) { 0f }

    @Test
    fun `output has correct shape`() {
        val result = MelSpectrogram.compute(SILENCE)
        assertEquals(N_MELS * N_FRAMES, result.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on wrong input size`() {
        MelSpectrogram.compute(FloatArray(1000))
    }

    @Test
    fun `silence produces all-same finite values`() {
        val result = MelSpectrogram.compute(SILENCE)
        assertTrue(result.all { it.isFinite() })
        // All values identical for silence (uniform power → uniform log scale)
        val first = result[0]
        assertTrue(result.all { it == first })
    }

    @Test
    fun `silence output value matches expected log scale`() {
        // For silence: power=0 → log10(1e-10)=-10 → clamp → (-10+4)/4 = -1.5
        val result = MelSpectrogram.compute(SILENCE)
        assertEquals(-1.5f, result[0], 0.001f)
    }

    @Test
    fun `output is deterministic`() {
        val audio = FloatArray(SAMPLE_RATE * 30) { i -> (i % 100) / 100f }
        val result1 = MelSpectrogram.compute(audio)
        val result2 = MelSpectrogram.compute(audio)
        assertTrue(result1.zip(result2.toList()).all { (a, b) -> a == b })
    }

    @Test
    fun `non-silent audio differs from silence`() {
        val audio = FloatArray(SAMPLE_RATE * 30) { 0.5f }
        val silenceResult = MelSpectrogram.compute(SILENCE)
        val audioResult = MelSpectrogram.compute(audio)
        assertTrue(silenceResult.zip(audioResult.toList()).any { (a, b) -> a != b })
    }

    @Test
    fun `output values are in reasonable range after normalization`() {
        val audio = FloatArray(SAMPLE_RATE * 30) { i -> kotlin.math.sin(i * 0.01f) }
        val result = MelSpectrogram.compute(audio)
        // After Whisper normalization (val + 4) / 4, values should be roughly -1..2
        assertTrue(result.all { it >= -2f && it <= 3f })
    }
}
