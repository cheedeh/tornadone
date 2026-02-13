package com.tornado.gesture

data class SensorReading(
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val sensorType: Int,
)

class SensorDataBuffer(private val capacity: Int = 500) {

    private val buffer = ArrayDeque<SensorReading>(capacity)
    private val lock = Any()

    fun add(reading: SensorReading) {
        synchronized(lock) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(reading)
        }
    }

    fun snapshot(): List<SensorReading> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    val size: Int get() = synchronized(lock) { buffer.size }
}
