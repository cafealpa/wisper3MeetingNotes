package com.example.groqtranscribe

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class UiState(
    val ready: Boolean = false,
    val hasKey: Boolean = false,
    val selected: AudioFile? = null,
    val resultFileName: String? = null,
    val result: Transcript? = null,
    val diarizationEnabled: Boolean = true,
    val speakerCount: Int = 0,
    val speakerClustering: SpeakerClustering = SpeakerClustering.DEFAULT,
    val unknownGapMode: UnknownGapMode = UnknownGapMode.CONSERVATIVE,
    val utterances: List<SpeakerUtterance> = emptyList(),
    val speakerNames: Map<Int, String> = emptyMap(),
    val speakerStatus: String = "",
    val speakerWarning: String? = null,
    val busy: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val notice: String? = null,
    val importedAtMillis: Long = 0,
    val meetingName: String? = null,
    val durationSeconds: Double? = null,
    val progress: MeetingProgress? = null,
    val aiSettings: AiSettings = AiSettings(),
    val hasAiKey: Boolean = false,
    val generatingNote: NoteKind? = null,
    val minutes: GeneratedNote? = null,
    val summary: GeneratedNote? = null
)

class TranscriptionViewModel @JvmOverloads constructor(application: Application,
    private val meetingAi: MeetingAiService = MeetingAiService(),
    private val meetingStore: MeetingStore = MeetingStore(java.io.File(application.noBackupFilesDir, "current-meeting"))) : AndroidViewModel(application) {
    private val keys = ApiKeyStore(application)
    private val files = AudioFiles(application)
    private val api = GroqApi()
    private val aiSettingsStore = AiSettingsStore(application)
    private val aiKeys = ApiKeyStore(application, "gemini")
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    fun saveAiSettings(value: AiSettings) {
        if (!state.value.ready || state.value.busy) return
        try { value.validate() } catch (e: Exception) { reportError(e); return }
        mutable.update { it.copy(busy = true, error = null, notice = null, status = "AI 설정 저장 중") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { aiSettingsStore.save(value) }
                mutable.update { it.copy(aiSettings = value, notice = "모델과 프롬프트를 저장했어.") }
            } catch (_: Exception) { mutable.update { it.copy(error = "AI 설정을 저장하지 못했어.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun saveAiKey(value: String) = changeAiKey(value.trim())
    fun deleteAiKey() = changeAiKey(null)
    private fun changeAiKey(value: String?) {
        if (!state.value.ready || state.value.busy) return
        if (value != null && (value.isBlank() || value.any { it.code !in 33..126 })) {
            mutable.update { it.copy(error = "공백 없이 유효한 Gemini API 키를 입력해 줘.") }; return
        }
        mutable.update { it.copy(busy = true, error = null, notice = null, status = "Gemini 키 저장 중") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (value == null) aiKeys.clear() else aiKeys.save(value) }
                mutable.update { it.copy(hasAiKey = value != null, notice = if (value == null) "Gemini 키를 삭제했어." else "Gemini 키를 안전하게 저장했어.") }
            } catch (_: Exception) { mutable.update { it.copy(error = "Gemini 키 설정을 저장하지 못했어.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun generateNote(kind: NoteKind) {
        val snapshot = state.value
        if (!snapshot.ready || snapshot.busy) return
        if (snapshot.utterances.isEmpty()) {
            mutable.update { it.copy(error = "화자 분석을 먼저 완료해 줘.") }; return
        }
        if (!snapshot.hasAiKey) {
            mutable.update { it.copy(error = "설정에서 Gemini API 키를 등록해 줘.") }; return
        }
        mutable.update { it.copy(busy = true, generatingNote = kind, error = null, notice = null, status = "${kind.label} 작성 중") }
        operation = viewModelScope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    val key = aiKeys.read()
                    if (key.isBlank()) throw UserError("설정에서 Gemini API 키를 등록해 줘.")
                    meetingAi.generate(snapshot.aiSettings, kind, meetingAiSource(snapshot), key)
                }
                // 파일 교체/화자 편집은 busy 동안 차단한다. 실패나 취소 시 이전 결과는 유지한다.
                val updated = if (kind == NoteKind.MINUTES) state.value.copy(minutes = generated) else state.value.copy(summary = generated)
                mutable.value = updated
                persist(updated)
                mutable.update { it.copy(notice = "${kind.label}을 작성하고 저장했어.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally { mutable.update { it.copy(busy = false, generatingNote = null) } }
        }
    }

    fun setDiarization(enabled: Boolean) { if (!state.value.busy) mutable.update { it.copy(diarizationEnabled = enabled) } }
    fun setSpeakerCount(count: Int) { if (!state.value.busy && count in 0..4) mutable.update { it.copy(speakerCount = count) } }
    fun setUnknownGapMode(mode: UnknownGapMode) {
        val current = state.value
        if (!current.ready || current.busy || current.unknownGapMode == mode) return
        mutable.update { it.copy(busy = true, error = null) }
        operation = viewModelScope.launch {
            try { persist(current.copy(unknownGapMode = mode)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun setSpeakerClustering(value: SpeakerClustering) {
        val current = state.value
        if (!current.ready || current.busy || current.speakerClustering == value) return
        mutable.update { it.copy(busy = true, error = null) }
        operation = viewModelScope.launch {
            try { persist(current.copy(speakerClustering = value)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun renameMeeting(name: String) {
        if (!state.value.ready || state.value.busy) return
        val cleaned = name.replace('\n', ' ').replace('\r', ' ').trim().take(120)
        val updated = state.value.copy(meetingName = cleaned.takeIf { it.isNotBlank() })
        mutable.update { it.copy(busy = true, status = "제목 저장 중", error = null) }
        operation = viewModelScope.launch {
            try { persist(updated) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun editSpeaker(index: Int, id: Int?, newName: String = "", allMatching: Boolean = false) {
        val current = state.value
        if (!current.ready || current.busy || index !in current.utterances.indices || (id != null && id <= 0)) return
        val existingIds = current.utterances.mapNotNull { it.speaker } + current.speakerNames.keys
        saveSpeakerEdit(current.copy(
            utterances = reassignUtterance(current.utterances, index, id, allMatching),
            speakerNames = if (id != null && id !in existingIds) renameSpeaker(current.speakerNames, id, newName) else current.speakerNames))
    }

    fun allowAutomaticSpeaker(index: Int) {
        val current = state.value
        if (!current.ready || current.busy || index !in current.utterances.indices) return
        saveSpeakerEdit(current.copy(utterances = current.utterances.mapIndexed { i, value ->
            if (i == index) value.copy(manualSpeaker = false) else value
        }))
    }

    fun editSpeakerName(id: Int, name: String) {
        val current = state.value
        if (!current.ready || current.busy || current.utterances.none { it.speaker == id }) return
        saveSpeakerEdit(current.copy(speakerNames = renameSpeaker(current.speakerNames, id, name)))
    }

    private fun saveSpeakerEdit(updated: UiState) {
        mutable.update { it.copy(busy = true, status = "화자 수정 내용 저장 중", error = null) }
        operation = viewModelScope.launch {
            try { persist(updated); mutable.update { it.copy(notice = "화자 수정 내용을 저장했어.") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = "수정 내용을 저장하지 못했어. 다시 시도해 줘.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun persist(updated: UiState) {
        val before = state.value
        val saved = try { withContext(Dispatchers.IO) { meetingStore.save(updated) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw UserError("작업을 저장하지 못했어. 저장 공간을 확인해 줘. 화면의 전사는 복사할 수 있지만 마지막 저장 이후의 변경은 앱 종료 시 사라질 수 있어.") }
        // 저장 중에도 로컬 분석 콜백은 진행될 수 있으므로 최신 진행 정보는 되돌리지 않는다.
        mutable.update { current -> saved.copy(
            progress = if (current.progress != before.progress) current.progress else saved.progress,
            durationSeconds = if (current.durationSeconds != before.durationSeconds) current.durationSeconds else saved.durationSeconds)
        }
    }

    fun clearSavedMeeting() {
        if (!state.value.ready || state.value.busy) return
        mutable.update { it.copy(busy = true, status = "저장된 작업 삭제 중") }
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { meetingStore.clear() }
                mutable.value = UiState(ready = true, hasKey = state.value.hasKey, aiSettings = state.value.aiSettings,
                    hasAiKey = state.value.hasAiKey, notice = "저장된 전사와 음성을 삭제했어.")
            } catch (_: Exception) { mutable.update { it.copy(busy = false, error = "저장된 작업을 삭제하지 못했어.") } }
        }
    }

    init {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { files.clearStaleFiles(); meetingStore.load() }
                if (restored != null) {
                    val duration = restored.durationSeconds ?: withContext(Dispatchers.IO) { restored.selected?.let { audioDurationSeconds(it.file) } }
                    mutable.value = restored.copy(durationSeconds = duration)
                }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "저장된 작업을 읽지 못했어. 기존 파일은 보존했어.") }
            }
            try {
                val hasKey = withContext(Dispatchers.IO) { keys.read().isNotEmpty() }
                mutable.update { it.copy(hasKey = hasKey) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "저장된 API 키를 읽지 못했어. 설정에서 키를 다시 저장해 줘.") }
            }
            try {
                val settings = withContext(Dispatchers.IO) { aiSettingsStore.load() }
                mutable.update { it.copy(aiSettings = settings) }
                val hasKey = withContext(Dispatchers.IO) { aiKeys.read().isNotBlank() }
                mutable.update { it.copy(hasAiKey = hasKey) }
            } catch (_: Exception) { mutable.update { it.copy(error = "AI 설정이나 Gemini 키를 읽지 못했어. 설정에서 다시 저장해 줘.") } }
            finally { mutable.update { it.copy(ready = true) } }
        }
    }

    fun saveKey(value: String) {
        val key = value.trim()
        if (key.isEmpty() || key.any { it.code !in 33..126 }) {
            mutable.update { it.copy(error = "공백 없이 유효한 API 키를 입력해 줘.") }
            return
        }
        changeKey { keys.save(key); true }
    }
    fun deleteKey() = changeKey { keys.clear(); false }
    private fun changeKey(block: () -> Boolean) {
        if (!state.value.ready || state.value.busy) return
        mutable.update { it.copy(busy = true, error = null, notice = null, status = "설정 저장 중") }
        viewModelScope.launch {
            try {
                val hasKey = withContext(Dispatchers.IO) { block() }
                mutable.update { it.copy(hasKey = hasKey, notice = if (hasKey) "API 키를 안전하게 저장했어." else "API 키를 삭제했어.") }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "API 키 설정을 저장하지 못했어. 다시 시도해 줘.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun selectFile(uri: Uri) {
        if (!state.value.ready || state.value.busy) return
        val previous = state.value
        mutable.update { it.copy(error = null, busy = true, status = "음성 파일 확인 및 저장 중") }
        operation = viewModelScope.launch {
            var prepared: AudioFile? = null
            try {
                withContext(Dispatchers.IO) { prepared = files.prepare(uri) }
                val duration = withContext(Dispatchers.IO) { prepared?.let { audioDurationSeconds(it.file) } }
                persist(UiState(ready = true, hasKey = previous.hasKey, selected = prepared,
                    aiSettings = previous.aiSettings, hasAiKey = previous.hasAiKey,
                    importedAtMillis = System.currentTimeMillis(), durationSeconds = duration,
                    diarizationEnabled = previous.diarizationEnabled, speakerCount = previous.speakerCount, unknownGapMode = previous.unknownGapMode, speakerClustering = previous.speakerClustering))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { reportError(e)
            } finally {
                prepared?.file?.delete()
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun transcribe() {
        val snapshot = state.value
        val selected = snapshot.selected ?: return
        if (!snapshot.hasKey || snapshot.busy) return
        mutable.update { it.copy(busy = true, error = null, notice = null, result = null, utterances = emptyList(), minutes = null, summary = null, speakerWarning = null, speakerStatus = "", status = "Groq에서 한국어 전사 중",
            progress = MeetingProgress(speakers = if (snapshot.diarizationEnabled) StepStatus.RUNNING else StepStatus.SKIPPED,
                alignment = if (snapshot.diarizationEnabled) StepStatus.WAITING else StepStatus.SKIPPED)) }
        operation = viewModelScope.launch {
            var cancelled = false
            try {
                val key = withContext(Dispatchers.IO) { keys.read() }
                if (key.isBlank()) throw UserError("설정에서 API 키를 저장해 줘.")
                runTranscriptionPipeline(
                    transcribe = { withContext(Dispatchers.IO) { api.transcribe(selected, key, true) } },
                    analyze = if (snapshot.diarizationEnabled) suspend { analyzeSpeakers(selected, snapshot.speakerCount, snapshot.speakerClustering) } else null,
                    onTranscript = { result ->
                        mutable.update { it.copy(result = result, resultFileName = selected.name,
                            progress = it.progress?.copy(transcript = StepStatus.COMPLETE), status = "전사 완료 · 화자 분석 마무리 중") }
                        persist(state.value)
                    },
                    onSpeakers = { result, analysis ->
                        mutable.update { it.copy(progress = it.progress?.copy(alignment = StepStatus.RUNNING)) }
                        val warning = analysis.warning ?: when {
                            result.words.isEmpty() -> "단어별 시간이 반환되지 않아 화자를 연결할 수 없어. 기존 구간별 전사를 표시할게."
                            analysis.turns.isEmpty() -> "화자를 구분할 발화 구간을 찾지 못했어."
                            else -> null
                        }
                        val utterances = if (warning == null) withContext(Dispatchers.Default) { alignSpeakers(result.words, analysis.turns, snapshot.unknownGapMode) } else emptyList()
                        persist(state.value.copy(utterances = utterances, speakerWarning = warning, speakerStatus = "",
                            progress = state.value.progress?.copy(
                                speakers = if (analysis.warning == null) StepStatus.COMPLETE else StepStatus.FAILED,
                                alignment = if (warning == null) StepStatus.COMPLETE else StepStatus.FAILED)))
                    }
                )
            } catch (e: CancellationException) { cancelled = true; throw e
            } catch (e: Exception) { reportError(e)
            } finally {
                mutable.update { it.copy(busy = false, speakerStatus = "", progress = it.progress?.finished(cancelled)) }
            }
        }
    }

    private suspend fun analyzeSpeakers(selected: AudioFile, count: Int, clustering: SpeakerClustering): List<SpeakerTurn> = withContext(Dispatchers.IO) {
        mutable.update { it.copy(speakerStatus = "화자 분석용 음성 변환 중") }
        AudioDecoder(getApplication()).decode(selected.file).use { audio ->
            mutable.update { it.copy(durationSeconds = audio.durationSeconds) }
            withContext(Dispatchers.Default) {
                SpeakerDiarizer(getApplication()).analyze(audio, count, { percent ->
                    mutable.update { it.copy(speakerStatus = "구간 분석 및 전체 화자 연결 $percent%",
                        progress = it.progress?.copy(percent = percent.coerceIn(0, 100),
                            speakers = if (percent == 100) StepStatus.COMPLETE else StepStatus.RUNNING)) }
                }, clustering = clustering, windowsProgress = { done, total ->
                    mutable.update { it.copy(progress = it.progress?.copy(completedWindows = done, totalWindows = total)) }
                })
            }
        }
    }

    fun reanalyzeSpeakers() {
        val snapshot = state.value
        val transcript = snapshot.result ?: return
        val audio = snapshot.selected ?: return
        if (snapshot.busy) return
        if (transcript.words.isEmpty()) {
            mutable.update { it.copy(error = "기존 전사에 단어별 시간이 없어 화자만 재분석할 수 없어.") }
            return
        }
        mutable.update { it.copy(busy = true, error = null, notice = null, status = "기존 전사를 유지하며 화자만 재분석 중",
            progress = MeetingProgress(transcript = StepStatus.COMPLETE, speakers = StepStatus.RUNNING)) }
        operation = viewModelScope.launch {
            var cancelled = false
            try {
                val updated = reanalyzeTranscriptSpeakers(transcript, snapshot.unknownGapMode) {
                    analyzeSpeakers(audio, snapshot.speakerCount, snapshot.speakerClustering).also {
                        mutable.update { it.copy(progress = it.progress?.copy(alignment = StepStatus.RUNNING)) }
                    }
                }
                val preserved = preserveSpeakerEdits(transcript.words, snapshot.utterances, updated, snapshot.speakerNames.keys)
                persist(state.value.copy(utterances = preserved, speakerNames = snapshot.speakerNames, speakerWarning = null,
                    progress = state.value.progress?.copy(alignment = StepStatus.COMPLETE),
                    notice = "화자 이름과 수동 배정은 유지하고 자동 구간을 갱신했어."))
            } catch (e: CancellationException) { cancelled = true; throw e
            } catch (_: LinkageError) {
                mutable.update { it.copy(error = "화자 분석 라이브러리를 실행하지 못했어. 기존 결과는 유지돼.") }
            } catch (e: Exception) { reportError(e)
            } finally {

                mutable.update { it.copy(busy = false, speakerStatus = "", progress = it.progress?.finished(cancelled)) }
            }
        }
    }

    fun cancel() {
        operation?.cancel()
        mutable.update { it.copy(notice = if (it.generatingNote != null) "AI 생성을 취소했어. 이전 생성 결과는 유지돼."
            else "취소를 요청했어. 진행 중인 로컬 분석이 안전하게 종료될 때까지 잠시 기다려 줘. 완료된 전사 결과는 유지돼.") }
    }
    private fun reportError(e: Exception) {
        val message = when (e) {
            is UserError -> e.message
            is java.net.SocketTimeoutException -> "요청 시간이 초과됐어. 연결을 확인하고 파일을 다시 선택해 줘."
            is IOException -> "파일을 읽거나 서버에 연결하지 못했어. 네트워크와 파일 접근 권한을 확인해 줘."
            is SecurityException -> "파일 접근 권한이 없어. 시스템 파일 선택기에서 다시 선택해 줘."
            else -> "처리 중 오류가 발생했어. API 키와 파일을 확인한 뒤 다시 시도해 줘."
        }
        mutable.update { it.copy(error = message) }
    }
}

