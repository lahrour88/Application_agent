package com.fileuploadagent.service

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import com.fileuploadagent.logging.UploadLogger
import com.fileuploadagent.settings.SettingsRepository
import com.fileuploadagent.settings.WatchedFolder
import com.fileuploadagent.upload.UploadJob
import com.fileuploadagent.upload.UploadQueue
import com.fileuploadagent.util.Constants
import com.fileuploadagent.util.MediaStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import android.net.Uri

/**
 * Fallback detection mechanism using [FileObserver] (inotify) on the resolved
 * filesystem path of each watched folder.
 *
 * IMPORTANT LIMITATION: under Android's scoped storage model, apps that do not
 * hold MANAGE_EXTERNAL_STORAGE generally cannot receive filesystem events for
 * directories outside their own sandbox, even when a SAF grant permits
 * content:// access to that tree. This fallback is therefore best-effort: it
 * only succeeds for folders whose real path happens to be observable (for
 * example, on some OEM builds, or for app-owned directories), and silently
 * does nothing otherwise. The [MediaObserverManager] (ContentObserver on
 * MediaStore) is the reliable, primary detection path on Android 11+ and
 * should always be running alongside this fallback.
 */
class FileObserverFallback(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val uploadQueue: UploadQueue,
    private val scope: CoroutineScope
) {
    private val logger = UploadLogger.getInstance(context)
    private val observers = mutableListOf<FileObserver>()

    fun start() {
        stop()
        val folders = settingsRepository.watchedFolders
        for (folder in folders) {
            val path = resolveFilesystemPath(folder) ?: continue
            val dir = File(path)
            if (!dir.exists() || !dir.canRead()) continue

            val observer = createObserver(dir, folder)
            try {
                observer.startWatching()
                observers.add(observer)
            } catch (e: SecurityException) {
                Log.w(TAG, "FileObserver unavailable for ${folder.displayPath}: ${e.message}")
            }
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun createObserver(dir: File, folder: WatchedFolder): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val extension = MediaStoreUtils.extensionFromDisplayName(path)
                if (extension !in Constants.SUPPORTED_IMAGE_EXTENSIONS) return

                scope.launch(Dispatchers.IO) {
                    handleDetectedFile(File(dir, path), folder)
                }
            }
        }
    }

    private suspend fun handleDetectedFile(file: File, folder: WatchedFolder) {
        if (!file.exists()) return
        logger.fileDetected(file.name)

        // File-size stability check on the raw file, mirroring the MediaStore path.
        var lastSize = -1L
        var stableReads = 0
        var attempts = 0
        while (attempts < Constants.STABILITY_CHECK_MAX_ATTEMPTS &&
            stableReads < Constants.STABILITY_REQUIRED_STABLE_READS
        ) {
            val size = file.length()
            stableReads = if (size == lastSize && size > 0) stableReads + 1 else 0
            lastSize = size
            attempts++
            if (stableReads < Constants.STABILITY_REQUIRED_STABLE_READS) {
                kotlinx.coroutines.delay(Constants.STABILITY_CHECK_INTERVAL_MS)
            }
        }

        if (stableReads < Constants.STABILITY_REQUIRED_STABLE_READS) {
            logger.info("Skipped ${file.name}: size never stabilized (fallback observer)")
            return
        }

        val uri = Uri.fromFile(file)
        uploadQueue.enqueue(UploadJob(uri, file.name))
    }

    /**
     * Best-effort resolution of a SAF tree URI to a real filesystem path.
     * Only works for the primary external storage volume.
     */
    private fun resolveFilesystemPath(folder: WatchedFolder): String? {
        val treeUri = Uri.parse(folder.treeUri)
        val relative = MediaStoreUtils.primaryRelativePathFromTreeUri(treeUri) ?: return null
        val root = Environment.getExternalStorageDirectory()
        return File(root, relative).absolutePath
    }

    companion object {
        private const val TAG = "FileObserverFallback"
    }
}
