package com.fileuploadagent.logging

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Central logging facility for the four required event types:
 * file detected, upload started, upload completed, upload failed.
 *
 * Entries are kept in memory (capped, exposed via LiveData for the UI) and
 * appended to a rolling log file under the app's external files directory
 * for post-hoc debugging. All file I/O runs on a single background executor
 * to avoid blocking callers on the main thread.
 */
class UploadLogger private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val entries = MutableLiveData<List<LogEntry>>(emptyList())
    private val buffer = ArrayDeque<LogEntry>()

    private val logFile: File by lazy {
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(dir, "upload_agent_log.txt")
    }

    private fun log(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            if (buffer.size > MAX_IN_MEMORY_ENTRIES) buffer.removeFirst()
            entries.postValue(buffer.toList())
        }
        val tag = "UploadAgent"
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.SUCCESS, LogLevel.INFO -> Log.i(tag, message)
        }
        executor.execute { appendToFile(entry) }
    }

    private fun appendToFile(entry: LogEntry) {
        try {
            FileWriter(logFile, true).use { writer ->
                writer.append("${dateFormat.format(Date(entry.timestampMillis))} [${entry.level}] ${entry.message}\n")
            }
        } catch (e: Exception) {
            Log.e("UploadAgent", "Failed to write log file", e)
        }
    }

    fun fileDetected(fileName: String) = log(LogLevel.INFO, "Detected: $fileName")

    fun uploadStarted(fileName: String) = log(LogLevel.INFO, "Upload started: $fileName")

    fun uploadCompleted(fileName: String, durationMs: Long) =
        log(LogLevel.SUCCESS, "Upload completed: $fileName (${durationMs}ms)")

    fun uploadFailed(fileName: String, reason: String) =
        log(LogLevel.ERROR, "Upload failed: $fileName — $reason")

    fun info(message: String) = log(LogLevel.INFO, message)

    companion object {
        private const val MAX_IN_MEMORY_ENTRIES = 200

        @Volatile private var instance: UploadLogger? = null

        fun getInstance(context: Context): UploadLogger =
            instance ?: synchronized(this) {
                instance ?: UploadLogger(context).also { instance = it }
            }
    }
}
