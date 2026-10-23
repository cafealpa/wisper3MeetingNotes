package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test

class ContextCorrectionTest {
    private val words = listOf(Word(0.0, 1.0, "앞"), Word(0.9, 1.3, "응"), Word(2.0, 2.6, "엉덩이"), Word(2.5, 3.5, "뒤"))
    private val turns = listOf(SpeakerTurn(0.0, 1.0, 4), SpeakerTurn(1.3, 1.9, 4),
        SpeakerTurn(2.4, 3.5, 4), SpeakerTurn(2.0, 2.236, -1))
    @Test fun contextCorrectsWholeSpanAndKeepsMarkerAndOriginalTimes() {
        val result = alignSpeakers(words, turns, UnknownGapMode.HALF_SECOND)
        assertEquals(3, result.size)
        assertEquals(listOf(1, 1, 1), result.map { it.speaker })
        val corrected = result[1]
        assertEquals(0.9, corrected.start, 0.0)
        assertEquals(2.6, corrected.end, 0.0)
        assertEquals("응 엉덩이", corrected.text)
        assertNotNull(corrected.contextCorrection)
        assertTrue(corrected.diagnosis!!.contains("단어별 불명 판정"))
        assertTrue(corrected.diagnosis!!.contains("전체 구간 기준"))
        assertTrue(corrected.diagnosis!!.contains("「응」"))
        assertNull(result[0].contextCorrection)
        assertNull(result[2].contextCorrection)
    }
    @Test fun offAndOtherKnownSpeakerBlockContext() {
        assertNull(alignSpeakers(words, turns, UnknownGapMode.OFF)[1].speaker)
        val blocked = alignSpeakers(words, turns + SpeakerTurn(1.5, 1.6, 7))[1]
        assertNull(blocked.speaker)
        assertTrue(blocked.diagnosis!!.contains("다른 확정 화자 근거 있음"))
    }
    @Test fun overlapAndTooMuchUnidentifiedVoiceBlockContext() {
        assertNull(alignSpeakers(words, turns + SpeakerTurn(1.4, 1.5, -1))[1].speaker)
        assertNull(alignSpeakers(words, turns + SpeakerTurn(2.0, 2.39, -1))[1].speaker)
    }
    @Test fun excessiveBoundaryOverlapAndLowCoverageBlockContext() {
        val changed = words.toMutableList().apply { this[0] = Word(0.0, 1.1, "앞") }
        assertNull(alignSpeakers(changed, turns)[1].speaker)
        assertNull(alignSpeakers(words, turns.filterNot { it.start == 1.3 })[1].speaker)
    }
    @Test fun longSpanAndDifferentNeighborsAreNotCorrected() {
        val longWords = words.map { it.copy(start = it.start * 1.3, end = it.end * 1.3) }
        val longTurns = turns.map { it.copy(start = it.start * 1.3, end = it.end * 1.3) }
        val longResult = alignSpeakers(longWords, longTurns)[1]
        assertNull(longResult.speaker)
        assertTrue(longResult.diagnosis!!.contains("문맥 범위 2초 초과"))
        val different = turns.map { if (it.start == 2.4) it.copy(speaker = 7) else it }
        assertNull(alignSpeakers(words, different)[1].speaker)
    }
    @Test fun manualAssignmentRemovesAutomaticMarker() {
        val result = alignSpeakers(words, turns)
        assertNull(reassignUtterance(result, 1, 2)[1].contextCorrection)
        assertNull(reassignUtterance(result, 1, 2)[1].diagnosis)
    }
}
