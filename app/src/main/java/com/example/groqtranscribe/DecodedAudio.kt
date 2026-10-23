package com.example.groqtranscribe

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** PCM은 디스크에 보관하고 분석 창만 메모리에 올린다. 소유자는 반드시 use로 정리한다. */
class DecodedAudio(val file: File, val sampleCount: Long) : Closeable {
    val durationSeconds get() = sampleCount / 16000.0
    fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        require(count <= 16000 * 130) { "PCM read must fit a bounded analysis window" }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(start * 4)
            val bytes = ByteArray(count * 4)
            input.readFully(bytes)
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            FloatArray(count) { buffer.float }
        }
    }
    override fun close() { file.delete() }
}

data class AnalysisWindow(val index: Int, val start: Long, val end: Long, val coreStart: Long, val coreEnd: Long)
fun analysisWindows(total: Long, coreSeconds: Int = 120, contextSeconds: Int = 2): List<AnalysisWindow> {
    require(total >= 0 && coreSeconds in 1..120 && contextSeconds in 0..5)
    val step = coreSeconds * 16000L
    return buildList {
        var start = 0L
        while (start < total) {
            val end = minOf(start + step, total)
            add(AnalysisWindow(size, maxOf(0, start - contextSeconds * 16000L), minOf(total, end + contextSeconds * 16000L), start, end))
            start = end
        }
    }
}
