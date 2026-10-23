package com.example.groqtranscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FILE = 0
private const val PROGRESS = 1
private const val RESULT = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeetingScreen(
    state: UiState, onSettings: () -> Unit, onSelectFile: () -> Unit, onTranscribe: () -> Unit,
    onCancel: () -> Unit, onDiarization: (Boolean) -> Unit, onSpeakerCount: (Int) -> Unit,
    onReanalyze: () -> Unit, onEdit: (Int) -> Unit, onCopy: (String) -> Unit,
    onRenameMeeting: (String) -> Unit = {},
    onUnknownGapMode: (UnknownGapMode) -> Unit = {},
    onSpeakerClustering: (SpeakerClustering) -> Unit = {},
    onGenerateNote: (NoteKind) -> Unit = {}
) {
    var screen by rememberSaveable(state.resultFileName) {
        mutableIntStateOf(if (state.progress?.running == true) PROGRESS else if (state.result != null) RESULT else FILE)
    }
    var tab by rememberSaveable(state.resultFileName) { mutableIntStateOf(0) }
    var analysisOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val result = state.result
    val activeTab = if (tab == 0 && state.utterances.isEmpty()) 1 else tab
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.progress?.running) {
        if (state.progress?.running == true) screen = PROGRESS
        else if (state.progress != null && result != null) screen = RESULT
    }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it) } }
    Scaffold(containerColor = colors.surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.size(33.dp).clip(RoundedCornerShape(11.dp)).background(colors.primaryContainer),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    listOf(12, 21, 15).forEach { height -> Box(Modifier.size(3.dp, height.dp).clip(RoundedCornerShape(4.dp)).background(colors.primary)) }
                }
                Spacer(Modifier.width(9.dp))
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("whisper", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
                    Text("Meeting Note", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
                IconButton(onSettings, enabled = state.ready && !state.busy) { BloomIcon(R.drawable.ic_settings, "설정") }
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 22.dp).fillMaxWidth()
                .clip(RoundedCornerShape(13.dp)).background(colors.surfaceVariant).padding(4.dp)) {
                listOf("파일 선택", "분석 중", "전사 결과").forEachIndexed { index, label ->
                    val enabled = when (index) { FILE -> !state.busy; PROGRESS -> state.progress != null || result != null; else -> result != null }
                    TextButton(onClick = { screen = index }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 3.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (screen == index) colors.surface else colors.surfaceVariant,
                            contentColor = if (screen == index) colors.primary else colors.onSurfaceVariant)) {
                        Text(label, fontSize = 12.sp, fontWeight = if (screen == index) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            }
        }
    }, bottomBar = {
        Column(Modifier.background(colors.surface).navigationBarsPadding()) {
            HorizontalDivider(color = colors.outlineVariant)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (screen) {
                    RESULT -> {
                        BloomButton("새 파일", onSelectFile, enabled = state.ready && !state.busy, icon = R.drawable.ic_file_plus)
                        val text = when (activeTab) {
                            0 -> speakerCopyText(state.utterances, state.speakerNames)
                            3 -> state.minutes?.text.orEmpty()
                            4 -> state.summary?.text.orEmpty()
                            else -> result?.text.orEmpty()
                        }
                        BloomButton(when (activeTab) { 0 -> "화자별 전사 복사"; 3 -> "회의록 복사"; 4 -> "요약 복사"; else -> "전체 전사 복사" }, { onCopy(text) },
                            Modifier.weight(1f), enabled = text.isNotBlank(), primary = true, icon = R.drawable.ic_copy)
                    }
                    PROGRESS -> {
                        BloomButton("분석 취소", onCancel, enabled = state.progress?.running == true)
                        BloomButton("완료된 전사 보기", { tab = 1; screen = RESULT }, Modifier.weight(1f), result != null, primary = true)
                    }
                    else -> BloomButton(if (result != null) "전사 결과 보기" else if (state.selected == null) "음성 파일 선택" else "전사 시작하기",
                        { if (result != null) screen = RESULT else if (state.selected == null) onSelectFile() else onTranscribe() },
                        Modifier.fillMaxWidth(), state.ready && !state.busy && (result != null || state.selected == null || state.hasKey), primary = true)
                }
            }
        }
    }) { padding ->
        key(screen, activeTab, state.resultFileName) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
                state.error?.let { item { InfoBox(it, error = true); Spacer(Modifier.height(12.dp)) } }
                if (state.busy && state.progress?.running != true) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.status, style = MaterialTheme.typography.bodySmall)
                }
                when (screen) {
                    FILE -> item { ImportContent(state, onSettings, onSelectFile, onDiarization, onSpeakerCount) }
                    PROGRESS -> item { ProgressContent(state) }
                    else -> if (result != null) {
                        item {
                            Eyebrow("CONVERSATION")
                            Text(state.meetingName ?: meetingTitle(state.resultFileName), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.clickable(enabled = !state.busy, onClickLabel = "회의 제목 변경") { renameOpen = true }
                                    .padding(top = 8.dp, bottom = 9.dp))
                            val metadata = listOfNotNull(meetingDate(state.importedAtMillis), meetingDuration(state.durationSeconds)).joinToString(" · ")
                            Text(metadata.ifBlank { "날짜·길이 정보 없음" }, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "가져온 날짜와 음성 길이: $metadata" })
                            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 17.dp),
                                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.align(Alignment.CenterVertically)) { BloomIcon(R.drawable.ic_file_audio) }
                                Text(state.resultFileName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    modifier = Modifier.widthIn(max = 240.dp).align(Alignment.CenterVertically), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Badge("전사 완료")
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                listOf("화자별", "전체 글", "시간별", "회의록", "요약").forEachIndexed { index, label ->
                                    Column(Modifier.weight(1f)) {
                                        TextButton({ tab = index }, enabled = index != 0 || state.utterances.isNotEmpty(),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(0.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                                            Text(label, fontSize = 13.sp, fontWeight = if (activeTab == index) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (activeTab == index) colors.primary else colors.onSurfaceVariant)
                                        }
                                        HorizontalDivider(thickness = if (activeTab == index) 2.dp else 1.dp,
                                            color = if (activeTab == index) colors.primary else colors.outlineVariant)
                                    }
                                }
                            }
                            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (activeTab == 0) "화자 이름을 눌러 수정" else if (activeTab >= 3) "한국어 · AI 작성" else "한국어 · 원문 전사", style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                                TextButton({ analysisOpen = true }, enabled = !state.busy, contentPadding = PaddingValues(6.dp)) {
                                    BloomIcon(R.drawable.ic_sliders); Spacer(Modifier.width(6.dp)); Text("화자 분석")
                                }
                            }
                            if (state.progress?.running == true) TextButton({ screen = PROGRESS }) { Text("화자 분석 중 · 진행 상황 보기") }
                            state.speakerWarning?.let { InfoBox(it, error = true); Spacer(Modifier.height(12.dp)) }
                        }
                        when (activeTab) {
                            3, 4 -> item { GeneratedNoteContent(state, if (activeTab == 3) NoteKind.MINUTES else NoteKind.SUMMARY, onGenerateNote, onCancel, onSettings) }
                            0 -> itemsIndexed(state.utterances) { index, utterance ->
                                SpeechBubble(utterance, state.speakerNames, !state.busy) { onEdit(index) }; Spacer(Modifier.height(14.dp))
                            }
                            1 -> item { SelectionContainer { Text(result.text.ifBlank { "인식된 음성이 없어요." }, style = MaterialTheme.typography.bodyLarge) } }
                            else -> {
                                if (result.segments.isEmpty()) item { Text("서버에서 구간 정보가 반환되지 않았어요.") }
                                items(result.segments) { segment ->
                                    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(compactTimestamp(segment.start), Modifier.widthIn(min = 46.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        SelectionContainer { Text(segment.text.trim(), style = MaterialTheme.typography.bodyLarge) }
                                    }
                                    HorizontalDivider(color = colors.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (renameOpen) BloomSheet({ renameOpen = false }) {
        var name by remember { mutableStateOf(state.meetingName ?: meetingTitle(state.resultFileName)) }
        Text("회의 제목 변경", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("회의 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BloomButton("닫기", { renameOpen = false })
            BloomButton("저장", { renameOpen = false; onRenameMeeting(name) }, Modifier.weight(1f), primary = true)
        }
    }
    if (analysisOpen) BloomSheet({ analysisOpen = false }) {
        Text("화자만 다시 분석", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        Text("전사 내용은 그대로 두고, 화자 구분만 바꿔요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        SpeakerCountOptions(state.speakerCount, !state.busy, onSpeakerCount)
        UnknownGapOptions(state.unknownGapMode, state.ready && !state.busy, onUnknownGapMode)
        SpeakerClusteringOptions(state.speakerClustering, state.ready && !state.busy, onSpeakerClustering)
        InfoBox("화자 이름과 수동 배정은 유지하고 자동 구간만 갱신해요. 수정 이력이 없는 이전 버전의 저장본은 전체 배정을 보존해요.")
        if (state.selected == null) InfoBox("보관한 음성을 찾을 수 없어 재분석할 수 없어요.", true)
        else if (result?.words.isNullOrEmpty()) InfoBox("단어별 시간이 없어 재분석할 수 없어요.", true)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BloomButton("닫기", { analysisOpen = false })
            BloomButton("다시 분석", { analysisOpen = false; onReanalyze() }, Modifier.weight(1f),
                !state.busy && state.selected != null && result?.words?.isNotEmpty() == true, primary = true)
        }
    }
}

@Composable
internal fun SpeechBubble(utterance: SpeakerUtterance, names: Map<Int, String>, enabled: Boolean, edit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var showDiagnosis by remember(utterance) { mutableStateOf(false) }
    val alternate = utterance.speaker != null && utterance.speaker % 2 == 0
    Surface(Modifier.fillMaxWidth().padding(start = if (alternate) 18.dp else 0.dp, end = if (alternate) 0.dp else 18.dp),
        color = if (utterance.speaker == null) colors.surfaceVariant else if (alternate) colors.secondaryContainer else colors.primaryContainer,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(topStart = if (alternate) 17.dp else 0.dp, topEnd = if (alternate) 0.dp else 17.dp, bottomStart = 17.dp, bottomEnd = 17.dp)) {
        Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(edit, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(speakerLabel(utterance.speaker, names), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        BloomIcon(R.drawable.ic_chevron_down)
                    }
                }
                Text(compactTimestamp(utterance.start), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "${timestamp(utterance.start)}부터 ${timestamp(utterance.end)}까지" })
            }
            Spacer(Modifier.height(5.dp))
            SelectionContainer { Text(utterance.text, style = MaterialTheme.typography.bodyLarge) }
            if (utterance.manualSpeaker) Text("배정 보호됨", style = MaterialTheme.typography.labelSmall)
            utterance.contextCorrection?.let {
                Text("문맥 보정됨 · 자동 추정", style = MaterialTheme.typography.labelSmall)
            }
            if (utterance.speaker == null || utterance.contextCorrection != null) {
                TextButton({ showDiagnosis = !showDiagnosis }) { Text(if (utterance.contextCorrection != null) { if (showDiagnosis) "보정 근거 닫기" else "보정 근거 보기" } else { if (showDiagnosis) "불명 원인 닫기" else "불명 원인 보기" }) }
                if (showDiagnosis) SelectionContainer {
                    Text(utterance.contextCorrection?.let { it + "\n\n" + utterance.diagnosis.orEmpty() } ?: utterance.diagnosis ?: "이전 분석에는 진단 정보가 없어요. 화자 재분석 후 원인과 연결 차단 이유를 확인할 수 있어요.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
