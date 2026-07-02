package com.fileuploadagent.upload

sealed class UploadResult {
    data class Success(val durationMs: Long, val httpCode: Int) : UploadResult()
    data class Failure(val reason: String, val retryable: Boolean) : UploadResult()
}
