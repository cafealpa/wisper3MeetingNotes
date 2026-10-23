package com.example.groqtranscribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

data class SpeakerAnalysis(val turns: List<SpeakerTurn> = emptyList(), val warning: String? = null)

/** 기존 전사만 읽고 로컬 화자 결과를 계산한다. 성공한 경우에만 UI가 교체한다. */
suspend fun reanalyzeTranscriptSpeakers(
    transcript: Transcript,
    gapMode: UnknownGapMode = UnknownGapMode.CONSERVATIVE,
    analyze: suspend () -> List<SpeakerTurn>
): List<SpeakerUtterance> {
    if (transcript.words.isEmpty()) throw UserError("단어별 시간이 없어 화자를 연결할 수 없어.")
    val turns = analyze()
    if (turns.isEmpty()) throw UserError("화자 구간을 찾지 못했어. 기존 결과는 유지돼.")
    return alignSpeakers(transcript.words, turns, gapMode)
}

/** 한쪽 실패가 다른 쪽의 성공 결과를 없애지 않으며 종료 전 자식 작업을 회수한다. */
suspend fun runTranscriptionPipeline(
    transcribe: suspend () -> Transcript,
    analyze: (suspend () -> List<SpeakerTurn>)?,
    onTranscript: suspend (Transcript) -> Unit,
    onSpeakers: suspend (Transcript, SpeakerAnalysis) -> Unit
) = supervisorScope {
    val speakers = analyze?.let { action -> async {
        try { SpeakerAnalysis(action()) }
        catch (e: CancellationException) { throw e }
        catch (e: UserError) { SpeakerAnalysis(warning = e.message) }
        catch (_: Exception) { SpeakerAnalysis(warning = "화자 분석에 실패했어. 전체 전사 결과는 유지돼.") }
        catch (_: LinkageError) { SpeakerAnalysis(warning = "이 기기에서 화자 분석 라이브러리를 실행할 수 없어. 전체 전사 결과는 유지돼.") }
    } }
    try {
        val result = transcribe()
        onTranscript(result)
        speakers?.let { onSpeakers(result, it.await()) }
    } finally { speakers?.cancel() }
}
