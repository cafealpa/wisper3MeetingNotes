package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test

class SpeakerPreservationTest {
    private val words = (0..3).map { Word(it.toDouble(), it + 1.0, "단어$it") }
    private val old = words.mapIndexed { i, w -> SpeakerUtterance(w.start, w.end, if (i < 2) 1 else 2, w.text) }
    @Test fun swappedIdsKeepExistingNamesAndAutomaticSegmentsRefresh() {
        val fresh = old.map { it.copy(speaker = if (it.speaker == 1) 2 else 1) }
        val result = preserveSpeakerEdits(words, old, fresh, setOf(1, 2))
        assertEquals(listOf(1, 1, 2, 2), result.map { it.speaker })
        assertTrue(result.none { it.manualSpeaker })
    }
    @Test fun manualUnknownSurvivesMergedAutomaticSegmentWithoutDuplicateText() {
        val edited = reassignUtterance(old, 1, null)
        val fresh = listOf(SpeakerUtterance(0.0, 4.0, 8, "단어0 단어1 단어2 단어3"))
        val result = preserveSpeakerEdits(words, edited, fresh, setOf(1, 2))
        assertEquals(edited[1], result.single { it.manualSpeaker })
        assertEquals(words.joinToString(" ") { it.text }, result.joinToString(" ") { it.text })
        assertEquals(result, preserveSpeakerEdits(words, result, fresh, setOf(1, 2)))
    }
    @Test fun newManualSpeakerAndBulkAssignmentsAreProtected() {
        val edited = reassignUtterance(old, 0, 9, true)
        val result = preserveSpeakerEdits(words, edited, old, setOf(1, 2, 9))
        assertEquals(edited.filter { it.manualSpeaker }, result.filter { it.manualSpeaker })
    }
    @Test fun ambiguousMergeDoesNotStealNamedIdentity() {
        val fresh = listOf(SpeakerUtterance(0.0, 4.0, 1, "전체"))
        assertEquals(3, preserveSpeakerEdits(words, old, fresh, setOf(1, 2)).single().speaker)
    }
    @Test fun protectedLegacySnapshotIsKeptExactly() {
        val legacy = old.map { it.copy(manualSpeaker = true) }
        assertEquals(legacy, preserveSpeakerEdits(words, legacy, emptyList(), setOf(1, 2)))
    }
}
