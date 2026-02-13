package com.tornado.gesture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gesture classifier using an ONNX model (StandardScaler + SVM pipeline).
 *
 * Collects a window of accelerometer data when motion is detected,
 * resamples to 100 points, extracts 52 features, and runs inference.
 * Labels: 0=m, 1=none, 2=o, 3=s, 4=z.
 */
@Singleton
class OnnxGestureClassifier @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class State { IDLE, COLLECTING }

    companion object {
        private const val N_POINTS = 100
        private const val OFFSET_THRESHOLD = 2.0f
        private const val QUIET_PERIOD_MS = 200L
        private const val MIN_SAMPLES = 20
        private const val Z_LABEL = 4L
        private val LABEL_NAMES = arrayOf("m", "none", "o", "s", "z")
    }

    var state: State = State.IDLE
        private set

    /** Acceleration magnitude to start collecting (m/s^2). */
    var onsetThreshold: Float = 4.0f

    /** Maximum collection window (ms). */
    var maxDurationMs: Long = 2000L

    /** Cooldown after a successful detection (ms). */
    var cooldownMs: Long = 2000L

    var onZDetected: ((Long) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null
    var onClassified: ((label: String, samples: Int, durationMs: Long) -> Unit)? = null

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val window = mutableListOf<Sample>()
    private var quietStartMs = 0L
    private var collectStartMs = 0L
    private var lastDetectionMs = 0L

    private data class Sample(val x: Float, val y: Float, val z: Float, val timestampMs: Long)

    init {
        val modelBytes = context.assets.open("gesture_classifier.onnx").readBytes()
        session = env.createSession(modelBytes)
    }

    fun onSensorData(x: Float, y: Float, z: Float, timestampMs: Long) {
        val mag = sqrt(x * x + y * y + z * z)

        if (lastDetectionMs > 0 && timestampMs - lastDetectionMs < cooldownMs) {
            if (state != State.IDLE) transition(State.IDLE)
            return
        }

        when (state) {
            State.IDLE -> {
                if (mag > onsetThreshold) {
                    window.clear()
                    window.add(Sample(x, y, z, timestampMs))
                    collectStartMs = timestampMs
                    quietStartMs = 0L
                    transition(State.COLLECTING)
                }
            }

            State.COLLECTING -> {
                window.add(Sample(x, y, z, timestampMs))

                if (mag < OFFSET_THRESHOLD) {
                    if (quietStartMs == 0L) quietStartMs = timestampMs
                    if (timestampMs - quietStartMs >= QUIET_PERIOD_MS) {
                        classifyAndHandle(timestampMs)
                        return
                    }
                } else {
                    quietStartMs = 0L
                }

                if (timestampMs - collectStartMs >= maxDurationMs) {
                    classifyAndHandle(timestampMs)
                }
            }
        }
    }

    fun reset() {
        state = State.IDLE
        window.clear()
        lastDetectionMs = 0L
        quietStartMs = 0L
    }

    // ---- internals ----

    private fun classifyAndHandle(timestampMs: Long) {
        if (window.size >= MIN_SAMPLES) {
            val durationMs = timestampMs - collectStartMs
            val label = classify()
            val labelName = LABEL_NAMES.getOrElse(label.toInt()) { "?" }
            onClassified?.invoke(labelName, window.size, durationMs)
            if (label == Z_LABEL) {
                lastDetectionMs = timestampMs
                onZDetected?.invoke(timestampMs)
            }
        }
        window.clear()
        transition(State.IDLE)
    }

    private fun classify(): Long {
        val timestamps = LongArray(window.size) { window[it].timestampMs }
        val xArr = FloatArray(window.size) { window[it].x }
        val yArr = FloatArray(window.size) { window[it].y }
        val zArr = FloatArray(window.size) { window[it].z }

        val xr = resample(timestamps, xArr, N_POINTS)
        val yr = resample(timestamps, yArr, N_POINTS)
        val zr = resample(timestamps, zArr, N_POINTS)

        val features = extractFeatures(xr, yr, zr)

        OnnxTensor.createTensor(env, arrayOf(features)).use { inputTensor ->
            session.run(mapOf("features" to inputTensor)).use { results ->
                val labelValue = results.get("output_label").get()
                return (labelValue.value as LongArray)[0]
            }
        }
    }

    private fun transition(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    // ---- feature extraction (mirrors features.py) ----

    private fun extractFeatures(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val n = x.size
        val axes = arrayOf(x, y, z)
        val features = mutableListOf<Float>()

        // Statistical features per axis (15)
        for (a in axes) {
            features.add(a.mean())
            features.add(a.populationStd())
            features.add(a.min())
            features.add(a.max())
            features.add(a.max() - a.min())
        }

        // Energy features (6)
        val energies = FloatArray(3) { i -> axes[i].sumOf { (it * it).toDouble() }.toFloat() }
        for (e in energies) features.add(e)

        val totalEnergy = energies.sum()
        if (totalEnergy > 0f) {
            for (e in energies) features.add(e / totalEnergy)
        } else {
            repeat(3) { features.add(1f / 3f) }
        }

        // Temporal features (9)
        for (a in axes) {
            features.add(zeroCrossings(a).toFloat())
            val absA = FloatArray(a.size) { abs(a[it]) }
            val peaks = findPeaks(absA, 1.0f)
            features.add(peaks.size.toFloat())
            features.add(if (peaks.isNotEmpty()) peaks[0].toFloat() / n else -1f)
        }

        // Shape features: correlations (3)
        features.add(correlation(x, y))
        features.add(correlation(x, z))
        features.add(correlation(y, z))

        // Direction reversals per axis (3)
        for (a in axes) features.add(directionReversals(a).toFloat())

        // Magnitude profile (4)
        val mag = FloatArray(n) { sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
        features.add(mag.max())
        features.add(mag.indexOfMax().toFloat() / n)
        features.add(mag.mean())
        features.add(mag.populationStd())

        // Segment-based features (12)
        val segSize = n / 3
        val segments = arrayOf(0 to segSize, segSize to 2 * segSize, 2 * segSize to n)
        for ((start, end) in segments) {
            val sx = x.sliceMean(start, end)
            val sy = y.sliceMean(start, end)
            val sz = z.sliceMean(start, end)
            features.add(sx)
            features.add(sy)
            features.add(sz)
            features.add(sqrt(sx * sx + sy * sy + sz * sz))
        }

        return features.toFloatArray()
    }

    // ---- helpers ----

    private fun resample(timestamps: LongArray, data: FloatArray, nPoints: Int): FloatArray {
        val n = data.size
        if (n < 2) return FloatArray(nPoints) { if (n == 1) data[0] else 0f }

        val tStart = timestamps[0].toDouble()
        val tEnd = timestamps[n - 1].toDouble()
        val duration = tEnd - tStart
        if (duration <= 0.0) return FloatArray(nPoints) { data[0] }

        val result = FloatArray(nPoints)
        var lo = 0
        for (i in 0 until nPoints) {
            val t = tStart + i.toDouble() / (nPoints - 1) * duration
            while (lo < n - 2 && timestamps[lo + 1] < t) lo++
            val segDur = (timestamps[lo + 1] - timestamps[lo]).toDouble()
            val frac = if (segDur > 0.0) ((t - timestamps[lo]) / segDur).toFloat() else 0f
            result[i] = data[lo] * (1f - frac) + data[lo + 1] * frac
        }
        return result
    }

    private fun FloatArray.mean(): Float {
        if (isEmpty()) return 0f
        return sum() / size
    }

    private fun FloatArray.populationStd(): Float {
        if (isEmpty()) return 0f
        val m = mean()
        val variance = sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / size
        return sqrt(variance)
    }

    private fun FloatArray.indexOfMax(): Int {
        if (isEmpty()) return 0
        var best = 0
        for (i in 1 until size) if (this[i] > this[best]) best = i
        return best
    }

    private fun FloatArray.sliceMean(from: Int, to: Int): Float {
        if (from >= to) return 0f
        var s = 0f
        for (i in from until to) s += this[i]
        return s / (to - from)
    }

    private fun sign(v: Float): Float = when {
        v > 0f -> 1f
        v < 0f -> -1f
        else -> 0f
    }

    private fun zeroCrossings(a: FloatArray): Int {
        var count = 0
        for (i in 1 until a.size) if (sign(a[i]) != sign(a[i - 1])) count++
        return count
    }

    private fun findPeaks(data: FloatArray, minHeight: Float): List<Int> {
        if (data.size < 3) return emptyList()
        val peaks = mutableListOf<Int>()
        for (i in 1 until data.size - 1) {
            if (data[i] > data[i - 1] && data[i] > data[i + 1] && data[i] >= minHeight) {
                peaks.add(i)
            }
        }
        return peaks
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val aStd = a.populationStd()
        val bStd = b.populationStd()
        if (aStd == 0f || bStd == 0f) return 0f
        val aMean = a.mean()
        val bMean = b.mean()
        var cov = 0.0
        for (i in a.indices) cov += (a[i] - aMean).toDouble() * (b[i] - bMean).toDouble()
        cov /= a.size
        return (cov / (aStd * bStd)).toFloat()
    }

    private fun directionReversals(a: FloatArray): Int {
        if (a.size < 3) return 0
        var count = 0
        var prev = sign(a[1] - a[0])
        for (i in 2 until a.size) {
            val cur = sign(a[i] - a[i - 1])
            if (cur != prev) count++
            prev = cur
        }
        return count
    }
}
