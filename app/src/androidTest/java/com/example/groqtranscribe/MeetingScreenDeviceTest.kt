package com.example.groqtranscribe

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MeetingScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val words = listOf(Word(12.0, 20.0, "이번 주에는 전사 화면을 정리하면 좋겠어요."))
    private val sample = UiState(ready = true, hasKey = true, resultFileName = "주간회의_1022.m4a",
        meetingName = "주간 디자인 회의", importedAtMillis = java.time.Instant.parse("2026-10-22T00:00:00Z").toEpochMilli(), durationSeconds = 758.0,
        result = Transcript("회의 원문 전체", listOf(Segment(12.0, 20.0, "시간별 원문")), words),
        utterances = listOf(
            SpeakerUtterance(12.0, 20.0, 1, "이번 주에는 파일을 올리고 전사 결과를 확인하는 흐름부터 정리하면 좋겠어요."),
            SpeakerUtterance(24.0, 30.0, 2, "좋아요. 결과 화면에서 화자 이름도 바로 바꿀 수 있으면 편하겠네요."),
            SpeakerUtterance(36.0, 44.0, 1, "그럼 읽는 화면은 간결하게 두고, 분석 옵션은 필요할 때 열도록 할게요.")))

    @Composable private fun Screen(state: UiState, onEdit: (Int) -> Unit = {}, onCopy: (String) -> Unit = {},
        onCount: (Int) -> Unit = {}, onAnalyze: () -> Unit = {}, onRename: (String) -> Unit = {}) {
        MeetingScreen(state, {}, {}, {}, {}, {}, onCount, onAnalyze, onEdit, onCopy, onRename)
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "$name.png")
        compose.waitForIdle()
        // GPU의 프레임 제출까지 확인한 후 시스템 바를 포함해 캡처한다.
        compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        android.os.SystemClock.sleep(250)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun resultTabsCopyAndSpeakerEdit() {
        var copied = ""
        var edited = -1
        compose.setContent { BloomTheme { Screen(sample, onEdit = { edited = it }, onCopy = { copied = it }) } }
        screenshot("bloom-v060-result")
        compose.onNodeWithText("화자 2").performClick()
        compose.runOnIdle { assertEquals(1, edited) }
        compose.onNodeWithText("화자별 전사 복사").performClick()
        compose.runOnIdle { assertEquals(speakerCopyText(sample.utterances), copied) }
        compose.onNodeWithText("전체 글").performClick()
        compose.onNodeWithText("회의 원문 전체").assertIsDisplayed()
        compose.onNodeWithText("전체 전사 복사").performClick()
        compose.runOnIdle { assertEquals(sample.result!!.text, copied) }
        compose.onNodeWithText("시간별").performClick()
        compose.onNodeWithText("시간별 원문").assertIsDisplayed()
    }

    @Test fun transcriptRemainsReadableDuringSpeakerAnalysis() {
        compose.setContent { BloomTheme { Screen(sample.copy(utterances = emptyList(), busy = true, status = "화자 분석 중",
            progress = MeetingProgress(transcript = StepStatus.COMPLETE, speakers = StepStatus.RUNNING, percent = 64, completedWindows = 4, totalWindows = 6))) } }
        compose.onNodeWithText("화자 분석 진행률 64% · 6개 구간 중 4개 분석").assertExists()
        screenshot("bloom-v060-progress")
        compose.onNodeWithText("완료된 전사 보기").performClick()
        compose.onNodeWithText("회의 원문 전체").assertIsDisplayed()
        compose.onNodeWithText("화자별", useUnmergedTree = false).assertIsNotEnabled()
        compose.onNodeWithText("전체 전사 복사").assertIsEnabled()
        compose.onNodeWithText("새 파일").assertIsNotEnabled()
    }

    @Test fun reanalysisExplainsMissingAudioAndCannotStart() {
        compose.setContent { BloomTheme { Screen(sample) } }
        compose.onNodeWithText("화자 분석").performClick()
        compose.onNodeWithText("보관한 음성을 찾을 수 없어 재분석할 수 없어요.").assertIsDisplayed()
        compose.onNodeWithText("다시 분석").assertIsNotEnabled()
    }

    @Test fun reanalysisUsesChosenCountAfterExplicitAction() {
        var started = false
        var selected = 0
        val audio = AudioFile(File("unused-preview.m4a"), "회의.m4a", 10, "audio/mp4")
        compose.setContent {
            var state by remember { mutableStateOf(sample.copy(selected = audio)) }
            BloomTheme { Screen(state, onCount = { selected = it; state = state.copy(speakerCount = it) }, onAnalyze = { started = true }) }
        }
        compose.onNodeWithText("화자 분석").performClick()
        compose.onNodeWithText("2명").performClick()
        compose.runOnIdle { assertEquals(2, selected); assertFalse(started) }
        screenshot("bloom-v060-analysis")
        compose.onNodeWithText("다시 분석").performClick()
        compose.runOnIdle { assertTrue(started) }
    }

    @Test fun largeFontKeepsCopyAndSettingsAccessible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                BloomTheme { Screen(sample) }
            }
        }
        compose.onNodeWithContentDescription("설정").assertIsDisplayed()
        compose.onNodeWithText("화자별 전사 복사").assertIsDisplayed()
        compose.onNodeWithText("전체 글").performClick()
        compose.onNodeWithText("회의 원문 전체").assertIsDisplayed()
        screenshot("bloom-v060-large-font")
    }

    @Test fun importScreenShowsFileAndPrivacyBeforeStarting() {
        compose.setContent { BloomTheme { Screen(UiState(ready = true, hasKey = true)) } }
        compose.onAllNodesWithText("음성 파일 선택").onLast().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("화자 구분").assertExists()
        screenshot("bloom-v060-import")
    }

    @Test fun stageNavigationAndMetadataMatchTheMockup() {
        compose.setContent { BloomTheme { Screen(sample) } }
        compose.onNodeWithText("2026.10.22 · 12분 38초").assertIsDisplayed()
        compose.onNodeWithText("00:12").assertIsDisplayed()
        compose.onNodeWithText("주간회의_1022.m4a").assertIsDisplayed()
        compose.onNodeWithText("파일 선택").performClick()
        compose.onNodeWithText("대화는 편하게,\n기록은 또렷하게.").assertIsDisplayed()
        compose.onNodeWithText("전사 결과 보기").performClick()
        compose.onNodeWithText("화자 2").assertIsDisplayed()
    }

    @Test fun meetingTitleCanBeEditedIndependentlyOfFilename() {
        var title = ""
        compose.setContent { BloomTheme { Screen(sample, onRename = { title = it }) } }
        compose.onNodeWithText("주간 디자인 회의").performClick()
        compose.onNodeWithText("회의 제목").performTextReplacement("고객 인터뷰")
        compose.onNodeWithText("저장").performClick()
        compose.runOnIdle { assertEquals("고객 인터뷰", title) }
    }
}
