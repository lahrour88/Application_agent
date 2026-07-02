package com.fileuploadagent.logging

enum class LogLevel { INFO, SUCCESS, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String
)
