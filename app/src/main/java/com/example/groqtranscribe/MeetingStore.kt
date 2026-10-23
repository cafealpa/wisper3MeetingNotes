package com.example.groqtranscribe

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 최근 작업 한 건. 메타데이터 교체가 성공한 뒤에만 이전 음성을 삭제한다. */
class MeetingStore(private val directory: File) {
    private val metadata get() = AtomicFile(File(directory, "meeting.json"))

    @Synchronized fun save(state: UiState): UiState {
        check(directory.isDirectory || directory.mkdirs())
        var copied: File? = null
        val audio = state.selected?.let { source ->
            if (source.file.parentFile?.canonicalFile == directory.canonicalFile) source
            else {
                val target = File(directory, "audio-${UUID.randomUUID()}.bin")
                copied = target
                try { source.file.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out); out.fd.sync() } } }
                catch (e: Exception) { target.delete(); throw e }
                source.copy(file = target)
            }
        }
        val saved = state.copy(selected = audio)
        try {
            val bytes = encode(saved).toString().toByteArray(Charsets.UTF_8)
            val file = metadata
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        } catch (e: Exception) { copied?.delete(); throw e }
        directory.listFiles()?.filter { it.name.startsWith("audio-") && it != audio?.file }?.forEach { it.delete() }
        return saved
    }

    @Synchronized fun load(): UiState? {
        val file = metadata
        if (!file.baseFile.exists() && !File(directory, "meeting.json.bak").exists()) return null
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("version") == 1)
        val audio = root.optJSONObject("audio")?.let {
            val name = it.getString("file")
            require(name.startsWith("audio-") && File(name).name == name && !name.contains('\\'))
            File(directory, name).takeIf { path -> path.isFile }?.let { path ->
                AudioFile(path, it.getString("name"), path.length(), it.getString("mime"))
            }
        }
        val transcript = root.optJSONObject("transcript")?.let { item ->
            Transcript(item.getString("text"), item.getJSONArray("segments").objects().map {
                Segment(it.getDouble("start"), it.getDouble("end"), it.getString("text"))
            }, item.getJSONArray("words").objects().map {
                Word(it.getDouble("start"), it.getDouble("end"), it.getString("text"))
            })
        }
        val names = root.getJSONObject("names")
        return UiState(selected = audio, result = transcript,
            minutes = GeneratedNote.fromJson(root.optJSONObject("minutes")),
            summary = GeneratedNote.fromJson(root.optJSONObject("summary")),
            meetingName = root.optString("meetingName").takeIf { it.isNotBlank() },
            importedAtMillis = root.optLong("importedAtMillis", 0),
            durationSeconds = root.optDouble("durationSeconds", Double.NaN).takeIf { it.isFinite() && it > 0 },
            resultFileName = root.optString("filename").takeIf { it.isNotEmpty() },
            diarizationEnabled = root.getBoolean("diarization"), speakerCount = root.getInt("count"),
            speakerClustering = SpeakerClustering.fromStored(root.optString("speakerClustering")),
            unknownGapMode = UnknownGapMode.fromStored(root.optString("unknownGapMode")),
            speakerNames = names.keys().asSequence().associate { it.toInt() to names.getString(it) },
            utterances = root.getJSONArray("utterances").objects().map {
                SpeakerUtterance(it.getDouble("start"), it.getDouble("end"),
                    if (it.isNull("speaker")) null else it.getInt("speaker"), it.getString("text"), it.optString("diagnosis").takeIf { value -> value.isNotBlank() },
                    it.optString("contextCorrection").takeIf { value -> value.isNotBlank() },
                    it.optBoolean("manualSpeaker", true))
            }, speakerWarning = root.optString("warning").takeIf { it.isNotEmpty() },
            notice = if (audio == null && transcript != null) "저장된 전사를 복원했어. 원본 음성이 없어 화자 재분석은 사용할 수 없어." else "이전 작업을 복원했어.")
    }

    @Synchronized fun clear() {
        metadata.delete()
        check(!metadata.baseFile.exists() && !File(directory, "meeting.json.bak").exists())
        directory.listFiles()?.filter { it.name.startsWith("audio-") }?.forEach { check(it.delete()) }
    }

    private fun encode(state: UiState) = JSONObject().apply {
        put("version", 1)
        state.minutes?.let { put("minutes", it.json()) }
        state.summary?.let { put("summary", it.json()) }
        put("speakerClustering", state.speakerClustering.name)
        put("unknownGapMode", state.unknownGapMode.name)
        put("importedAtMillis", state.importedAtMillis)
        put("meetingName", state.meetingName.orEmpty())
        state.durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { put("durationSeconds", it) }
        put("filename", state.resultFileName.orEmpty())
        put("diarization", state.diarizationEnabled); put("count", state.speakerCount)
        put("warning", state.speakerWarning.orEmpty())
        put("names", JSONObject().apply { state.speakerNames.forEach { (id, name) -> put(id.toString(), name) } })
        state.selected?.let { put("audio", JSONObject().put("file", it.file.name).put("name", it.name).put("mime", it.mime)) }
        state.result?.let { transcript -> put("transcript", JSONObject().apply {
            put("text", transcript.text)
            put("segments", JSONArray().apply { transcript.segments.forEach { put(timed(it.start, it.end, it.text)) } })
            put("words", JSONArray().apply { transcript.words.forEach { put(timed(it.start, it.end, it.text)) } })
        }) }
        put("utterances", JSONArray().apply { state.utterances.forEach {
            put(timed(it.start, it.end, it.text).put("speaker", it.speaker ?: JSONObject.NULL).put("diagnosis", it.diagnosis.orEmpty()).put("contextCorrection", it.contextCorrection.orEmpty()).put("manualSpeaker", it.manualSpeaker))
        } })
    }
    private fun timed(start: Double, end: Double, text: String) = JSONObject().put("start", start).put("end", end).put("text", text)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
