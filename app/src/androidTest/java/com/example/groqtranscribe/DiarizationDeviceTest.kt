package com.example.groqtranscribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiarizationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun decode(name: String): DecodedAudio {
        val file = File(context.cacheDir, "test-$name")
        try {
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            return AudioDecoder(context).decode(file)
        } finally { file.delete() }
    }
    @Test fun decodesM4aMp3AndWavOnTheSameTimeline() = runBlocking {
        decode("four-speakers.wav").use { wav ->
            assertTrue(wav.sampleCount in 900000L..920000L)
            for (name in listOf("four-speakers.m4a", "four-speakers.mp3")) {
                decode(name).use { decoded ->
                    assertEquals(wav.durationSeconds, decoded.durationSeconds, 0.15)
                    assertTrue(decoded.read(0, decoded.sampleCount.toInt()).any { kotlin.math.abs(it) > 0.1 })
                }
            }
        }
    }
    @Test fun nativeModelsProduceSpeakerTurns() = runBlocking {
        decode("four-speakers.wav").use { audio ->
            val turns = SpeakerDiarizer(context).analyze(audio, 4, {})
            assertTrue(turns.isNotEmpty())
            assertTrue(turns.filter { it.speaker >= 0 }.map { it.speaker }.distinct().size >= 2)
            assertTrue(turns.all { it.start >= 0 && it.end > it.start && it.end <= audio.durationSeconds + 0.1 })
        }
    }
    @Test fun telephoneBandwidthTwoSpeakerFixtureDoesNotExplode() = runBlocking {
        decode("two-speaker-telephone.m4a").use { audio ->
            assertTrue(audio.durationSeconds > 360)
            for (count in listOf(0, 2)) {
                val turns = SpeakerDiarizer(context).analyze(audio, count, {})
                val speakers = turns.filter { it.speaker >= 0 }.map { it.speaker }.distinct()
                assertEquals("speaker count in mode $count: $speakers", 2, speakers.size)
                val known = turns.filter { it.speaker >= 0 }.sumOf { it.end - it.start }
                val all = turns.sumOf { it.end - it.start }
                assertTrue("Too much unknown speech in mode $count", known / all > 0.9)
            }
        }
    }
    @Test fun longerThanTenMinutesKeepsReturningSpeakersAcrossChunks() = runBlocking {
        val originalDuration = decode("four-speakers.wav").use { it.durationSeconds }
        decode("long-meeting.m4a").use { audio ->
            assertTrue(audio.durationSeconds > 600)
            for (speakerCount in listOf(0, 4)) {
                val turns = SpeakerDiarizer(context).analyze(audio, speakerCount, {})
                assertTrue(turns.any { it.end > 600 })
                fun at(time: Double) = turns.firstOrNull { it.start <= time && it.end > time }?.speaker
                val first = listOf(3.0, 8.5, 15.0, 28.5).map { at(it) }
                assertTrue(first.all { it != null && it >= 0 })
                assertTrue(first.distinct().size >= 3)
                for (repeat in listOf(3, 6, 9, 11)) {
                    val later = listOf(3.0, 8.5, 15.0, 28.5).map { at(it + originalDuration * repeat) }
                    assertEquals("Returning speakers differ in repetition $repeat, count=$speakerCount", first, later)
                }
            }
        }
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("diarization_") })
    }
}
