package com.example.groqtranscribe

import android.content.Context
import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** JNI가 invoke(IIJ)Ljava/lang/Integer;를 찾으므로 indy lambda 대신 명시적 클래스를 사용한다. */
@Keep
internal class DiarizationProgressCallback(private val action: (Int, Int) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(processed: Int, total: Int, arg: Long): Int {
        action(processed, total)
        return 0
    }
}

class SpeakerDiarizer(private val context: Context) {
    suspend fun analyze(audio: DecodedAudio, speakerCount: Int, progress: (Int) -> Unit,
                        coreSeconds: Int = 120, windowsProgress: (Int, Int) -> Unit = { _, _ -> },
                        clustering: SpeakerClustering = SpeakerClustering.DEFAULT): List<SpeakerTurn> {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val diarizer = OfflineSpeakerDiarization(context.assets, OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "diarization/segmentation.int8.onnx"),
                numThreads = 2, provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = "diarization/nemo_en_titanet_small.onnx", numThreads = 2, provider = "cpu"),
            // 참석자 전원이 모든 구간에 등장한다고 가정하지 않는다.
            clustering = FastClusteringConfig(numClusters = -1, threshold = clustering.distance.toFloat())
        ))
        try {
            check(diarizer.sampleRate() == DIARIZATION_SAMPLE_RATE)
            val extractor = SpeakerEmbeddingExtractor(context.assets, SpeakerEmbeddingExtractorConfig(
                model = "diarization/nemo_en_titanet_small.onnx", numThreads = 2, provider = "cpu"))
            try {
                val prototypes = mutableListOf<SpeakerPrototype>()
                val pending = mutableListOf<SpeakerTurn>()
                val windows = analysisWindows(audio.sampleCount, coreSeconds)
                windowsProgress(0, windows.size)
                for (window in windows) {
                    coroutine.ensureActive()
                    val samples = audio.read(window.start, (window.end - window.start).toInt())
                    val turns = diarizer.processWithCallback(samples, DiarizationProgressCallback { done, total ->
                        if (coroutine.isActive) progress(((window.index + if (total > 0) 0.8 * done / total else 0.0) * 95 / windows.size).toInt())
                    })
                    coroutine.ensureActive()
                    val localToPrototype = mutableMapOf<Int, Int>()
                    for ((localId, localTurns) in turns.groupBy { it.speaker }) {
                        coroutine.ensureActive()
                        // 겹말을 제외한 긴 발화부터 최대 15초를 모아 대표 특징을 추출한다.
                        val clean = cleanSpeakerRegions(turns.map { SpeakerTurn(it.start.toDouble(), it.end.toDouble(), it.speaker) }, localId)
                        val pieces = mutableListOf<FloatArray>()
                        var remaining = 15 * 16000
                        for (turn in clean) {
                            if (remaining <= 0) break
                            val start = (turn.first * 16000).toInt().coerceIn(0, samples.size)
                            val end = minOf((turn.second * 16000).toInt().coerceIn(start, samples.size), start + remaining)
                            if (end > start) { pieces += samples.copyOfRange(start, end); remaining -= end - start }
                        }
                        val count = pieces.sumOf { it.size }
                        if (count < 4800) continue // 0.3초 미만은 특징 추출 근거가 부족하다.
                        val representative = FloatArray(count)
                        var offset = 0
                        for (piece in pieces) { piece.copyInto(representative, offset); offset += piece.size }
                        val stream = extractor.createStream()
                        val embedding = try {
                            stream.acceptWaveform(representative, 16000)
                            stream.inputFinished()
                            if (extractor.isReady(stream)) normalized(extractor.compute(stream)) else null
                        } finally { stream.release() }
                        if (embedding != null) {
                            if (prototypes.size >= 1024) throw UserError("이 녹음의 화자 후보가 너무 많아 분석을 중단했어. 전체 전사는 유지돼.")
                            localToPrototype[localId] = prototypes.size
                            val overlappingIds = turns.filter { other -> other.speaker != localId && localTurns.any { own ->
                                minOf(own.end, other.end) - maxOf(own.start, other.start) >= 0.5f
                            } }.map { it.speaker }.toSet()
                            prototypes += SpeakerPrototype(window.index, localId, embedding, count / 16000.0, overlappingIds)
                        }
                    }
                    val base = window.start / 16000.0
                    for (turn in turns) {
                        val start = maxOf(window.coreStart / 16000.0, base + turn.start)
                        val end = minOf(window.coreEnd / 16000.0, base + turn.end)
                        if (end > start) pending += SpeakerTurn(start, end, localToPrototype[turn.speaker] ?: -1)
                    }
                    progress((window.index + 1) * 95 / windows.size)
                    windowsProgress(window.index + 1, windows.size)
                }
                coroutine.ensureActive()
                val ids = globalSpeakerIds(prototypes, speakerCount, threshold = clustering.similarity, checkCancelled = { coroutine.ensureActive() })
                progress(100)
                // -1은 분석 근거가 부족한 화자이며 단어 매칭에서 화자 불명으로 남긴다.
                return pending.map { it.copy(speaker = if (it.speaker >= 0) ids[it.speaker] else -1) }.sortedBy { it.start }
            } finally { extractor.release() }
        } finally { diarizer.release() }
    }
}
