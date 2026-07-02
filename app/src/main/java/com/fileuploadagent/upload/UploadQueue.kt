package com.fileuploadagent.upload

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.fileuploadagent.logging.UploadLogger
import com.fileuploadagent.settings.SettingsRepository
import com.fileuploadagent.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UploadJob(val contentUri: Uri, val fileName: String)

/**
 * Serial upload queue. Uploads are processed one at a time (simple, predictable
 * bandwidth/battery usage) with exponential backoff retries on retryable
 * failures (network errors, HTTP 5xx). A short partial wake lock is held only
 * for the duration of each individual upload attempt.
 */
class UploadQueue(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val channel = Channel<UploadJob>(Channel.UNLIMITED)
    private val client = UploadClient(context)
    private val logger = UploadLogger.getInstance(context)

    // Guards against the same file being enqueued twice when both the
    // MediaStore ContentObserver and the FileObserver fallback fire for it.
    // Keyed by file name; a short recency window is enough since both
    // detectors react within seconds of the same write.
    private val recentlyEnqueued = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > DEDUP_CACHE_MAX_SIZE
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            for (job in channel) {
                processWithRetry(job)
            }
        }
    }

    fun enqueue(job: UploadJob) {
        val now = System.currentTimeMillis()
        synchronized(recentlyEnqueued) {
            val lastSeen = recentlyEnqueued[job.fileName]
            if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                return
            }
            recentlyEnqueued[job.fileName] = now
        }
        channel.trySend(job)
    }

    private suspend fun processWithRetry(job: UploadJob) {
        logger.uploadStarted(job.fileName)

        var attempt = 0
        while (true) {
            val result = withWakeLock {
                withContext(Dispatchers.IO) {
                    client.upload(job.contentUri, job.fileName, settingsRepository)
                }
            }

            when (result) {
                is UploadResult.Success -> {
                    logger.uploadCompleted(job.fileName, result.durationMs)
                    return
                }
                is UploadResult.Failure -> {
                    attempt++
                    val shouldRetry = result.retryable && attempt <= Constants.UPLOAD_MAX_RETRIES
                    if (!shouldRetry) {
                        logger.uploadFailed(job.fileName, result.reason)
                        return
                    }
                    val backoff = Constants.UPLOAD_RETRY_BASE_DELAY_MS * (1 shl (attempt - 1))
                    delay(backoff)
                }
            }
        }
    }

    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "FileUploadAgent:upload"
        )
        wakeLock.acquire(2 * 60 * 1000L /*max 2 min safety timeout*/)
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 60_000L
        private const val DEDUP_CACHE_MAX_SIZE = 500
    }
}