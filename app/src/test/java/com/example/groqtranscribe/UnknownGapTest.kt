package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test

class UnknownGapTest {
    private fun words(length: Double) = listOf(Word(0.0, 1.0, "내가"), Word(1.0, 1.0 + length, "은호"), Word(1.0 + length, 2.0, "랑"))
    private fun turns(length: Double) = listOf(SpeakerTurn(0.0, 1.0, 4), SpeakerTurn(1.0 + length, 2.0, 4))
    @Test fun offPreservesUnknownAndDefaultBridgesShortGap() {
        assertEquals(listOf(1, null, 1), alignSpeakers(words(0.2), turns(0.2), UnknownGapMode.OFF).map { it.speaker })
        val joined = alignSpeakers(words(0.2), turns(0.2)).single()
        assertEquals(SpeakerUtterance(0.0, 2.0, 1, "내가 은호 랑"), joined)
    }
    @Test fun addedDurationsBridgeOnlyWithinTheirLimit() {
        assertEquals(3, alignSpeakers(words(0.45), turns(0.45), UnknownGapMode.CONSERVATIVE).size)
        assertEquals(1, alignSpeakers(words(0.45), turns(0.45), UnknownGapMode.HALF_SECOND).size)
        assertEquals(3, alignSpeakers(words(0.6), turns(0.6), UnknownGapMode.HALF_SECOND).size)
        assertEquals(1, alignSpeakers(words(0.9), turns(0.9), UnknownGapMode.ONE_SECOND).size)
    }
    @Test fun distanceThresholdMapsToGlobalSimilarity() {
        val prototypes = listOf(
            SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 3.0),
            SpeakerPrototype(1, 0, floatArrayOf(0.6f, 0.8f), 3.0))
        val strict = globalSpeakerIds(prototypes, 0, SpeakerClustering.STRICT.similarity)
        val loose = globalSpeakerIds(prototypes, 0, SpeakerClustering.LOOSE.similarity)
        assertNotEquals(strict[0], strict[1])
        assertEquals(loose[0], loose[1])
        assertEquals(0.5, SpeakerClustering.DEFAULT.distance, 0.0)
        assertEquals(SpeakerClustering.DEFAULT, SpeakerClustering.fromStored(""))
        assertEquals(SpeakerClustering.DEFAULT, SpeakerClustering.fromStored("future"))
        for (value in SpeakerClustering.entries) assertEquals(value, SpeakerClustering.fromStored(value.name))
    }
    @Test fun aggressiveOnlyExtendsDuration() {
        assertEquals(3, alignSpeakers(words(0.6), turns(0.6)).size)
        assertEquals(1, alignSpeakers(words(0.6), turns(0.6), UnknownGapMode.AGGRESSIVE).size)
        assertEquals(3, alignSpeakers(words(0.9), turns(0.9), UnknownGapMode.AGGRESSIVE).size)
    }
    @Test fun overlapAndUnidentifiedVoiceAreNeverBridged() {
        for (mode in UnknownGapMode.entries) {
            for (other in listOf(7, -1)) {
                val evidence = turns(0.2) + SpeakerTurn(1.0, 1.2, 4) + SpeakerTurn(1.0, 1.1, other)
                assertEquals(listOf(1, null, 1), alignSpeakers(words(0.2), evidence, mode).map { it.speaker })
            }
        }
    }
    @Test fun definiteOtherSpeakerRemainsEvenIfVeryShort() {
        val evidence = turns(0.2) + SpeakerTurn(1.0, 1.2, 7)
        assertEquals(listOf(1, 2, 1), alignSpeakers(words(0.2), evidence, UnknownGapMode.AGGRESSIVE).map { it.speaker })
    }
    @Test fun tinyConflictingEvidenceCanBeBridged() {
        assertEquals(listOf(1), alignSpeakers(words(0.2), turns(0.2) + SpeakerTurn(1.05, 1.06, 7), UnknownGapMode.AGGRESSIVE).map { it.speaker })
    }
    @Test fun diagnosticsExplainDisabledAndDurationBlocks() {
        val disabled = alignSpeakers(words(0.2), turns(0.2), UnknownGapMode.OFF)[1].diagnosis!!
        assertTrue(disabled.contains("화자 분석 구간 없음"))
        assertTrue(disabled.contains("연결 옵션 꺼짐"))
        assertTrue(alignSpeakers(words(0.6), turns(0.6))[1].diagnosis!!.contains("연결 길이 초과"))
    }
    @Test fun manyTinyConflictsAccumulateAndDuplicateIntervalsDoNot() {
        val repeated = turns(0.2) + List(20) { SpeakerTurn(1.05, 1.06, 7) }
        assertEquals(1, alignSpeakers(words(0.2), repeated).size)
        val cumulative = turns(0.2) + listOf(SpeakerTurn(1.0, 1.03, 7), SpeakerTurn(1.1, 1.13, 7))
        assertNull(alignSpeakers(words(0.2), cumulative)[1].speaker)
    }
    @Test fun shortButDominantOverlapIsProtected() {
        val evidence = turns(0.1) + SpeakerTurn(1.0, 1.1, 4) + SpeakerTurn(1.02, 1.05, 7)
        val unknown = alignSpeakers(words(0.1), evidence)[1]
        assertNull(unknown.speaker)
        assertTrue(unknown.diagnosis!!.contains("겹말 근거 유지"))
        assertTrue(unknown.diagnosis!!.contains("시간 비중"))
    }
    @Test fun tinyUnidentifiedEvidenceBridgesButSubstantialEvidenceDoesNot() {
        assertEquals(1, alignSpeakers(words(0.2), turns(0.2) + SpeakerTurn(1.05, 1.06, -1)).size)
        val blocked = alignSpeakers(words(0.2), turns(0.2) + SpeakerTurn(1.0, 1.1, -1))[1]
        assertNull(blocked.speaker)
        assertTrue(blocked.diagnosis!!.contains("신원 미확정"))
    }
    @Test fun manualAssignmentsClearOldDiagnosis() {
        val unknown = alignSpeakers(words(0.2), turns(0.2), UnknownGapMode.OFF)
        assertNull(reassignUtterance(unknown, 1, 1)[1].diagnosis)
        assertTrue(reassignUtterance(unknown, 0, null)[0].diagnosis!!.contains("직접"))
    }
    @Test fun differentNeighborsAndEdgesRemainUnknown() {
        assertEquals(listOf(1, null, 2), alignSpeakers(words(0.2), listOf(SpeakerTurn(0.0, 1.0, 4), SpeakerTurn(1.2, 2.0, 7)), UnknownGapMode.AGGRESSIVE).map { it.speaker })
        assertEquals(listOf(null, 1), alignSpeakers(words(0.2), listOf(SpeakerTurn(1.2, 2.0, 4))).map { it.speaker })
    }
    @Test fun totalGapIncludesSilenceAndMultipleUnknownWords() {
        val text = listOf(Word(0.0, 1.0, "앞"), Word(1.4, 1.5, "중간"), Word(2.0, 3.0, "뒤"))
        assertEquals(3, alignSpeakers(text, listOf(SpeakerTurn(0.0, 1.0, 4), SpeakerTurn(2.0, 3.0, 4)), UnknownGapMode.AGGRESSIVE).size)
        val multi = listOf(Word(0.0, 1.0, "앞"), Word(1.0, 1.1, "중간1"), Word(1.1, 1.2, "중간2"), Word(1.2, 2.0, "뒤"))
        assertEquals("앞 중간1 중간2 뒤", alignSpeakers(multi, turns(0.2)).single().text)
    }
    @Test fun legacyAndUnknownOptionsDefaultToConservative() {
        assertEquals(UnknownGapMode.CONSERVATIVE, UnknownGapMode.fromStored(""))
        assertEquals(UnknownGapMode.CONSERVATIVE, UnknownGapMode.fromStored("future"))
        for (mode in UnknownGapMode.entries) assertEquals(mode, UnknownGapMode.fromStored(mode.name))
    }
}
