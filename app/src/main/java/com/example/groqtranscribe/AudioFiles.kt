package com.example.groqtranscribe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream

const val MAX_AUDIO_BYTES = 25_000_000L
class UserError(message: String) : Exception(message)
data class AudioFile(val file: File, val name: String, val size: Long, val mime: String)

/** 메타데이터가 없는 형식도 업로드할 수 있도록 길이 조회 실패는 파일 선택과 분리한다. */
fun audioDurationSeconds(file: File): Double? = try {
    val metadata = android.media.MediaMetadataRetriever()
    try {
        metadata.setDataSource(file.absolutePath)
        metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 }?.div(1000.0)
    } finally { metadata.release() }
} catch (_: Exception) { null }

suspend fun copyLimited(input: InputStream, output: OutputStream): Long {
    var total = 0L
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > MAX_AUDIO_BYTES) throw UserError("25MB를 초과하는 파일은 선택할 수 없어.")
        output.write(buffer, 0, count)
    }
    if (total == 0L) throw UserError("비어 있는 파일이야. 다른 음성 파일을 선택해 줘.")
    return total
}

class AudioFiles(private val context: Context) {
    private val directory = File(context.cacheDir, "audio_uploads").apply { mkdirs() }
    fun clearStaleFiles() {
        directory.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("diarization_") && it.extension == "pcm" }?.forEach { it.delete() }
    }

    suspend fun prepare(uri: Uri): AudioFile {
        val resolver = context.contentResolver
        var name = "audio"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > MAX_AUDIO_BYTES)
                    throw UserError("25MB를 초과하는 파일은 선택할 수 없어.")
            }
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        val supported = setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm")
        if (extension !in supported) throw UserError("지원 형식: M4A, MP3, MP4, MPEG, MPGA, WAV, FLAC, OGG, WEBM")
        val file = File.createTempFile("upload_", ".$extension", directory)
        try {
            val size = (resolver.openInputStream(uri) ?: throw UserError("파일을 열 수 없어.")).use { input ->
                file.outputStream().use { output -> copyLimited(input, output) }
            }
            return AudioFile(file, name, size, resolver.getType(uri) ?: "application/octet-stream")
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}
