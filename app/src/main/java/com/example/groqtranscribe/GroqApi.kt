package com.example.groqtranscribe

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Segment(val start: Double, val end: Double, val text: String)
data class Word(val start: Double, val end: Double, val text: String)
data class Transcript(val text: String, val segments: List<Segment>, val words: List<Word> = emptyList())

class GroqApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: String = "https://api.groq.com/openai/v1/audio/transcriptions"
) {
    suspend fun transcribe(audio: AudioFile, apiKey: String, withWords: Boolean = false): Transcript {
        // 내부 보관 파일은 .bin이므로 업로드에는 선택 당시의 형식을 사용한다.
        // 개인 파일명은 전송하지 않고 확장자만 유지한다.
        val uploadName = "audio.${audio.name.substringAfterLast('.', "").lowercase()}"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (withWords) addFormDataPart("timestamp_granularities[]", "word") }
            .addFormDataPart("file", uploadName, audio.file.asRequestBody(audio.mime.toMediaTypeOrNull()))
            .build()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw UserError(httpError(it.code))
                            parseTranscript(it.body?.string() ?: throw UserError("전사 응답이 비어 있어."))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}

fun httpError(code: Int): String = when (code) {
    401, 403 -> "API 키 또는 접근 권한을 확인해 줘. 설정에서 키를 다시 저장할 수 있어."
    413 -> "서버의 파일 크기 제한을 초과했어. 더 작은 파일을 선택해 줘."
    429 -> "Groq 사용량 또는 요청 한도에 도달했어. 잠시 후 다시 시도해 줘."
    400, 422 -> "처리할 수 없는 음성 파일이야. 파일 형식과 내용을 확인해 줘."
    in 500..599 -> "Groq 서버에 일시적인 문제가 있어. 잠시 후 다시 시도해 줘."
    else -> "전사 요청에 실패했어. (HTTP $code)"
}

fun parseTranscript(json: String): Transcript {
    val root = JSONObject(json)
    val text = root.getString("text")
    val segments = root.optJSONArray("segments")
    return Transcript(text, buildList {
        if (segments != null) for (index in 0 until segments.length()) {
            val item = segments.getJSONObject(index)
            add(Segment(item.getDouble("start"), item.getDouble("end"), item.getString("text")))
        }
    }, buildList {
        val words = root.optJSONArray("words")
        if (words != null) for (index in 0 until words.length()) {
            val word = words.getJSONObject(index)
            val start = word.optDouble("start", Double.NaN)
            val end = word.optDouble("end", Double.NaN)
            if (start.isFinite() && end.isFinite() && start >= 0 && end > start)
                add(Word(start, end, word.optString("word")))
        }
    })
}
