package com.example.groqtranscribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BloomTheme { TranscriptionApp() } }
    }
}

@Composable
fun TranscriptionApp(model: TranscriptionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var renamingSpeaker by remember { mutableStateOf<Int?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    DisposableEffect(activity, state.busy) {
        if (state.busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::selectFile) }
    MeetingScreen(state = state, onSettings = { settings = true },
        onSelectFile = { if (state.result != null) confirmReplace = true else picker.launch(arrayOf("*/*")) },
        onTranscribe = model::transcribe, onCancel = model::cancel,
        onDiarization = model::setDiarization, onSpeakerCount = model::setSpeakerCount, onUnknownGapMode = model::setUnknownGapMode, onSpeakerClustering = model::setSpeakerClustering,
        onReanalyze = model::reanalyzeSpeakers, onEdit = { editingIndex = it }, onRenameMeeting = model::renameMeeting,
        onGenerateNote = model::generateNote,
        onCopy = { text ->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("회의 기록", text))
            Toast.makeText(context, "내용을 복사했어.", Toast.LENGTH_SHORT).show()
        })
    if (settings) BloomSheet({ settings = false }) {
        Text("설정", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { InfoBox(it, true) }
        KeySettings(state, model::saveKey, model::deleteKey, model::clearSavedMeeting, model::setUnknownGapMode, model::setSpeakerClustering)
        AiSettingsContent(state, model::saveAiSettings, model::saveAiKey, model::deleteAiKey)
        BloomButton("닫기", { settings = false })
    }
    editingIndex?.let { index ->
        state.utterances.getOrNull(index)?.let { utterance ->
            SpeakerEditDialog(utterance.speaker, (state.utterances.mapNotNull { it.speaker } + state.speakerNames.keys).distinct().sorted(), state.speakerNames,
                onDismiss = { editingIndex = null }, onSave = { speaker, name, all -> model.editSpeaker(index, speaker, name, all); editingIndex = null },
                utterance = utterance, matchingCount = state.utterances.count { it.speaker == utterance.speaker },
                onRename = { renamingSpeaker = utterance.speaker; editingIndex = null },
                onAllowAutomatic = { model.allowAutomaticSpeaker(index); editingIndex = null })
        }
    }
    renamingSpeaker?.let { id ->
        SpeakerNameDialog(id, state.speakerNames, { renamingSpeaker = null }) { name ->
            model.editSpeakerName(id, name)
            renamingSpeaker = null
        }
    }
    if (confirmReplace) BloomSheet({ confirmReplace = false }) {
        Text("새 파일을 열까요?", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        Text("최근 작업 한 건만 저장해요. 새 파일을 선택하면 현재 전사와 화자 수정 내용이 교체돼요. 필요한 내용을 먼저 복사해 주세요.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BloomButton("닫기", { confirmReplace = false })
            BloomButton("새 파일 선택", { confirmReplace = false; picker.launch(arrayOf("*/*")) }, Modifier.weight(1f), primary = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakerEditDialog(current: Int?, ids: List<Int>, names: Map<Int, String>, onDismiss: () -> Unit,
    onSave: (Int?, String, Boolean) -> Unit, utterance: SpeakerUtterance? = null,
    matchingCount: Int = 1, onRename: () -> Unit = {}, onAllowAutomatic: () -> Unit = {}) {
    var selected by remember { mutableStateOf(current) }
    var name by remember { mutableStateOf("") }
    var allMatching by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val newId = (ids.maxOrNull() ?: 0) + 1
    val colors = MaterialTheme.colorScheme
    BloomSheet(onDismiss) {
        Text("화자 변경", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        utterance?.let { Text("${compactTimestamp(it.start)} · ${it.text.take(45)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        Text("변경할 화자", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
                Row(Modifier.padding(11.dp).heightIn(min = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected == newId) "새 화자 추가" else speakerLabel(selected, names), fontSize = 16.sp)
                    BloomIcon(R.drawable.ic_chevron_down)
                }
            }
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                (ids.map { it as Int? } + listOf(null, newId)).forEach { id ->
                    DropdownMenuItem(text = { Text(if (id == newId) "새 화자 추가" else speakerLabel(id, names)) },
                        onClick = { selected = id; name = names[id].orEmpty(); expanded = false })
                }
            }
        }
        if (selected == newId) {
            OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("화자 이름 (비워두면 기본 번호)") }, singleLine = true, shape = RoundedCornerShape(10.dp))
        }
        Text("적용 범위", style = MaterialTheme.typography.bodySmall)
        listOf(false to "이 발화만", true to "${speakerLabel(current, names)}의 모든 발화 · ${matchingCount}개").forEach { (all, label) ->
            Row(Modifier.fillMaxWidth().selectable(selected = allMatching == all, role = Role.RadioButton,
                onClick = { allMatching = all }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = allMatching == all, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (utterance?.manualSpeaker == true) {
            Text("이 발화는 재분석해도 배정을 유지해요.", style = MaterialTheme.typography.bodySmall)
            TextButton(onAllowAutomatic) { Text("이 발화의 자동 갱신 허용") }
        }
        if (current != null) TextButton(onRename) { Text("${speakerLabel(current, names)} 이름 변경") }
        Text("현재 전사 결과에만 적용하고 기기에 저장해요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BloomButton("닫기", onDismiss)
            BloomButton(if (allMatching) "${matchingCount}개 발화 변경" else "이 발화 변경", { onSave(selected, name, allMatching) },
                Modifier.weight(1f), enabled = selected != current, primary = true)
        }
    }
}

@Composable
fun SpeakerNameDialog(id: Int, names: Map<Int, String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(id) { mutableStateOf(names[id].orEmpty()) }
    BloomSheet(onDismiss) {
        Text("화자 이름 변경", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        Text("${speakerLabel(id, names)}의 모든 발화에 같은 이름이 표시돼요. 발화의 화자 배정은 유지돼요.")
        OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("화자 이름 (비워두면 기본 번호)") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BloomButton("취소", onDismiss)
            BloomButton("이름 저장", { onSave(name) }, Modifier.weight(1f), primary = true)
        }
    }
}

@Composable
internal fun KeySettings(state: UiState, save: (String) -> Unit, delete: () -> Unit, clearMeeting: () -> Unit = {},
    onUnknownGapMode: (UnknownGapMode) -> Unit = {},
    onSpeakerClustering: (SpeakerClustering) -> Unit = {}) {
    var input by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf(!state.hasKey) }
    var confirmDelete by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Groq API 키"); Badge(if (state.hasKey) "등록됨" else "등록 필요")
        }
        if (state.hasKey) TextButton({ editKey = !editKey }, enabled = !state.busy) { Text(if (editKey) "키 입력 닫기" else "API 키 변경") }
        if (editKey) {
            Text("키는 기기에 암호화해 저장하며 다시 표시하지 않아요.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(input, { input = it }, label = { Text("Groq API 키") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !state.busy && state.ready, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            BloomButton("키 저장", { save(input); input = "" }, enabled = input.isNotBlank() && !state.busy && state.ready, primary = true)
            if (state.hasKey) TextButton({ delete(); input = "" }, enabled = !state.busy && state.ready) { Text("저장된 키 삭제") }
        }
        InfoBox("음성 전사: Groq\n화자 구분: 기기 내 분석\n지원 파일 크기: 최대 25 MB")
        UnknownGapOptions(state.unknownGapMode, state.ready && !state.busy, onUnknownGapMode)
        SpeakerClusteringOptions(state.speakerClustering, state.ready && !state.busy, onSpeakerClustering)
        Text("최근 작업 한 건과 원본 음성을 기기에 자동 저장해요.", style = MaterialTheme.typography.bodySmall)
        TextButton({ confirmDelete = true }, enabled = state.ready && !state.busy) { Text("저장된 전사와 음성 삭제") }
        Text("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("저장된 작업 삭제") },
        text = { Text("전사, 화자 이름과 배정, 보관한 음성을 삭제해요. API 키와 원래 선택한 파일은 유지돼요.") },
        confirmButton = { TextButton({ confirmDelete = false; clearMeeting() }) { Text("삭제") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("취소") } })
}

fun timestamp(seconds: Double): String {
    val millis = (seconds.coerceAtLeast(0.0) * 1000).toLong()
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000)
}
