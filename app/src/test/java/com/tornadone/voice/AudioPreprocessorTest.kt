package com.tornadone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioPreprocessorTest {

    @Test
    fun `highPassFilter on empty array returns empty array`() {
        val result = AudioPreprocessor.highPassFilter(FloatArray(0))
        assertEquals(0, result.size)
    }

    @Test
    fun `highPassFilter preserves first sample`() {
        val input = floatArrayOf(0.5f, 0.3f, 0.8f)
        val result = AudioPreprocessor.highPassFilter(input)
        assertEquals(0.5f, result[0], 0.0001f)
    }

    @Test
    fun `highPassFilter on constant signal converges toward zero`() {
        // A high-pass filter removes DC offset; after many samples the output → 0
        val input = FloatArray(1000) { 1.0f }
        val result = AudioPreprocessor.highPassFilter(input)
        assertTrue("Expected last sample near 0, got ${result.last()}", abs(result.last()) < 0.01f)
    }

    @Test
    fun `normalizeGain on silence returns all-zero array`() {
        val silence = FloatArray(100) { 0f }
        val result = AudioPreprocessor.normalizeGain(silence)
        assertTrue(result.all { it == 0f })
    }

    @Test
    fun `normalizeGain scales peak to 0_9`() {
        val input = floatArrayOf(0.0f, 0.5f, 1.0f, 0.3f, -0.2f)
        val result = AudioPreprocessor.normalizeGain(input)
        val peak = result.map { abs(it) }.maxOrNull()!!
        assertEquals(0.9f, peak, 0.0001f)
    }

    @Test
    fun `normalizeGain on all-negative signal scales magnitude to 0_9`() {
        val input = floatArrayOf(-0.5f, -0.8f, -0.3f)
        val result = AudioPreprocessor.normalizeGain(input)
        val peak = result.map { abs(it) }.maxOrNull()!!
        assertEquals(0.9f, peak, 0.0001f)
    }
}
