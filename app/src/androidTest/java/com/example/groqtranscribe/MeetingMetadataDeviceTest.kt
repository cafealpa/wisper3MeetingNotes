package com.example.groqtranscribe

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MeetingMetadataDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun durationTitleAndDateSurviveSaveAndOldFilesRemainReadable() {
        val root = File(context.cacheDir, "metadata-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val audio = File(root, "sample.m4a")
            instrumentation.context.assets.open("four-speakers.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
            val duration = audioDurationSeconds(audio)
            assertNotNull(duration)
            assertTrue(duration!! in 56.0..58.0)
            val directory = File(root, "saved")
            val store = MeetingStore(directory)
            val state = UiState(selected = AudioFile(audio, "sample.m4a", audio.length(), "audio/mp4"),
                importedAtMillis = 1792627200000L, durationSeconds = duration, meetingName = "고객 인터뷰")
            store.save(state)
            val restored = store.load()!!
            assertEquals(state.importedAtMillis, restored.importedAtMillis)
            assertEquals(duration, restored.durationSeconds)
            assertEquals("고객 인터뷰", restored.meetingName)
            val metadata = File(directory, "meeting.json")
            val old = JSONObject(metadata.readText()).apply { remove("importedAtMillis"); remove("durationSeconds"); remove("meetingName") }
            metadata.writeText(old.toString())
            val legacy = store.load()!!
            assertEquals(0L, legacy.importedAtMillis)
            assertNull(legacy.durationSeconds)
            assertNull(legacy.meetingName)
            assertNotNull(legacy.selected)
        } finally { root.deleteRecursively() }
    }

    @Test fun nativeProgressReportsCompletedWindows() = runBlocking {
        val file = File(context.cacheDir, "progress-${UUID.randomUUID()}.wav")
        try {
            instrumentation.context.assets.open("four-speakers.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            AudioDecoder(context).decode(file).use { audio ->
                val windows = mutableListOf<Pair<Int, Int>>()
                val percentages = mutableListOf<Int>()
                SpeakerDiarizer(context).analyze(audio, 4, { percentages += it }, coreSeconds = 20,
                    windowsProgress = { done, total -> windows += done to total })
                assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), windows)
                assertEquals(100, percentages.last())
                assertTrue(percentages.zipWithNext().all { (a,b) -> b >= a })
            }
        } finally { file.delete() }
    }
}
