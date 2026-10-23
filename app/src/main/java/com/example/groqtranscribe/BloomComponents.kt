package com.example.groqtranscribe

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun BloomIcon(@DrawableRes id: Int, description: String? = null) {
    Icon(painterResource(id), description, Modifier.size(16.dp))
}

@Composable
internal fun BloomButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, @DrawableRes icon: Int? = null) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(onClick, modifier.defaultMinSize(minHeight = if (primary) 48.dp else 44.dp), enabled,
        shape = RoundedCornerShape(12.dp), border = if (primary) null else BorderStroke(1.dp, colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (primary) colors.primary else colors.surface,
            contentColor = if (primary) colors.onPrimary else colors.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
        icon?.let { BloomIcon(it); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun ImportContent(state: UiState, settings: () -> Unit, select: () -> Unit, diarization: (Boolean) -> Unit, count: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Eyebrow("A LITTLE LESS TO REMEMBER")
        Text("대화는 편하게,\n기록은 또렷하게.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp, bottom = 9.dp))
        Text("녹음 파일을 읽기 편한 글로 바꿔요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (!state.hasKey) TextButton(settings, enabled = state.ready && !state.busy) { Text("처음 한 번, API 키 등록하기") }
        Surface(Modifier.fillMaxWidth().padding(vertical = 24.dp).drawWithContent {
            drawContent()
            drawRoundRect(colors.outline, cornerRadius = CornerRadius(20.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
        }, shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), color = colors.surface) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_file_audio), null, Modifier.size(24.dp), tint = colors.primary) }
                }
                Spacer(Modifier.height(15.dp))
                Text(state.selected?.name ?: "어떤 대화를 기록할까요?", style = MaterialTheme.typography.titleSmall)
                Text(state.selected?.let { String.format(Locale.KOREA, "%.1f MB · 파일 선택 완료", it.size / 1_000_000.0) }
                    ?: "M4A · MP3 · WAV 등 / 최대 25 MB", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                BloomButton(if (state.selected == null) "음성 파일 선택" else "다른 파일 선택", select, Modifier.fillMaxWidth(), state.ready && !state.busy, icon = R.drawable.ic_folder)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("화자 구분", fontWeight = FontWeight.SemiBold)
                Text("누가 한 말인지 함께 정리해요", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(state.diarizationEnabled, diarization, enabled = state.ready && !state.busy)
        }
        if (state.diarizationEnabled) SpeakerCountOptions(state.speakerCount, state.ready && !state.busy, count)
        Spacer(Modifier.height(15.dp))
        InfoBox("전사를 시작하면 음성 파일이 Groq로 전송돼요. 화자 구분은 이 기기에서 처리해요.")
    }
}

@Composable
internal fun ProgressContent(state: UiState) {
    val colors = MaterialTheme.colorScheme
    val progress = state.progress ?: MeetingProgress(running = false, transcript = StepStatus.COMPLETE,
        speakers = if (state.utterances.isNotEmpty()) StepStatus.COMPLETE else StepStatus.SKIPPED,
        alignment = if (state.utterances.isNotEmpty()) StepStatus.COMPLETE else StepStatus.SKIPPED)
    val states = listOf(progress.transcript, progress.speakers, progress.alignment)
    val failed = state.error != null || states.any { it == StepStatus.FAILED }
    val cancelled = states.any { it == StepStatus.CANCELLED }
    Column {
        Eyebrow(if (progress.running) "IN PROGRESS" else "YOUR CONVERSATION")
        Text(if (progress.running) "대화를 정리하고 있어요" else if (cancelled) "분석을 중단했어요" else if (failed) "분석 결과를 확인해 주세요" else "대화 정리가 끝났어요",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 9.dp))
        Text(state.selected?.name ?: state.resultFileName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when { progress.alignment == StepStatus.RUNNING -> "발화와 화자 연결 중"; progress.speakers == StepStatus.RUNNING -> "화자 구분 중";
                progress.transcript == StepStatus.RUNNING -> "음성을 글로 변환 중"; else -> "처리 결과" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Badge(if (progress.running) "${if (progress.alignment == StepStatus.RUNNING) 3 else if (progress.speakers == StepStatus.RUNNING) 2 else 1} / 3단계" else if (failed) "일부 실패" else if (cancelled) "중단됨" else "완료")
        }
        Spacer(Modifier.height(16.dp))
        if (progress.percent != null) LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
        else if (progress.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { if (failed || cancelled) 0f else 1f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(if (progress.percent != null) "화자 분석 진행률 ${progress.percent}%" +
            (if (progress.totalWindows > 0) " · ${progress.totalWindows}개 구간 중 ${progress.completedWindows}개 분석" else "")
            else if (progress.running) "처리 시간을 확인하고 있어요" else "완료된 전사는 결과 화면에서 볼 수 있어요",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        listOf("음성을 글로 변환", "화자 구분", "발화와 화자 연결").forEachIndexed { index, title ->
            val step = states[index]
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.size(30.dp), shape = RoundedCornerShape(15.dp), color = colors.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text(if (step == StepStatus.COMPLETE) "✓" else "${index + 1}", fontSize = 12.sp, color = colors.primary) }
                }
                Column {
                    Text(title)
                    Text(when (step) {
                        StepStatus.COMPLETE -> if (index == 0) "전체 전사를 먼저 볼 수 있어요" else "완료"
                        StepStatus.RUNNING -> if (index == 1) "이 기기에서 분석하고 있어요" else "처리 중"
                        StepStatus.SKIPPED -> "사용 안 함"; StepStatus.FAILED -> "처리하지 못했어요"
                        StepStatus.CANCELLED -> "중단됨"; StepStatus.WAITING -> "대기 중"
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
        Spacer(Modifier.height(24.dp))
        InfoBox(if (progress.running) "완료될 때까지 앱을 열어두세요.\n화자 분석을 취소해도 완료된 전사는 유지돼요."
            else if (cancelled || failed) "완료된 전사와 기존 화자 수정 내용은 유지돼요." else "완료된 결과를 기기에 저장했어요.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerCountOptions(count: Int, enabled: Boolean, onCount: (Int) -> Unit) {
    Column {
        Text("참석 인원", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(0, 2, 3, 4).forEach { value ->
                OutlinedButton({ onCount(value) }, Modifier.weight(1f).heightIn(min = 44.dp), enabled = enabled,
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, if (value == count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (value == count) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Text(if (value == 0) "자동" else "${value}명", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun InfoBox(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(13.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BloomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnknownGapOptions(mode: UnknownGapMode, enabled: Boolean, onChange: (UnknownGapMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("짧은 불명 구간 연결", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UnknownGapMode.entries.forEach { option ->
                FilterChip(selected = option == mode, onClick = { onChange(option) },
                    label = { Text(option.label) }, enabled = enabled)
            }
        }
        Text("앞뒤가 같은 화자일 때 선택한 시간까지 연결해요. 기본은 0.35초예요. 미세한 분석 흔적은 허용하되, 뚜렷한 다른 화자나 겹말 근거는 유지해요.",
            style = MaterialTheme.typography.bodySmall)
        Text("다음 전사 또는 화자 재분석부터 적용돼요. 현재 수정한 발화는 그대로 유지돼요. 끄기를 제외하면 문맥 근거가 충분한 불명 구간은 최대 2초까지 별도로 보정하고 표시해요.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerClusteringOptions(value: SpeakerClustering, enabled: Boolean, onChange: (SpeakerClustering) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("화자 군집 임계값", style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeakerClustering.entries.forEach { option ->
                FilterChip(selected = option == value, onClick = { onChange(option) },
                    label = { Text(option.label) }, enabled = enabled)
            }
        }
        Text("기본 0.5 · 높을수록 같은 화자로 더 쉽게 묶어요. 너무 높이면 다른 사람까지 합쳐질 수 있어요.", style = MaterialTheme.typography.bodySmall)
        Text("다음 전사·재분석부터 적용돼요. 화자 수를 직접 지정하면 전체 군집 수는 지정 인원을 우선해요.", style = MaterialTheme.typography.bodySmall)
    }
}
