package com.example.groqtranscribe

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class MeetingAiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sample = UiState(ready = true, hasAiKey = true, resultFileName = "회의.m4a",
        result = Transcript("금요일에 검토합니다.", emptyList()),
        utterances = listOf(SpeakerUtterance(1.0, 3.0, 1, "금요일에 검토합니다.")))

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun resultTabsGenerateCopyAndWarnOnSourceChanges() {
        var requested: NoteKind? = null
        var copied = ""
        val note = GeneratedNote("회의록 테스트 본문", "gemini", "gemini-3.8-flash", "old-source", 1L)
        compose.setContent { BloomTheme {
            MeetingScreen(sample.copy(minutes = note), {}, {}, {}, {}, {}, {}, {}, {}, { copied = it }, onGenerateNote = { requested = it })
        } }
        compose.onNodeWithText("회의록").performClick()
        compose.onNodeWithText("회의록 다시 작성").performScrollTo().performClick()
        screenshot("meeting-ai-minutes")
        compose.runOnIdle { assertEquals(NoteKind.MINUTES, requested) }
        compose.onNodeWithText("화자 또는 회의 제목이 변경됐어요. 아래 내용은 이전 자료로 작성됐으니 다시 작성해 주세요.").assertExists()
        compose.onNodeWithText("회의록 복사").performClick()
        compose.runOnIdle { assertEquals(note.text, copied) }
        compose.onNodeWithText("요약").performScrollTo().performClick()
        compose.onNodeWithText("요약 작성").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(NoteKind.SUMMARY, requested) }
        compose.onNodeWithText("요약 복사").assertIsNotEnabled()
    }

    @Test fun editableModelAndIndependentPrompts() {
        var saved: AiSettings? = null
        compose.setContent { BloomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AiSettingsContent(sample, { saved = it }, {}, {})
            }
        } }
        compose.onNodeWithText("AI 모델 ID").performScrollTo().performTextReplacement("gemini-custom")
        compose.onNodeWithText("회의록 프롬프트").performScrollTo().performTextReplacement("안건 중심 회의록")
        compose.onNodeWithText("요약 프롬프트").performScrollTo().performTextReplacement("세 줄 요약")
        compose.onNodeWithText("AI 설정 저장").performScrollTo().performClick()
        screenshot("meeting-ai-settings")
        compose.runOnIdle {
            assertEquals("gemini-custom", saved!!.model)
            assertEquals("안건 중심 회의록", saved!!.minutesPrompt)
            assertEquals("세 줄 요약", saved!!.summaryPrompt)
        }
    }

    @Test fun notesRoundTripAndLegacyMeetingCompatibility() {
        val folder = File(context.cacheDir, "ai-store-${UUID.randomUUID()}")
        try {
            val store = MeetingStore(folder)
            store.save(sample)
            assertNull(store.load()!!.minutes)
            val note = GeneratedNote("내용", "gemini", "gemini-3.8-flash", sourceHash(meetingAiSource(sample)), 42L)
            store.save(sample.copy(minutes = note, summary = note.copy(text = "요약")))
            val restored = MeetingStore(folder).load()!!
            assertEquals(note, restored.minutes)
            assertEquals("요약", restored.summary!!.text)
            assertEquals(sample.utterances, restored.utterances)
        } finally { folder.deleteRecursively() }
    }

    @Test fun viewModelGenerationFailureCancellationAndSettingsRestore() = runBlocking {
        val store = MeetingStore(File(context.noBackupFilesDir, "current-meeting"))
        val settingsStore = AiSettingsStore(context)
        val previousSettings = settingsStore.load()
        val keys = ApiKeyStore(context, "gemini")
        val previousKey = keys.read()
        val owners = mutableListOf<ViewModelStore>()
        var mode = "success"
        val provider = object : TextGenerationProvider {
            override val id = "gemini"
            override suspend fun generate(request: TextGenerationRequest, apiKey: String): String {
                if (mode == "fail") throw UserError("테스트 실패")
                if (mode == "wait") delay(60000)
                return "생성한 ${request.instruction.takeLast(5)}"
            }
        }
        fun create(): TranscriptionViewModel {
            lateinit var model: TranscriptionViewModel
            instrumentation.runOnMainSync {
                model = TranscriptionViewModel(context.applicationContext as Application, MeetingAiService(listOf(provider)))
                owners += ViewModelStore().apply { put("ai", model) }
            }
            return model
        }
        suspend fun idle(model: TranscriptionViewModel) {
            withTimeout(10000) { while (!model.state.value.ready || model.state.value.busy) delay(20) }
        }
        try {
            keys.save("test-only-key")
            store.save(sample)
            val model = create()
            idle(model)
            val custom = AiSettings(model = "gemini-test", minutesPrompt = "회의록 양식", summaryPrompt = "요약 양식")
            instrumentation.runOnMainSync { model.saveAiSettings(custom) }
            idle(model)
            instrumentation.runOnMainSync { model.generateNote(NoteKind.MINUTES) }
            idle(model)
            val note = model.state.value.minutes!!
            assertEquals("gemini-test", note.model)
            assertEquals(note, store.load()!!.minutes)
            mode = "fail"
            instrumentation.runOnMainSync { model.generateNote(NoteKind.MINUTES) }
            idle(model)
            assertEquals(note, model.state.value.minutes)
            assertEquals("테스트 실패", model.state.value.error)
            mode = "wait"
            instrumentation.runOnMainSync { model.generateNote(NoteKind.MINUTES) }
            instrumentation.runOnMainSync { model.cancel() }
            idle(model)
            assertEquals(note, model.state.value.minutes)
            assertNull(model.state.value.generatingNote)
            val reopened = create()
            idle(reopened)
            assertEquals(custom, reopened.state.value.aiSettings)
            assertEquals(note, reopened.state.value.minutes)
            instrumentation.runOnMainSync { reopened.clearSavedMeeting() }
            idle(reopened)
            assertEquals(custom, reopened.state.value.aiSettings)
            assertTrue(reopened.state.value.hasAiKey)
            assertNull(reopened.state.value.minutes)
        } finally {
            instrumentation.runOnMainSync { owners.forEach { it.clear() } }
            settingsStore.save(previousSettings)
            if (previousKey.isBlank()) keys.clear() else keys.save(previousKey)
            store.clear()
        }
    }
}
