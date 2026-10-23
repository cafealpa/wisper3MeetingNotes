package com.example.groqtranscribe

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StepStatus { WAITING, RUNNING, COMPLETE, SKIPPED, FAILED, CANCELLED }

/** 병렬로 진행되는 전사와 로컬 분석을 독립적으로 추적한다. */
data class MeetingProgress(
    val running: Boolean = true,
    val transcript: StepStatus = StepStatus.RUNNING,
    val speakers: StepStatus = StepStatus.WAITING,
    val alignment: StepStatus = StepStatus.WAITING,
    val percent: Int? = null,
    val completedWindows: Int = 0,
    val totalWindows: Int = 0
) {
    fun finished(cancelled: Boolean): MeetingProgress {
        val terminal = if (cancelled) StepStatus.CANCELLED else StepStatus.FAILED
        fun finish(value: StepStatus) = if (value == StepStatus.RUNNING || value == StepStatus.WAITING) terminal else value
        return copy(running = false, transcript = finish(transcript), speakers = finish(speakers), alignment = finish(alignment))
    }
}

fun meetingTitle(filename: String?): String = filename.orEmpty().substringBeforeLast('.', filename.orEmpty())
    .replace('_', ' ').trim().ifBlank { "대화 기록" }

fun compactTimestamp(seconds: Double): String {
    val total = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0)?.toLong() ?: 0
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}

fun meetingDate(epochMillis: Long): String? = epochMillis.takeIf { it > 0 }?.let {
    DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREA).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
}

fun meetingDuration(seconds: Double?): String? = seconds?.takeIf { it.isFinite() && it > 0 }?.toLong()?.let {
    if (it >= 3600) "${it / 3600}시간 ${it / 60 % 60}분 ${it % 60}초" else "${it / 60}분 ${it % 60}초"
}
