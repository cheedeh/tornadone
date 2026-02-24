package com.tornadone.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDataBufferTest {

    private fun reading(t: Long) = SensorReading(t, t.toFloat(), 0f, 0f, 0)

    @Test
    fun `empty buffer has size 0`() {
        assertEquals(0, SensorDataBuffer().size)
    }

    @Test
    fun `empty buffer snapshot is empty`() {
        assertTrue(SensorDataBuffer().snapshot().isEmpty())
    }

    @Test
    fun `adding items increases size`() {
        val buf = SensorDataBuffer()
        buf.add(reading(1))
        buf.add(reading(2))
        assertEquals(2, buf.size)
    }

    @Test
    fun `snapshot preserves insertion order`() {
        val buf = SensorDataBuffer()
        buf.add(reading(10))
        buf.add(reading(20))
        buf.add(reading(30))
        val snap = buf.snapshot()
        assertEquals(listOf(10L, 20L, 30L), snap.map { it.timestamp })
    }

    @Test
    fun `size capped at capacity`() {
        val buf = SensorDataBuffer(capacity = 3)
        repeat(10) { buf.add(reading(it.toLong())) }
        assertEquals(3, buf.size)
    }

    @Test
    fun `oldest entry dropped on overflow`() {
        val buf = SensorDataBuffer(capacity = 3)
        buf.add(reading(1))
        buf.add(reading(2))
        buf.add(reading(3))
        buf.add(reading(4)) // evicts 1
        val snap = buf.snapshot()
        assertEquals(listOf(2L, 3L, 4L), snap.map { it.timestamp })
    }

    @Test
    fun `clear resets size to 0`() {
        val buf = SensorDataBuffer()
        buf.add(reading(1))
        buf.add(reading(2))
        buf.clear()
        assertEquals(0, buf.size)
        assertTrue(buf.snapshot().isEmpty())
    }

    @Test
    fun `snapshot returns a copy`() {
        val buf = SensorDataBuffer()
        buf.add(reading(1))
        val snap = buf.snapshot().toMutableList()
        buf.add(reading(2))
        // Original snapshot should not see new element
        assertEquals(1, snap.size)
        assertEquals(2, buf.size)
    }

    @Test
    fun `exactly at capacity does not drop entries`() {
        val buf = SensorDataBuffer(capacity = 5)
        repeat(5) { buf.add(reading(it.toLong())) }
        assertEquals(5, buf.size)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), buf.snapshot().map { it.timestamp })
    }
}
