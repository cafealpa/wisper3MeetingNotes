package com.example.groqtranscribe

import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TranscriptionTest {
    private val discard = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
    @Test fun exactLimitIsAccepted() = runBlocking {
        assertEquals(MAX_AUDIO_BYTES, copyLimited(ByteArrayInputStream(ByteArray(MAX_AUDIO_BYTES.toInt())), discard))
    }
    @Test fun oversizedStreamIsRejectedEvenWithoutMetadata() = runBlocking {
        try {
            copyLimited(ByteArrayInputStream(ByteArray(MAX_AUDIO_BYTES.toInt() + 1)), discard)
            fail("Oversized stream accepted")
        } catch (e: UserError) { assertTrue(e.message!!.contains("25MB")) }
    }
    @Test fun emptyFileIsRejected() = runBlocking {
        try { copyLimited(ByteArrayInputStream(byteArrayOf()), discard); fail("Empty stream accepted") }
        catch (e: UserError) { assertTrue(e.message!!.contains("비어")) }
    }
    @Test fun multipartAndKoreanResult() = runBlocking {
        val server = MockWebServer()
        server.start()
        val file = File.createTempFile("audio", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            server.enqueue(MockResponse().setBody("""{"text":"안녕하세요","segments":[{"start":0.2,"end":1.5,"text":"안녕하세요"}]}"""))
            val api = GroqApi(endpoint = server.url("/openai/v1/audio/transcriptions").toString())
            val result = api.transcribe(AudioFile(file, "회의.m4a", 3, "audio/mp4"), "test-key")
            assertEquals("안녕하세요", result.text)
            assertEquals(0.2, result.segments.single().start, 0.001)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/openai/v1/audio/transcriptions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val parts = body.split("--$boundary")
            for ((field, value) in listOf("model" to "whisper-large-v3-turbo", "language" to "ko",
                "response_format" to "verbose_json", "timestamp_granularities[]" to "segment")) {
                val part = parts.single { it.substringBefore("\r\n\r\n").contains("name=\"$field\"") }
                assertEquals(value, part.substringAfter("\r\n\r\n").removeSuffix("\r\n"))
            }
            assertTrue(body.contains("name=\"file\"; filename="))
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun rateLimitDoesNotExposeServerBody() = runBlocking {
        val server = MockWebServer()
        server.start()
        val file = File.createTempFile("audio", ".mp3").apply { writeText("test") }
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("sensitive upstream details"))
            try {
                GroqApi(endpoint = server.url("/").toString()).transcribe(AudioFile(file, "test.mp3", 4, "audio/mpeg"), "test-key")
                fail("HTTP failure ignored")
            } catch (e: UserError) {
                assertTrue(e.message!!.contains("한도"))
                assertFalse(e.message!!.contains("sensitive"))
            }
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun missingSegmentsAreAllowed() { assertTrue(parseTranscript("""{"text":"텍스트"}""").segments.isEmpty()) }
    @Test fun storedAudioUploadsWithOriginalFormatInsteadOfBinExtension() = runBlocking {
        val server = MockWebServer()
        server.start()
        // MeetingStore는 원본을 .bin으로 보관한다. 파일 내용은 그대로 업로드해야 한다.
        val bytes = byteArrayOf(0, 1, 2, 127)
        val stored = File.createTempFile("audio-", ".bin").apply { writeBytes(bytes) }
        try {
            val api = GroqApi(endpoint = server.url("/").toString())
            for (extension in listOf("m4a", "mp3", "mp4", "mpeg", "mpga", "wav", "flac", "ogg", "webm")) {
                server.enqueue(MockResponse().setBody("""{"text":"전사 완료"}"""))
                val result = api.transcribe(AudioFile(stored, "개인 회의.${extension.uppercase()}",
                    bytes.size.toLong(), "application/octet-stream"), "test-key", true)
                assertEquals("전사 완료", result.text)
                val request = server.takeRequest()
                val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
                val part = request.body.readUtf8().split("--$boundary")
                    .single { it.substringBefore("\r\n\r\n").contains("name=\"file\"") }
                val headers = part.substringBefore("\r\n\r\n")
                assertTrue("Original $extension format must survive storage", headers.contains("filename=\"audio.$extension\""))
                assertFalse(headers.contains(".bin"))
                assertFalse(headers.contains("개인 회의"))
                assertTrue(headers.contains("Content-Type: application/octet-stream"))
                assertArrayEquals(bytes, part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray())
            }
        } finally { stored.delete(); server.shutdown() }
    }
    @Test fun speakerModeRequestsBothTimestampGranularities() = runBlocking {
        val server = MockWebServer()
        server.start()
        val file = File.createTempFile("audio", ".mp3").apply { writeText("test") }
        try {
            server.enqueue(MockResponse().setBody("""{"text":"네","words":[{"start":0,"end":1,"word":"네"}]}"""))
            val result = GroqApi(endpoint = server.url("/").toString())
                .transcribe(AudioFile(file, "test.mp3", 4, "audio/mpeg"), "test-key", true)
            val request = server.takeRequest()
            val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
            val values = request.body.readUtf8().split("--$boundary")
                .filter { it.substringBefore("\r\n\r\n").contains("name=\"timestamp_granularities[]\"") }
                .map { it.substringAfter("\r\n\r\n").removeSuffix("\r\n") }
            assertEquals(listOf("segment", "word"), values)
            assertEquals("네", result.words.single().text)
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun timestampsIncludeHoursAndMilliseconds() { assertEquals("01:01:01.250", timestamp(3661.25)) }
}
