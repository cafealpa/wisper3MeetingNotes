package com.example.groqtranscribe

import kotlin.math.max
import kotlin.math.min

/** 전사 단어는 재분석 전후 동일하다. 단어 소유권으로 수동 구간이 새 자동 구간에 흡수되는 것을 막는다. */
fun preserveSpeakerEdits(words: List<Word>, previous: List<SpeakerUtterance>, fresh: List<SpeakerUtterance>,
                         namedIds: Set<Int>): List<SpeakerUtterance> {
    fun overlap(a: Double, b: Double, c: Double, d: Double) = max(0.0, min(b, d) - max(a, c))
    fun owner(word: Word, items: List<SpeakerUtterance>): Int? = items.indices
        .filter { overlap(word.start, word.end, items[it].start, items[it].end) > 0 }
        .maxByOrNull { overlap(word.start, word.end, items[it].start, items[it].end) }
    val valid = words.filter { it.start.isFinite() && it.end.isFinite() && it.end > it.start }.sortedBy { it.start }
    val oldOwners = valid.map { owner(it, previous) }
    val newOwners = valid.map { owner(it, fresh) }
    if (previous.isNotEmpty() && previous.all { it.manualSpeaker }) return previous
    val totals = mutableMapOf<Int, Double>()
    val scores = mutableMapOf<Int, MutableMap<Int, Double>>()
    valid.forEachIndexed { i, word ->
        val oldId = oldOwners[i]?.let { previous[it].speaker }
        val newId = newOwners[i]?.let { fresh[it].speaker }
        if (newId != null) totals[newId] = (totals[newId] ?: 0.0) + word.end - word.start
        if (oldId != null && newId != null) {
            val row = scores.getOrPut(newId) { mutableMapOf() }
            row[oldId] = (row[oldId] ?: 0.0) + word.end - word.start
        }
    }
    val reserved = previous.mapNotNull { it.speaker }.toSet() + namedIds
    var next = (reserved.maxOrNull() ?: 0) + 1
    val mapping = mutableMapOf<Int, Int>()
    val used = mutableSetOf<Int>()
    // 강한 대응부터 일대일로 연결한다. 분할·병합으로 애매해진 군집은 기존 이름을 추측하지 않는다.
    val candidates = scores.mapNotNull { (newId, row) ->
        val ranked = row.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val total = totals.getValue(newId)
        if (best.value / total >= 0.6 && (best.value - (ranked.getOrNull(1)?.value ?: 0.0)) / total >= 0.2)
            Triple(newId, best.key, best.value) else null
    }.sortedByDescending { it.third }
    for ((newId, oldId) in candidates) if (used.add(oldId)) mapping[newId] = oldId
    fresh.mapNotNull { it.speaker }.distinct().forEach { id -> if (id !in mapping) mapping[id] = next++ }
    val result = mutableListOf<SpeakerUtterance>()
    val emittedManual = mutableSetOf<Int>()
    var lastFresh: Int? = null
    valid.forEachIndexed { i, word ->
        val oldIndex = oldOwners[i]
        val old = oldIndex?.let { previous[it] }
        if (old?.manualSpeaker == true) {
            if (emittedManual.add(oldIndex)) result += old
            lastFresh = null
        } else {
            val freshIndex = newOwners[i]
            val new = freshIndex?.let { fresh[it] } ?: SpeakerUtterance(word.start, word.end, null, word.text)
            val piece = new.copy(start = word.start, end = word.end, text = word.text.trim(),
                speaker = new.speaker?.let { mapping[it] })
            if (result.isNotEmpty() && lastFresh != null && lastFresh == freshIndex && !result.last().manualSpeaker) {
                val last = result.last()
                result[result.lastIndex] = last.copy(end = max(last.end, piece.end), text = last.text + " " + piece.text)
            } else result += piece
            lastFresh = freshIndex
        }
    }
    // 단어 시간과 일치하지 않는 이전 저장 구간도 수동 내용을 잃지 않도록 유지한다.
    if (previous.withIndex().any { (i, old) -> old.manualSpeaker && i !in emittedManual })
        throw UserError("수동 배정 구간과 단어 시간을 연결하지 못해 기존 결과를 유지했어.")
    return result.sortedBy { it.start }
}
