package com.example.groqtranscribe

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MeetingStoreDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun sample(audio: File) = UiState(ready = true,
        selected = AudioFile(audio, "통화.m4a", audio.length(), "audio/mp4"), resultFileName = "통화.m4a",
        result = Transcript("안녕하세요", listOf(Segment(0.0, 1.0, "안녕하세요")), listOf(Word(0.0, 1.0, "안녕하세요"))),
        utterances = listOf(SpeakerUtterance(0.0, 1.0, 2, "안녕하세요")), speakerNames = mapOf(2 to "김팀장"), speakerCount = 2)

    @Test fun durableRoundTripFailureAndMissingAudio() {
        val root = File(context.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val folder = File(root, "saved")
            val store = MeetingStore(folder)
            val expected = sample(source).copy(utterances = listOf(SpeakerUtterance(0.0, 1.0, 1, "진단 예시", "단어별 불명 판정: 분석 공백\n전체 구간 기준: 문맥 보정", "문맥으로 화자 1에 연결됨", manualSpeaker = true)), unknownGapMode = UnknownGapMode.ONE_SECOND, speakerClustering = SpeakerClustering.LOOSE)
            val saved = store.save(expected)
            source.delete()
            val restored = MeetingStore(folder).load()!!
            assertEquals(expected.speakerClustering, restored.speakerClustering)
            assertEquals(expected.unknownGapMode, restored.unknownGapMode)
            assertEquals(expected.result, restored.result)
            assertEquals(expected.utterances, restored.utterances)
            assertEquals(expected.speakerNames, restored.speakerNames)
            assertEquals(2, restored.speakerCount)
            assertArrayEquals(byteArrayOf(1, 2, 3), restored.selected!!.file.readBytes())
            // 직렬화 실패가 이전 메타데이터를 손상시키면 안 된다.
            try {
                store.save(saved.copy(utterances = listOf(SpeakerUtterance(Double.NaN, 1.0, 1, "오류"))))
                fail("invalid timestamp must fail")
            } catch (_: org.json.JSONException) { }
            assertEquals(expected.speakerNames, store.load()!!.speakerNames)
            saved.selected!!.file.delete()
            assertEquals(expected.result, store.load()!!.result)
            assertNull(store.load()!!.selected)
            store.clear()
            assertNull(store.load())
        } finally { root.deleteRecursively() }
    }

    @Test fun replacingAudioRemovesOnlyPreviousPrivateCopy() {
        val root = File(context.cacheDir, "store-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source.m4a").apply { writeText("audio") }
            val store = MeetingStore(File(root, "saved"))
            val first = store.save(sample(source)).selected!!.file
            val second = store.save(sample(source)).selected!!.file
            assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(source.exists())
            store.clear()
            assertFalse(second.exists()); assertTrue(source.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun viewModelRecreationRestoresEditedNamesAndAssignments() = runBlocking {
        val testRoot = File(context.cacheDir, "isolated-meeting-${UUID.randomUUID()}")
        val store = MeetingStore(testRoot)
        val source = File(context.cacheDir, "restore-test.m4a").apply { writeText("audio") }
        val owners = mutableListOf<ViewModelStore>()
        fun create(): TranscriptionViewModel {
            lateinit var model: TranscriptionViewModel
            instrumentation.runOnMainSync {
                model = TranscriptionViewModel(context.applicationContext as Application, meetingStore = store)
                owners += ViewModelStore().apply { put("meeting", model) }
            }
            return model
        }
        try {
            val initial = sample(source).copy(utterances = listOf(
                SpeakerUtterance(0.0, 1.0, 2, "안녕하세요"),
                SpeakerUtterance(1.0, 2.0, 1, "네"),
                SpeakerUtterance(2.0, 3.0, 2, "반갑습니다")), speakerNames = mapOf(1 to "기존 이름", 2 to "김팀장"))
            store.save(initial)
            val model = create()
            withTimeout(10000) { while (!model.state.value.ready) delay(20) }
            assertEquals("김팀장", model.state.value.speakerNames[2])
            instrumentation.runOnMainSync { model.editSpeaker(0, 1, "덮어쓰면 안 됨", true) }
            withTimeout(10000) { while (model.state.value.busy) delay(20) }
            assertNull(model.state.value.error)
            assertEquals("기존 이름", model.state.value.speakerNames[1])
            assertEquals(listOf(1, 1, 1), model.state.value.utterances.map { it.speaker })
            instrumentation.runOnMainSync { model.editSpeakerName(1, "홍길동") }
            withTimeout(10000) { while (model.state.value.busy) delay(20) }
            assertNull(model.state.value.error)
            assertEquals(initial.utterances.map { it.text }, model.state.value.utterances.map { it.text })
            instrumentation.runOnMainSync { model.setUnknownGapMode(UnknownGapMode.OFF) }
            withTimeout(10000) { while (model.state.value.busy) delay(20) }
            assertNull(model.state.value.error)
            assertEquals(listOf(1, 1, 1), model.state.value.utterances.map { it.speaker })
            instrumentation.runOnMainSync { model.setSpeakerClustering(SpeakerClustering.CAREFUL) }
            withTimeout(10000) { while (model.state.value.busy) delay(20) }
            assertNull(model.state.value.error)
            instrumentation.runOnMainSync { owners.first().clear() }
            val reopened = create()
            withTimeout(10000) { while (!reopened.state.value.ready) delay(20) }
            assertEquals(SpeakerClustering.CAREFUL, reopened.state.value.speakerClustering)
            assertEquals(UnknownGapMode.OFF, reopened.state.value.unknownGapMode)
            assertEquals("홍길동", reopened.state.value.speakerNames[1])
            assertEquals(listOf(1, 1, 1), reopened.state.value.utterances.map { it.speaker })
            assertEquals("안녕하세요", reopened.state.value.result!!.text)
            assertTrue(reopened.state.value.selected!!.file.isFile)
        } finally {
            instrumentation.runOnMainSync { owners.forEach { it.clear() } }
            store.clear(); source.delete(); testRoot.deleteRecursively()
        }
    }
}
