package com.fileuploadagent.upload

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.fileuploadagent.settings.SettingsRepository
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a single image, identified by its content:// URI, to the configured
 * local server via multipart/form-data POST. The file is streamed directly
 * from a ContentResolver InputStream into the request body — it is never
 * fully buffered in memory and never Base64-encoded.
 */
class UploadClient(private val context: Context) {

    fun upload(
        contentUri: Uri,
        fileName: String,
        settings: SettingsRepository
    ): UploadResult {
        if (!settings.isServerConfigured()) {
            return UploadResult.Failure("Server host/port not configured", retryable = false)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        val mimeType = mimeTypeFor(fileName)

        val fileBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()

            override fun contentLength(): Long {
                return try {
                    context.contentResolver.openAssetFileDescriptor(contentUri, "r")
                        ?.use { it.length }
                        ?: -1L
                } catch (e: Exception) {
                    -1L
                }
            }

            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(contentUri)
                    ?: throw IOException("Unable to open input stream for $contentUri")
                input.use { stream ->
                    sink.writeAll(stream.source())
                }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()

        val request = Request.Builder()
            .url(settings.uploadUrl())
            .post(multipartBody)
            .build()

        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    UploadResult.Success(duration, response.code)
                } else {
                    val retryable = response.code >= 500
                    UploadResult.Failure("HTTP ${response.code}", retryable)
                }
            }
        } catch (e: IOException) {
            UploadResult.Failure(e.message ?: "Network error", retryable = true)
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "heic" -> "image/heic"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
    }
}
