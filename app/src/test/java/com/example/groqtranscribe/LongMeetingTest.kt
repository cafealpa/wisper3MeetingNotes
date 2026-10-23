package com.example.groqtranscribe

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataOutputStream

class LongMeetingTest {
    @Test fun briefOverlapDoesNotDiscardTheEntireSpeakerSample() {
        val regions = cleanSpeakerRegions(listOf(SpeakerTurn(0.0, 6.0, 0), SpeakerTurn(2.0, 2.5, 1)), 0)
        assertEquals(listOf(2.5 to 6.0, 0.0 to 2.0), regions)
        assertEquals(5.5, regions.sumOf { it.second - it.first }, 0.0)
    }
    @Test fun swappedLocalNumbersStillIdentifyTheSamePerson() {
        val ids = globalSpeakerIds(listOf(
            SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 5.0),
            SpeakerPrototype(0, 1, floatArrayOf(0f, 1f), 5.0),
            SpeakerPrototype(1, 1, floatArrayOf(0.99f, 0.01f), 5.0),
            SpeakerPrototype(1, 0, floatArrayOf(0.01f, 0.99f), 5.0)), 0)
        assertEquals(ids[0], ids[2]); assertEquals(ids[1], ids[3]); assertNotEquals(ids[0], ids[1])
    }
    @Test fun speakerReturningAfterSilentChunksUsesTheOriginalIdentity() {
        val ids = globalSpeakerIds(listOf(
            SpeakerPrototype(0, 5, floatArrayOf(1f, 0f, 0f), 5.0),
            SpeakerPrototype(1, 5, floatArrayOf(0f, 1f, 0f), 5.0),
            SpeakerPrototype(20, 8, floatArrayOf(1f, 0f, 0f), 5.0),
            SpeakerPrototype(21, 5, floatArrayOf(0f, 0f, 1f), 5.0)), 0)
        assertEquals(ids[0], ids[2]); assertEquals(3, ids.distinct().size)
    }
    @Test fun simultaneousSpeakersAreNotMergedInAutomaticMode() {
        val ids = globalSpeakerIds(listOf(
            SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 2.0, setOf(1)),
            SpeakerPrototype(0, 1, floatArrayOf(1f, 0.01f), 2.0, setOf(0))), 0)
        assertNotEquals(ids[0], ids[1])
    }
    @Test fun manualCountOverridesNoisyOverlapConstraints() {
        val prototypes = (0 until 46).map { index ->
            SpeakerPrototype(0, index, if (index % 2 == 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f),
                3.0, (0 until 46).filter { it != index }.toSet())
        }
        val ids = globalSpeakerIds(prototypes, 2)
        assertEquals(2, ids.distinct().size)
        ids.forEachIndexed { index, id -> assertEquals(ids[index % 2], id) }
    }
    @Test fun briefFragmentsDoNotCreateDozensOfSpeakers() {
        val prototypes = listOf(SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 10.0),
            SpeakerPrototype(0, 1, floatArrayOf(0f, 1f), 10.0)) + (2 until 46).map {
            SpeakerPrototype(1, it, if (it % 2 == 0) floatArrayOf(1f, 0.05f) else floatArrayOf(0.05f, 1f), 0.5)
        }
        val ids = globalSpeakerIds(prototypes, 0)
        assertEquals(2, ids.distinct().size)
        ids.forEachIndexed { index, id -> assertEquals(ids[index % 2], id) }
    }
    @Test fun ambiguousShortSpeechCannotInventAnIdentity() {
        val ids = globalSpeakerIds(listOf(
            SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 10.0),
            SpeakerPrototype(0, 1, floatArrayOf(0f, 1f), 10.0),
            SpeakerPrototype(1, 2, floatArrayOf(1f, 1f), 0.5)), 0)
        assertEquals(-1, ids[2])
    }
    @Test fun fragmentedSpeakerWithinTheSameChunkCanBeReunited() {
        val ids = globalSpeakerIds(listOf(
            SpeakerPrototype(0, 0, floatArrayOf(1f, 0f), 5.0),
            SpeakerPrototype(0, 2, floatArrayOf(0.99f, 0.01f), 3.0),
            SpeakerPrototype(1, 1, floatArrayOf(1f, 0f), 5.0)), 0)
        assertEquals(1, ids.distinct().size)
    }
    @Test fun invalidVectorsRemainUnknown() {
        val ids = globalSpeakerIds(listOf(SpeakerPrototype(0, 0, floatArrayOf(Float.NaN), 1.0)), 0)
        assertEquals(-1, ids.single())
    }
    @Test fun chunkCoresCoverLongAudioExactlyOnceAndReadsAreBounded() {
        val total = 16000L * 3600 + 123
        val windows = analysisWindows(total)
        assertEquals(0L, windows.first().coreStart)
        assertEquals(total, windows.last().coreEnd)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.coreEnd, b.coreStart); assertTrue(a.end > b.start) }
        assertTrue(windows.all { it.end - it.start <= 124 * 16000 })
        assertEquals(total, windows.sumOf { it.coreEnd - it.coreStart })
    }
    @Test fun pcmRandomReadsAndCleanupAreCorrect() {
        val file = File.createTempFile("pcm", ".bin")
        DataOutputStream(file.outputStream()).use { out -> repeat(20) { out.writeFloat(it.toFloat()) } }
        DecodedAudio(file, 20).use { pcm -> assertArrayEquals(floatArrayOf(10f, 11f, 12f), pcm.read(10, 3), 0f) }
        assertFalse(file.exists())
    }
    @Test fun renamingAndReassignmentAffectCopyWithoutChangingTextOrTime() {
        val original = listOf(SpeakerUtterance(1.0, 3.0, 1, "안녕하세요"), SpeakerUtterance(5.0, 6.0, 1, "네"))
        val names = renameSpeaker(emptyMap(), 1, "  김팀장  ")
        assertTrue(speakerCopyText(original, names).contains("김팀장"))
        val edited = reassignUtterance(original, 0, 2)
        assertEquals(original[0].text, edited[0].text); assertEquals(original[0].start, edited[0].start, 0.0)
        assertEquals(2, edited[0].speaker); assertEquals(1, edited[1].speaker)
        assertNull(reassignUtterance(edited, 0, null)[0].speaker)
        assertTrue(renameSpeaker(names, 1, "  ").isEmpty())
    }
}
