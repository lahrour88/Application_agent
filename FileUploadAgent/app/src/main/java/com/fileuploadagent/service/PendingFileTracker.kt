package com.fileuploadagent.service

import android.content.Context
import com.fileuploadagent.util.Constants
import com.fileuploadagent.util.DiscoveredMedia
import com.fileuploadagent.util.MediaStoreUtils
import kotlinx.coroutines.delay

/**
 * Determines when a newly detected image has finished being written to disk.
 *
 * Two independent signals are used:
 *  1. MediaStore.IS_PENDING (Android 10+): the writer app clears this flag when
 *     it finishes the write. If present and still set, the file is not ready.
 *  2. Size stability: the file size is re-read on an interval and must report
 *     the same value for [Constants.STABILITY_REQUIRED_STABLE_READS] consecutive
 *     reads. This also covers apps/devices that never set IS_PENDING.
 *
 * Returns the final, stable [DiscoveredMedia], or null if the file disappeared
 * or never stabilized within the attempt budget.
 */
object PendingFileTracker {

    suspend fun awaitStableFile(context: Context, media: DiscoveredMedia): DiscoveredMedia? {
        var current = media
        var stableReadCount = 0
        var lastSize = current.size

        repeat(Constants.STABILITY_CHECK_MAX_ATTEMPTS) {
            if (!current.isPending && stableReadCount >= Constants.STABILITY_REQUIRED_STABLE_READS) {
                return current
            }

            delay(Constants.STABILITY_CHECK_INTERVAL_MS)

            val refreshed = MediaStoreUtils.refreshMedia(context, current.contentUri) ?: return null
            current = refreshed

            stableReadCount = if (refreshed.size == lastSize && refreshed.size > 0) {
                stableReadCount + 1
            } else {
                0
            }
            lastSize = refreshed.size
        }

        // Final check: accept if not pending and size was stable on the last read,
        // even if we didn't hit the full streak (handles very small/fast writes).
        return if (!current.isPending && stableReadCount > 0) current else null
    }
}
