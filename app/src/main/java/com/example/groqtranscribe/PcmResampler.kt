package com.example.groqtranscribe

import kotlin.math.*

/** 청크 사이 필터/위상 상태를 유지하는 mono resampler. 다운샘플 전에 저역통과 필터 적용. */
class PcmResampler(private val inputRate: Int, private val output: (Float) -> Unit) {
    private val taps = 63
    private val history = FloatArray(taps)
    private var head = 0
    private var index = -1L
    private var previous = 0f
    private var nextPosition = 0.0
    private val step = inputRate / 16000.0
    private val coefficients = DoubleArray(taps) { i ->
        val x = i - (taps - 1) / 2.0
        val cutoff = min(0.45, 7200.0 / inputRate)
        val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
        sinc * (0.54 - 0.46 * cos(2 * PI * i / (taps - 1)))
    }.also { values -> val sum = values.sum(); for (i in values.indices) values[i] /= sum }

    init { require(inputRate > 0) }
    fun accept(value: Float) {
        history[head] = value
        var filtered = value
        if (inputRate > 16000) {
            var sum = 0.0
            for (i in coefficients.indices) sum += coefficients[i] * history[(head - i + taps) % taps]
            filtered = sum.toFloat()
        }
        head = (head + 1) % taps
        index++
        while (nextPosition <= index) {
            val fraction = (nextPosition - (index - 1)).coerceIn(0.0, 1.0)
            output((previous + (filtered - previous) * fraction).toFloat())
            nextPosition += step
        }
        previous = filtered
    }
}
