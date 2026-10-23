package com.example.groqtranscribe

import kotlin.math.max
import kotlin.math.min

data class SpeakerTurn(val start: Double, val end: Double, val speaker: Int)
data class SpeakerUtterance(val start: Double, val end: Double, val speaker: Int?, val text: String, val diagnosis: String? = null, val contextCorrection: String? = null, val manualSpeaker: Boolean = false)

enum class UnknownGapMode(val label: String, val maxGapSeconds: Double) {
    OFF("끄기", 0.0), CONSERVATIVE("0.35초", 0.35), HALF_SECOND("0.5초", 0.5),
    AGGRESSIVE("0.8초", 0.8), ONE_SECOND("1.0초", 1.0);

    companion object {
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: CONSERVATIVE
    }
}

// 중복·겹친 분석 구간을 합집합으로 계산한다.
private fun coveredSeconds(turns: List<SpeakerTurn>, start: Double, end: Double): Double {
    var edge = start
    var total = 0.0
    for (turn in turns.sortedBy { it.start }) {
        val left = max(edge, max(start, turn.start))
        val right = min(end, turn.end)
        total += max(0.0, right - left)
        edge = max(edge, right)
    }
    return total
}

/** 음성 겹침과 모호한 경계는 불명으로 남긴다. 번호는 파일 내 첫 등장 순서다. */
fun alignSpeakers(words: List<Word>, turns: List<SpeakerTurn>, gapMode: UnknownGapMode = UnknownGapMode.CONSERVATIVE): List<SpeakerUtterance> {
    val sorted = turns.filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start }.sortedBy { it.start }
    val ids = sorted.map { it.speaker }.filter { it >= 0 }.distinct().withIndex().associate { it.value to it.index + 1 }
    val result = mutableListOf<SpeakerUtterance>()
    for (word in words.sortedBy { it.start }) {
        if (!word.start.isFinite() || !word.end.isFinite() || word.end <= word.start) continue
        val scores = sorted.filter { it.start < word.end && it.end > word.start }
            .groupBy { it.speaker }.mapValues { (_, intervals) ->
                // 같은 화자의 중첩 구간이 있더라도 시간을 중복 계산하지 않는다.
                var edge = word.start
                var coverage = 0.0
                for (interval in intervals) {
                    val start = max(edge, max(word.start, interval.start))
                    val end = min(word.end, interval.end)
                    coverage += max(0.0, end - start)
                    edge = max(edge, end)
                }
                coverage
            }.entries.sortedByDescending { it.value }
        val duration = word.end - word.start
        val best = scores.firstOrNull()
        val runnerUp = scores.getOrNull(1)?.value ?: 0.0
        val speaker = if (best != null && best.value / duration >= 0.5 && runnerUp / duration < 0.25)
            ids[best.key] else null
        val reasonCategory = if (speaker != null) null else when {
            best == null -> "화자 분석 구간 없음"
            best.key < 0 -> "음성 특징 부족으로 신원 미확정"
            runnerUp / duration >= 0.25 -> "다른 화자 비중이 25% 이상인 모호한 경계 또는 겹말"
            else -> "주 화자 시간 비중이 50% 미만"
        }
        val reason = reasonCategory?.let {
            val bestPercent = (best?.value ?: 0.0) / duration * 100
            val secondPercent = runnerUp / duration * 100
            "${timestamp(word.start)}–${timestamp(word.end)} 「${word.text.trim()}」: $it (최대 후보 ${String.format(java.util.Locale.ROOT, "%.1f", bestPercent)}%, 차순위 ${String.format(java.util.Locale.ROOT, "%.1f", secondPercent)}%)"
        }
        val previous = result.lastOrNull()
        if (previous != null && previous.speaker == speaker && word.start - previous.end <= 1.0) {
            result[result.lastIndex] = previous.copy(end = max(previous.end, word.end), text = previous.text + " " + word.text.trim(),
                diagnosis = listOfNotNull(previous.diagnosis, reason).flatMap { it.lines() }.distinct().joinToString("\n").ifEmpty { null })
        } else result += SpeakerUtterance(word.start, word.end, speaker, word.text.trim(), reason)
    }
    // 원래 배정만 근거로 삼아 연쇄 보정과 확실한 다른 화자의 병합을 막는다.
    val corrected = result.mapIndexed { index, value ->
        if (value.speaker != null) return@mapIndexed value
        val before = result.getOrNull(index - 1)
        val after = result.getOrNull(index + 1)
        val speaker = before?.speaker
        val start = before?.end ?: value.start
        val end = after?.start ?: value.end
        val gap = end - start
        val contained = value.start >= start && value.end <= end && gap > 0
        val relevant = sorted.filter { it.start < end && it.end > start }
        val others = relevant.filter { ids[it.speaker] != speaker }
        val conflict = coveredSeconds(others, start, end)
        // 80ms 미만이면서 불명 발화 길이의 20% 미만인 흔적만 허용한다.
        // 주변 침묵이 길다고 다른 화자 근거의 비중이 작아지지 않도록 발화 길이를 분모로 쓴다.
        val fraction = conflict / (value.end - value.start)
        val strongConflict = conflict >= 0.08 - 1e-9 || fraction >= 0.2 - 1e-9
        val overlapPieces = relevant.filter { ids[it.speaker] == speaker }.flatMap { own ->
            others.mapNotNull { other ->
                val left = max(own.start, other.start)
                val right = min(own.end, other.end)
                if (right > left) SpeakerTurn(left, right, 0) else null
            }
        }
        val overlap = coveredSeconds(overlapPieces, start, end)
        val blocks = buildList {
            if (gapMode == UnknownGapMode.OFF) add("연결 옵션 꺼짐")
            if (before?.speaker == null || after?.speaker == null) add("앞뒤에 확정 화자가 모두 필요함")
            else if (before.speaker != after.speaker) add("앞뒤 화자가 다름")
            if (!contained) add("단어 시간 경계가 겹치거나 역전됨")
            if (gapMode != UnknownGapMode.OFF && gap > gapMode.maxGapSeconds + 1e-9) add("선택한 연결 길이 초과")
            if (strongConflict) add(if (overlap >= 0.08 - 1e-9 || overlap / (value.end - value.start) >= 0.2 - 1e-9)
                "겹말 근거 유지" else "다른 화자 또는 신원 미확정 음성 근거 유지")
        }
        // 문맥 보정은 불명 구간 전체와 양쪽 시간 경계를 함께 검사한다.
        val duration = value.end - value.start
        val contextStart = min(start, value.start)
        val contextEnd = max(end, value.end)
        val boundaryOverlap = max(0.0, start - value.start) + max(0.0, value.end - end)
        val contextTurns = sorted.filter { it.start < contextEnd && it.end > contextStart }
        val ownTurns = contextTurns.filter { ids[it.speaker] == speaker && it.speaker >= 0 }
        val unknownTurns = contextTurns.filter { it.speaker < 0 }
        val knownOthers = contextTurns.filter { it.speaker >= 0 && ids[it.speaker] != speaker }
        val ownFraction = coveredSeconds(ownTurns, value.start, value.end) / duration
        val unknownFraction = coveredSeconds(unknownTurns, contextStart, contextEnd) / duration
        val contextOverlap = coveredSeconds(ownTurns.flatMap { own ->
            (unknownTurns + knownOthers).mapNotNull { other ->
                val left = max(own.start, other.start)
                val right = min(own.end, other.end)
                if (right > left) SpeakerTurn(left, right, 0) else null
            }
        }, contextStart, contextEnd)
        val contextBlocks = buildList {
            if (gapMode == UnknownGapMode.OFF) add("연결 옵션 꺼짐")
            if (speaker == null || after?.speaker != speaker) add("앞뒤 확정 화자가 같지 않음")
            if (duration > 2.0 + 1e-9 || contextEnd - contextStart > 2.0 + 1e-9) add("문맥 범위 2초 초과")
            if (gap <= 0 || boundaryOverlap > 0.2 + 1e-9 ||
                (before != null && value.start < before.start) || (after != null && value.end > after.end)) add("경계 겹침 허용 범위 초과")
            if (ownFraction < 0.5 - 1e-9) add("주변 화자의 전체 구간 비중 50% 미만")
            if (unknownFraction > 0.2 + 1e-9) add("신원 미확정 비중 20% 초과")
            if (coveredSeconds(knownOthers, contextStart, contextEnd) > 0) add("다른 확정 화자 근거 있음")
            if (contextOverlap >= 0.08 - 1e-9 || contextOverlap / duration >= 0.2 - 1e-9) add("겹말 근거 유지")
        }
        if (blocks.isEmpty()) value.copy(speaker = speaker, diagnosis = null)
        else {
            fun seconds(number: Double) = String.format(java.util.Locale.ROOT, "%.3f초", number)
            val coverage = sorted.groupBy { it.speaker }.mapNotNull { (id, regions) ->
                val amount = coveredSeconds(regions, value.start, value.end)
                if (amount <= 0) null else "${if (id < 0) "신원 미확정" else "화자 ${ids[id]}"}: ${seconds(amount)} (${String.format(java.util.Locale.ROOT, "%.0f", amount / (value.end - value.start) * 100)}%)"
            }.joinToString(" / ").ifEmpty { "겹치는 분석 구간 없음" }
            val details = "단어별 불명 판정:\n${value.diagnosis.orEmpty()}\n\n전체 구간 기준 (단어별 판정과 별도):\n짧은 구간 연결 차단: ${blocks.joinToString(", ")}\n불명 발화: ${seconds(value.end - value.start)} · 앞뒤 간격: ${seconds(gap)} · 설정: ${gapMode.label}\n시간 비중: $coverage\n다른 화자·미확정 흔적: ${seconds(conflict)} · 동시 겹침: ${seconds(overlap)}\n미세 흔적 허용: 누적 0.080초 미만이며 불명 발화의 20% 미만\n분석 시점의 화자 번호이며, 비율은 신뢰도가 아닌 시간 비중이에요.\n문맥 보정: ${if (contextBlocks.isEmpty()) "적용 가능" else contextBlocks.joinToString(", ")}"
            if (contextBlocks.isEmpty()) value.copy(speaker = speaker, diagnosis = details,
                contextCorrection = "문맥으로 화자 $speaker 에 연결됨 · ${timestamp(value.start)}–${timestamp(value.end)}\n앞뒤 화자 일치 · 전체 구간 화자 비중 ${String.format(java.util.Locale.ROOT, "%.1f", ownFraction * 100)}% · 신원 미확정 ${String.format(java.util.Locale.ROOT, "%.1f", unknownFraction * 100)}% · 경계 겹침 ${seconds(boundaryOverlap)}\n자동 추정 결과예요. 음성을 듣고 화자를 수정할 수 있어요.")
            else value.copy(diagnosis = details)
        }
    }
    return buildList {
        for (value in corrected) {
            val previous = lastOrNull()
            if (previous != null && previous.contextCorrection == null && value.contextCorrection == null && previous.speaker == value.speaker && value.start - previous.end <= 1.0) {
                this[lastIndex] = previous.copy(end = max(previous.end, value.end), text = previous.text + " " + value.text)
            } else add(value)
        }
    }
}

fun speakerLabel(speaker: Int?, names: Map<Int, String> = emptyMap()) = speaker?.let { names[it] ?: "화자 $it" } ?: "화자 불명"
fun speakerCopyText(utterances: List<SpeakerUtterance>, names: Map<Int, String> = emptyMap()) = utterances.joinToString("\n\n") {
    "${speakerLabel(it.speaker, names)} · ${timestamp(it.start)}–${timestamp(it.end)}\n${it.text}"
}

fun renameSpeaker(names: Map<Int, String>, id: Int, name: String): Map<Int, String> {
    if (id <= 0) return names
    val clean = name.replace('\n', ' ').replace('\r', ' ').trim().take(40)
    return if (clean.isEmpty()) names - id else names + (id to clean)
}
fun reassignUtterance(utterances: List<SpeakerUtterance>, index: Int, speaker: Int?, allMatching: Boolean = false): List<SpeakerUtterance> {
    if (index !in utterances.indices || (speaker != null && speaker <= 0)) return utterances
    return utterances.mapIndexed { i, value -> if (i == index || (allMatching && value.speaker == utterances[index].speaker)) value.copy(speaker = speaker, manualSpeaker = true, contextCorrection = null, diagnosis = if (speaker == null) "직접 화자 불명으로 변경한 발화예요. 자동 연결은 다시 분석할 때 적용돼요." else null) else value }
}
