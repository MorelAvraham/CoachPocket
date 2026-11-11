package com.coachpocket.core.util

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al., CHI 2012): a low-pass whose cutoff rises with speed, so a
 * still signal is smooth and a moving one is not delayed. Also exposes the filtered derivative.
 */
class OneEuroFilter(
    private val minCutoffHz: Double = 1.5,
    private val beta: Double = 0.05,
    private val derivativeCutoffHz: Double = 1.5,
) {
    private var initialised = false
    private var lastT = 0.0
    private var x = 0.0

    /** Filtered rate of change, units per second. */
    var derivative = 0.0; private set

    fun reset() {
        initialised = false; derivative = 0.0
    }

    fun filter(value: Double, tSeconds: Double): Double {
        if (!initialised) {
            initialised = true; lastT = tSeconds; x = value; derivative = 0.0
            return value
        }
        val dt = tSeconds - lastT
        if (dt <= 0) return x
        lastT = tSeconds
        val rawDerivative = (value - x) / dt
        derivative += alpha(derivativeCutoffHz, dt) * (rawDerivative - derivative)
        val cutoff = minCutoffHz + beta * abs(derivative)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dt)
    }
}

/** Fixed-size window of the most recent values with percentile queries, for latency reporting. */
class RollingStats(private val capacity: Int = 256) {
    private val values = DoubleArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized
    fun add(v: Double) {
        values[next] = v
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    val size: Int @Synchronized get() = count

    @Synchronized
    fun percentile(p: Double): Double {
        if (count == 0) return Double.NaN
        val sorted = values.copyOf(count).also { it.sort() }
        val idx = ((p / 100.0) * (count - 1)).let { Math.round(it).toInt() }.coerceIn(0, count - 1)
        return sorted[idx]
    }

    @Synchronized
    fun mean(): Double = if (count == 0) Double.NaN else values.take(count).sum() / count

    @Synchronized
    fun clear() {
        count = 0; next = 0
    }
}
