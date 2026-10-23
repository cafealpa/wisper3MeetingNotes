package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test

class SpeakerEditTest {
    private val original = listOf(
        SpeakerUtterance(0.0, 1.0, 1, "첫 발화"),
        SpeakerUtterance(1.0, 2.0, 2, "다른 사람"),
        SpeakerUtterance(2.0, 3.0, 1, "다시 말함"),
        SpeakerUtterance(3.0, 4.0, null, "불명 하나"),
        SpeakerUtterance(4.0, 5.0, null, "불명 둘"))
    @Test fun singleChangePreservesOtherUtterances() {
        assertEquals(listOf(2, 2, 1, null, null), reassignUtterance(original, 0, 2).map { it.speaker })
    }
    @Test fun bulkChangePreservesTextTimesAndOrder() {
        val updated = reassignUtterance(original, 0, 2, true)
        assertEquals(listOf(2, 2, 2, null, null), updated.map { it.speaker })
        assertEquals(original, updated.mapIndexed { i, value -> value.copy(speaker = original[i].speaker, manualSpeaker = original[i].manualSpeaker) })
    }
    @Test fun supportsNewAndUnknownSpeakers() {
        assertEquals(listOf(null, 2, null, null, null), reassignUtterance(original, 0, null, true).map { it.speaker })
        assertEquals(listOf(1, 2, 1, 3, 3), reassignUtterance(original, 3, 3, true).map { it.speaker })
    }
    @Test fun invalidTargetDoesNotChangeAnything() {
        assertEquals(original, reassignUtterance(original, -1, 2, true))
        assertEquals(original, reassignUtterance(original, 0, 0, true))
    }
}
