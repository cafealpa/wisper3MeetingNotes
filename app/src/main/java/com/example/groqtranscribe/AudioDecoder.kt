package com.example.groqtranscribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder

const val DIARIZATION_SAMPLE_RATE = 16000

class AudioDecoder(private val context: Context) {
    suspend fun decode(file: File, progress: (Double) -> Unit = {}): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val temp = File.createTempFile("diarization_", ".pcm", context.cacheDir)
        var transferred = false
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UserError("이 파일에서 디코딩 가능한 음성 트랙을 찾지 못했어.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var frames = 0L
            var count = 0L
            var lastProgressSecond = -1L
            // 64MB 여유 공간을 남긴다. 전체 PCM은 heap에 적재하지 않는다.
            val maxSamples = ((context.cacheDir.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0) / 4)
            val coroutine = currentCoroutineContext()
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                var resampler = PcmResampler(rate) {
                    if (count >= maxSamples) throw UserError("음성 변환을 위한 임시 저장 공간이 부족해. 전체 전사는 유지돼.")
                    output.writeFloat(it)
                    count++
                }
                var lastOutput = SystemClock.elapsedRealtime()
                while (!outputDone) {
                    coroutine.ensureActive()
                    if (SystemClock.elapsedRealtime() - lastOutput > 60_000) throw UserError("음성 변환이 응답하지 않아 중단했어.")
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decoded = codec.outputFormat
                            val newRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (frames > 0 && newRate != rate) throw UserError("샘플레이트가 변경되는 음성 파일은 화자 분석을 지원하지 않아.")
                            rate = newRate
                            channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            if (frames == 0L) resampler = PcmResampler(rate) {
                                if (count >= maxSamples) throw UserError("음성 변환을 위한 임시 저장 공간이 부족해. 전체 전사는 유지돼.")
                                output.writeFloat(it); count++
                            }
                        }
                        else -> if (index >= 0) {
                            lastOutput = SystemClock.elapsedRealtime()
                            try {
                                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                                    throw UserError("지원하지 않는 PCM 형식이야.")
                                require(channels > 0 && rate > 0)
                                val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val expectedFrame = (info.presentationTimeUs.coerceAtLeast(0) * rate / 1_000_000)
                                // 컨테이너의 빈 시간 구간을 보존한다. 반올림 오차(2프레임)는 무시한다.
                                if (info.size > 0 && expectedFrame > frames + 2) {
                                    while (frames < expectedFrame) {
                                        if (frames % 4096 == 0L) coroutine.ensureActive()
                                        resampler.accept(0f); frames++
                                    }
                                }
                                while (buffer.remaining() >= sampleBytes * channels) {
                                    if (frames % 4096 == 0L) coroutine.ensureActive()
                                    var sum = 0f
                                    repeat(channels) { sum += if (sampleBytes == 4) buffer.float else buffer.short / 32768f }
                                    resampler.accept((sum / channels).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f })
                                    frames++
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                if (count / 16000 != lastProgressSecond) {
                                    lastProgressSecond = count / 16000
                                    progress(lastProgressSecond.toDouble())
                                }
                            } finally { codec.releaseOutputBuffer(index, false) }
                        }
                    }
                }
            }
            if (count < 1600) throw UserError("화자 분석에 사용할 음성이 너무 짧아.")
            coroutine.ensureActive()
            return DecodedAudio(temp, count).also { transferred = true }
        } finally {
            codec?.release()
            extractor.release()
            if (!transferred) temp.delete()
        }
    }
}
