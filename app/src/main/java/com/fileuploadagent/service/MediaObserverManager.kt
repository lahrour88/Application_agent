package com.fileuploadagent.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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

/**
 * Preferred detection mechanism: registers a [ContentObserver] on the images
 * collection so the OS notifies us the moment any app inserts or updates a
 * row, rather than polling. On each notification we re-query MediaStore for
 * rows added since the last check and filter down to the ones that fall
 * inside a user-selected watched folder.
 */
class MediaObserverManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val uploadQueue: UploadQueue,
    private val scope: CoroutineScope
) {
    private val logger = UploadLogger.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val processedUris = mutableSetOf<Uri>()

    @Volatile private var lastCheckedMillis = System.currentTimeMillis()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch(Dispatchers.IO) { checkForNewImages() }
        }
    }

    fun start() {
        lastCheckedMillis = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        // Catch any files that landed just before the observer was registered.
        scope.launch(Dispatchers.IO) { checkForNewImages() }
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(observer)
    }

    private suspend fun checkForNewImages() {
        val folders = settingsRepository.watchedFolders
        if (folders.isEmpty()) return

        val checkStart = System.currentTimeMillis()
        val candidates = MediaStoreUtils.queryRecentImages(context, lastCheckedMillis)
        lastCheckedMillis = checkStart

        for (media in candidates) {
            if (media.contentUri in processedUris) continue

            val extension = MediaStoreUtils.extensionFromDisplayName(media.displayName)
            if (extension !in Constants.SUPPORTED_IMAGE_EXTENSIONS) continue

            findMatchingFolder(media.relativePath, folders) ?: continue

            processedUris.add(media.contentUri)
            logger.fileDetected(media.displayName)

            val stableMedia = PendingFileTracker.awaitStableFile(context, media)
            if (stableMedia == null) {
                logger.info("Skipped ${media.displayName}: file removed or never stabilized")
                continue
            }

            uploadQueue.enqueue(UploadJob(stableMedia.contentUri, stableMedia.displayName))
            trimProcessedCache()
        }
    }

    private fun findMatchingFolder(relativePath: String, folders: List<WatchedFolder>): WatchedFolder? {
        return folders.firstOrNull { folder ->
            val treeUri = Uri.parse(folder.treeUri)
            MediaStoreUtils.isPathWithinFolder(relativePath, folder, treeUri)
        }
    }

    private fun trimProcessedCache() {
        if (processedUris.size > MAX_PROCESSED_CACHE) {
            val excess = processedUris.size - MAX_PROCESSED_CACHE
            val toRemove = processedUris.take(excess)
            processedUris.removeAll(toRemove.toSet())
        }
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 5_000L
        private const val MAX_PROCESSED_CACHE = 2000
    }
}
