package com.example.groqtranscribe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AiSettingsContent(state: UiState, save: (AiSettings) -> Unit, saveKey: (String) -> Unit, deleteKey: () -> Unit) {
    var model by rememberSaveable(state.aiSettings) { mutableStateOf(state.aiSettings.model) }
    var minutes by rememberSaveable(state.aiSettings) { mutableStateOf(state.aiSettings.minutesPrompt) }
    var summary by rememberSaveable(state.aiSettings) { mutableStateOf(state.aiSettings.summaryPrompt) }
    var key by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf(!state.hasAiKey) }
    val enabled = state.ready && !state.busy
    HorizontalDivider()
    Text("회의록 · 요약 AI", style = MaterialTheme.typography.titleMedium)
    Text("제공자: Google Gemini", style = MaterialTheme.typography.bodyMedium)
    Text("생성 시 화자 이름·발언·시각·회의 제목을 Google에 전송해요. 음성 파일은 보내지 않아요.", style = MaterialTheme.typography.bodySmall)
    Text(if (state.hasAiKey) "Gemini API 키 등록됨" else "Gemini API 키 등록 필요")
    if (state.hasAiKey) TextButton({ editKey = !editKey }, enabled = enabled) { Text("Gemini 키 변경") }
    if (editKey) {
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("Gemini API 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        BloomButton("Gemini 키 저장", { saveKey(key); key = "" }, enabled = enabled && key.isNotBlank())
    }
    if (state.hasAiKey) TextButton({ deleteKey(); key = "" }, enabled = enabled) { Text("Gemini 키 삭제") }
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("AI 모델 ID") }, singleLine = true)
    Text("기본값: gemini-3.8-flash · 이용 가능한 Gemini 모델 ID로 변경할 수 있어요.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(minutes, { minutes = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("회의록 프롬프트") }, minLines = 4, maxLines = 9)
    OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("요약 프롬프트") }, minLines = 4, maxLines = 9)
    Text("발언 자료는 자동으로 첨부돼요. 프롬프트에는 원하는 작성 방식만 입력해 주세요. 저장 후 다음 생성부터 적용돼요.", style = MaterialTheme.typography.bodySmall)
    TextButton({ model = AiSettings().model; minutes = AiSettings.DEFAULT_MINUTES; summary = AiSettings.DEFAULT_SUMMARY }, enabled = enabled) {
        Text("AI 기본값 불러오기")
    }
    BloomButton("AI 설정 저장", { save(state.aiSettings.copy(model = model.trim(), minutesPrompt = minutes.trim(), summaryPrompt = summary.trim())) },
        enabled = enabled && model.isNotBlank() && minutes.isNotBlank() && summary.isNotBlank(), primary = true)
}

@Composable
internal fun GeneratedNoteContent(state: UiState, kind: NoteKind, generate: (NoteKind) -> Unit, cancel: () -> Unit, settings: () -> Unit) {
    val note = if (kind == NoteKind.MINUTES) state.minutes else state.summary
    val currentHash = remember(state.utterances, state.speakerNames, state.meetingName) { sourceHash(meetingAiSource(state)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("화자별 발언을 바탕으로 ${kind.label}을 작성해요. 생성하면 회의 텍스트와 화자 이름이 Google에 전송돼요.", style = MaterialTheme.typography.bodySmall)
        Text("모델: ${state.aiSettings.model}", style = MaterialTheme.typography.bodySmall)
        if (!state.hasAiKey) TextButton(settings, enabled = !state.busy) { Text("설정에서 Gemini 키 등록") }
        if (state.utterances.isEmpty()) Text("화자 분석을 완료한 뒤 작성할 수 있어요.")
        if (state.generatingNote != null) BloomButton("AI 생성 취소", cancel)
        else BloomButton(if (note == null) "${kind.label} 작성" else "${kind.label} 다시 작성", { generate(kind) },
            enabled = state.ready && !state.busy && state.hasAiKey && state.utterances.isNotEmpty(), primary = true)
        if (note != null) {
            if (note.sourceHash != currentHash) InfoBox("화자 또는 회의 제목이 변경됐어요. 아래 내용은 이전 자료로 작성됐으니 다시 작성해 주세요.")
            Text("작성 모델: ${note.model} · ${meetingDate(note.createdAt).orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("AI가 작성한 내용이에요. 결정 사항과 담당자는 원문과 함께 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
