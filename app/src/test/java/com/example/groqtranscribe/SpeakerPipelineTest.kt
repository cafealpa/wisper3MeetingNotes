package com.example.groqtranscribe

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpeakerPipelineTest {
    @Test fun speakerOnlyRetryReusesTheSameTranscript() = runBlocking {
        val transcript = Transcript("안녕 네", emptyList(), listOf(Word(0.0, 1.0, "안녕"), Word(1.0, 2.0, "네")))
        val before = transcript.copy()
        val updated = reanalyzeTranscriptSpeakers(transcript) {
            listOf(SpeakerTurn(0.0, 1.0, 0), SpeakerTurn(1.0, 2.0, 1))
        }
        assertEquals(listOf(1, 2), updated.map { it.speaker })
        assertEquals(before, transcript)
    }
    @Test fun speakerOnlyRetryDoesNotCommitFailedOrCancelledResults() = runBlocking {
        val transcript = Transcript("네", emptyList(), listOf(Word(0.0, 1.0, "네")))
        val original = listOf(SpeakerUtterance(0.0, 1.0, 1, "네"))
        var displayed = original
        try { displayed = reanalyzeTranscriptSpeakers(transcript) { emptyList() }; fail() } catch (_: UserError) { }
        assertSame(original, displayed)
        try { displayed = reanalyzeTranscriptSpeakers(transcript) { throw CancellationException() }; fail() } catch (_: CancellationException) { }
        assertSame(original, displayed)
    }
    @Test fun progressCallbackExposesTheExactJniSignature() {
        val method = DiarizationProgressCallback::class.java.getDeclaredMethod("invoke", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        assertEquals(java.lang.Integer::class.java, method.returnType)
        var progress = 0
        val callback = DiarizationProgressCallback { done, _ -> progress = done }
        assertEquals(0, method.invoke(callback, 3, 10, 0L))
        assertEquals(3, progress)
    }
    @Test fun splitsOneAsrSegmentAtSpeakerBoundary() {
        val words = listOf(Word(0.0, 1.0, "안녕"), Word(1.0, 2.0, "하세요"), Word(2.0, 3.0, "반가워요"))
        val result = alignSpeakers(words, listOf(SpeakerTurn(0.0, 2.0, 9), SpeakerTurn(2.0, 3.0, 3)))
        assertEquals(listOf(1, 2), result.map { it.speaker })
        assertEquals("안녕 하세요", result[0].text)
    }
    @Test fun overlappingSpeakersAndSilenceRemainUnknown() {
        val result = alignSpeakers(listOf(Word(0.0, 1.0, "겹말"), Word(4.0, 5.0, "공백")),
            listOf(SpeakerTurn(0.0, 1.0, 0), SpeakerTurn(0.0, 1.0, 1)))
        assertTrue(result.all { it.speaker == null })
        assertEquals(2, result.size)
    }
    @Test fun majorityCoverageAssignsButAmbiguousBoundaryDoesNot() {
        val result = alignSpeakers(listOf(Word(0.0, 1.0, "네")), listOf(SpeakerTurn(0.0, 0.8, 5), SpeakerTurn(0.8, 1.0, 7)))
        assertEquals(1, result.single().speaker)
        assertNull(alignSpeakers(listOf(Word(0.0, 1.0, "네")), listOf(SpeakerTurn(0.0, 0.5, 5), SpeakerTurn(0.5, 1.0, 7))).single().speaker)
    }
    @Test fun invalidWordTimestampsDoNotBreakOriginalText() {
        val result = parseTranscript("""{"text":"원문","words":[{"start":0,"end":1,"word":"네"},{"start":2,"end":1,"word":"오류"}]}""")
        assertEquals("원문", result.text)
        assertEquals(1, result.words.size)
    }
    @Test fun diarizationFailureKeepsTranscript() = runBlocking {
        val expected = Transcript("전사 성공", emptyList())
        var displayed: Transcript? = null
        var warning: String? = null
        runTranscriptionPipeline({ expected }, { throw UserError("분석 실패") }, { displayed = it }, { _, analysis -> warning = analysis.warning })
        assertEquals(expected, displayed)
        assertEquals("분석 실패", warning)
    }
    @Test fun transcriptIsShownBeforeSlowAnalysisFinishes() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        var displayed = false
        runTranscriptionPipeline({ Transcript("성공", emptyList()) }, { gate.await(); emptyList() },
            { displayed = true; gate.complete(Unit) }, { _, _ -> assertTrue(displayed) })
    }
    @Test fun cancellationIsNotConvertedToAnAnalysisWarning() = runBlocking {
        try {
            runTranscriptionPipeline({ Transcript("완료", emptyList()) }, { throw CancellationException() }, {}, { _, _ -> fail("취소를 실패로 처리") })
            fail("취소가 전달되지 않음")
        } catch (_: CancellationException) { }
    }
    @Test fun asrFailureCancelsAnalysisBeforeReturning() = runBlocking {
        var cleaned = false
        val started = CompletableDeferred<Unit>()
        try {
            runTranscriptionPipeline({ started.await(); throw UserError("네트워크 오류") },
                { try { started.complete(Unit); awaitCancellation() } finally { cleaned = true } }, {}, { _, _ -> })
            fail("ASR 실패 누락")
        } catch (_: UserError) { assertTrue(cleaned) }
    }
    @Test fun resamplingPreservesDurationAndSuppressesAliasing() {
        fun rms(frequency: Int): Double {
            val values = mutableListOf<Float>()
            val resampler = PcmResampler(48000) { values.add(it) }
            repeat(48000) { resampler.accept(sin(2 * PI * frequency * it / 48000).toFloat()) }
            assertEquals(16000, values.size)
            return sqrt(values.drop(100).map { it * it.toDouble() }.average())
        }
        assertTrue(rms(1000) > 0.6)
        assertTrue(rms(12000) < 0.05)
    }
    @Test fun nativeRatePassesThroughUnchanged() {
        val values = mutableListOf<Float>()
        val resampler = PcmResampler(16000) { values.add(it) }
        listOf(0f, 0.5f, -0.5f, 1f).forEach(resampler::accept)
        assertEquals(listOf(0f, 0.5f, -0.5f, 1f), values)
    }
}
