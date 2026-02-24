package com.tornadone.voice

import java.io.File
import java.io.RandomAccessFile

object WavWriter {
    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun write(file: File, audio: FloatArray) {
        val dataSize = audio.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(CHANNELS)
            raf.writeIntLE(SAMPLE_RATE)
            raf.writeIntLE(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
            raf.writeShortLE(CHANNELS * BITS_PER_SAMPLE / 8)
            raf.writeShortLE(BITS_PER_SAMPLE)
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
            for (sample in audio) {
                val s = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                raf.writeShortLE(s.toInt())
            }
        }
    }

    fun read(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            // Skip RIFF header (44 bytes)
            raf.seek(40)
            val dataSize = raf.readIntLE()
            val numSamples = dataSize / 2
            val samples = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                val s = raf.readShortLE()
                samples[i] = s.toFloat() / Short.MAX_VALUE
            }
            return samples
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun RandomAccessFile.readShortLE(): Short {
        val b0 = read()
        val b1 = read()
        return (b0 or (b1 shl 8)).toShort()
    }
}
