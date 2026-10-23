package com.example.groqtranscribe

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SpeakerEditorDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unknownGapOptionsCanBeSelected() {
        var selected = UnknownGapMode.CONSERVATIVE
        compose.setContent { BloomTheme { UnknownGapOptions(selected, true, { selected = it }) } }
        compose.onNodeWithText("0.35초").assertIsSelected()
        compose.onNodeWithText("0.8초").performClick()
        compose.runOnIdle { assertEquals(UnknownGapMode.AGGRESSIVE, selected) }
        compose.onNodeWithText("0.5초").performClick()
        compose.runOnIdle { assertEquals(UnknownGapMode.HALF_SECOND, selected) }
        compose.onNodeWithText("1.0초").performClick()
        compose.runOnIdle { assertEquals(UnknownGapMode.ONE_SECOND, selected) }
        compose.onNodeWithText("끄기").performClick()
        compose.runOnIdle { assertEquals(UnknownGapMode.OFF, selected) }
    }
    @Test fun clusteringOptionsCanBeSelected() {
        var selected = SpeakerClustering.DEFAULT
        compose.setContent { BloomTheme { SpeakerClusteringOptions(selected, true, { selected = it }) } }
        compose.onNodeWithText("0.5").assertIsSelected()
        compose.onNodeWithText("0.7").performClick()
        compose.runOnIdle { assertEquals(SpeakerClustering.LOOSE, selected) }
        compose.onNodeWithText("0.3").performClick()
        compose.runOnIdle { assertEquals(SpeakerClustering.STRICT, selected) }
    }
    @Test fun unknownReasonCanBeExpandedAndCollapsed() {
        compose.setContent { BloomTheme { SpeechBubble(SpeakerUtterance(0.0, 0.2, null, "테스트", "연결 차단: 겹말 근거 유지"), emptyMap(), true, {}) } }
        compose.onNodeWithText("연결 차단: 겹말 근거 유지").assertDoesNotExist()
        compose.onNodeWithText("불명 원인 보기").performClick()
        compose.onNodeWithText("연결 차단: 겹말 근거 유지").assertIsDisplayed()
        compose.onNodeWithText("불명 원인 닫기").performClick()
        compose.onNodeWithText("연결 차단: 겹말 근거 유지").assertDoesNotExist()
    }
    @Test fun contextCorrectionShowsMarkerAndEvidence() {
        compose.setContent { BloomTheme { SpeechBubble(SpeakerUtterance(0.9, 2.6, 1, "응 엉덩이",
            "단어별 불명 판정: 예시", "문맥으로 화자 1에 연결됨"), emptyMap(), true, {}) } }
        compose.onNodeWithText("문맥 보정됨 · 자동 추정").assertIsDisplayed()
        compose.onNodeWithText("보정 근거 보기").performClick()
        compose.onNodeWithText("문맥으로 화자 1에 연결됨", substring = true).assertIsDisplayed()
        compose.onNodeWithText("단어별 불명 판정", substring = true).assertIsDisplayed()
        compose.onNodeWithText("보정 근거 닫기").performClick()
    }
    @Test fun protectedUtteranceCanAllowAutomaticUpdates() {
        var released = false
        compose.setContent { BloomTheme {
            SpeakerEditDialog(1, listOf(1), emptyMap(), {}, { _, _, _ -> },
                utterance = SpeakerUtterance(0.0, 1.0, 1, "보호", manualSpeaker = true),
                onAllowAutomatic = { released = true })
        } }
        compose.onNodeWithText("이 발화의 자동 갱신 허용").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(released) }
    }
    @Test fun settingsShowsTheBuiltVersion() {
        compose.setContent { BloomTheme { KeySettings(UiState(ready = true), {}, {}) } }
        compose.onNodeWithText("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
    }
    @Test fun renameExistingSpeakerFromDialog() {
        var saved: String? = null
        compose.setContent { BloomTheme { SpeakerNameDialog(1, emptyMap(), {}, { saved = it }) } }
        compose.onNodeWithText("화자 이름 (비워두면 기본 번호)").performTextInput("김팀장")
        compose.onNodeWithText("이름 저장").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("김팀장", saved) }
    }
    @Test fun assignOneOrAllWithoutRenamingExistingSpeaker() {
        var saved: Triple<Int?, String, Boolean>? = null
        compose.setContent { BloomTheme { SpeakerEditDialog(1, listOf(1, 2), emptyMap(), {},
            { id, name, all -> saved = Triple(id, name, all) }, matchingCount = 12) } }
        compose.onNodeWithText("화자 1").performClick()
        compose.onNodeWithText("화자 2").performClick()
        compose.onNodeWithText("화자 이름 (비워두면 기본 번호)").assertDoesNotExist()
        compose.onNodeWithText("이 발화 변경").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple(2, "", false), saved) }
        compose.onNodeWithText("화자 1의 모든 발화 · 12개").performScrollTo().performClick()
        compose.onNodeWithText("12개 발화 변경").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple(2, "", true), saved) }
    }
    @Test fun createNewSpeaker() {
        var saved: Triple<Int?, String, Boolean>? = null
        compose.setContent { BloomTheme { SpeakerEditDialog(1, listOf(1, 2), emptyMap(), {},
            { id, name, all -> saved = Triple(id, name, all) }) } }
        compose.onNodeWithText("화자 1").performClick()
        compose.onNodeWithText("새 화자 추가").performClick()
        compose.onNodeWithText("화자 이름 (비워두면 기본 번호)").performTextInput("새 참석자")
        compose.onNodeWithText("이 발화 변경").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple(3, "새 참석자", false), saved) }
    }
}
