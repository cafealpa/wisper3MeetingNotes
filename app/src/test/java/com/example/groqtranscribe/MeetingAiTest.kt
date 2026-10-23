package com.example.groqtranscribe

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MeetingAiTest {
    private val response = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"내부 추론","thought":true},{"text":"회의록 "},{"text":"완료"}]}}]}"""
    @Test fun geminiRequestUsesConfiguredModelAndSeparateInstruction() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(response))
            val api = GeminiTextProvider(baseUrl = server.url("/v1beta/").toString())
            assertEquals("회의록 완료", api.generate(TextGenerationRequest("gemini-custom", "요약 지시", "발언 원문"), "dummy-key"))
            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-custom:generateContent", request.path)
            assertEquals("dummy-key", request.getHeader("x-goog-api-key"))
            assertFalse(request.path!!.contains("dummy-key"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("요약 지시", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals("발언 원문", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        } finally { server.shutdown() }
    }
    @Test fun incompleteBlockedAndEmptyResponsesAreRejected() {
        listOf(response.replace("STOP", "MAX_TOKENS"), "{}",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[]}}]}""").forEach {
            try { parseGeminiText(it); fail("Invalid result accepted") } catch (_: UserError) { }
        }
    }
    @Test fun httpErrorsNeverExposeResponseContent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            for (code in listOf(400, 401, 403, 404, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private transcript and key"))
                try {
                    GeminiTextProvider(baseUrl = server.url("/").toString()).generate(TextGenerationRequest("gemini-test", "a", "b"), "dummy")
                    fail("Error ignored")
                } catch (e: UserError) { assertFalse(e.message!!.contains("private")) }
            }
        } finally { server.shutdown() }
    }
    @Test fun cancellationStopsPendingRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) {
                GeminiTextProvider(baseUrl = server.url("/").toString()).generate(TextGenerationRequest("gemini-test", "a", "b"), "dummy")
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun providerCanBeReplacedWithoutChangingMeetingWorkflow() = runBlocking {
        val requests = mutableListOf<TextGenerationRequest>()
        val fake = object : TextGenerationProvider {
            override val id = "another-provider"
            override suspend fun generate(request: TextGenerationRequest, apiKey: String): String {
                requests += request
                return "생성 결과"
            }
        }
        val service = MeetingAiService(listOf(fake))
        val settings = AiSettings(provider = fake.id, model = "other-model", minutesPrompt = "회의록 양식", summaryPrompt = "요약 양식")
        val minutes = service.generate(settings, NoteKind.MINUTES, "source", "key")
        service.generate(settings, NoteKind.SUMMARY, "source", "key")
        assertTrue(requests[0].instruction.endsWith("회의록 양식"))
        assertTrue(requests[1].instruction.endsWith("요약 양식"))
        assertEquals("other-model", minutes.model)
        assertEquals(sourceHash("source"), minutes.sourceHash)
        assertEquals(minutes, GeneratedNote.fromJson(minutes.json()))
    }
    @Test fun sourcePreservesUnknownSpeakerTimesAndEditedNames() {
        val state = UiState(meetingName = "주간 회의", speakerNames = mapOf(1 to "민수"), utterances = listOf(
            SpeakerUtterance(1.5, 2.5, 1, "다음 주 검토를 제안합니다."), SpeakerUtterance(3.0, 4.0, null, "아직 미정입니다.")))
        val source = meetingAiSource(state)
        val utterances = JSONObject(source).getJSONArray("utterances")
        assertEquals("민수", utterances.getJSONObject(0).getString("speaker"))
        assertEquals(1.5, utterances.getJSONObject(0).getDouble("startSeconds"), 0.0)
        assertEquals("U2", utterances.getJSONObject(1).getString("id"))
        assertTrue(utterances.getJSONObject(1).isNull("speakerId"))
        assertNotEquals(sourceHash(source), sourceHash(meetingAiSource(state.copy(speakerNames = mapOf(1 to "지수")))))
        assertEquals(sourceHash(source), sourceHash(meetingAiSource(state.copy(busy = true))))
    }
    @Test fun invalidModelAndBlankPromptsAreRejected() {
        listOf(AiSettings(model = "../other?key=leak"), AiSettings(minutesPrompt = " "), AiSettings(summaryPrompt = "")).forEach {
            try { it.validate(); fail("Invalid settings accepted") } catch (_: UserError) { }
        }
    }
}
