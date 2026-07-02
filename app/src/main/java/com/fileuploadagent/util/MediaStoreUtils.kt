package com.fileuploadagent.util

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.fileuploadagent.settings.WatchedFolder

/**
 * A media file discovered via MediaStore, with just the fields needed to
 * decide whether to upload it and how to read its bytes.
 */
data class DiscoveredMedia(
    val contentUri: Uri,
    val displayName: String,
    val relativePath: String,
    val isPending: Boolean,
    val dateAdded: Long,
    val size: Long
)

object MediaStoreUtils {

    /**
     * Extracts the "primary:<relative-path>" style document id from a SAF tree URI
     * and returns the relative-path portion (e.g. "DCIM/Camera") for volumes on
     * primary external storage. Returns null for non-primary volumes (e.g. SD
     * cards), which cannot be reliably matched against MediaStore.RELATIVE_PATH.
     */
    fun primaryRelativePathFromTreeUri(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0] == "primary") {
                parts[1].trim('/')
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns true if [relativePath] (from MediaStore, e.g. "DCIM/Camera/") falls
     * within the folder identified by [folder], directly or in a subfolder.
     */
    fun isPathWithinFolder(relativePath: String, folder: WatchedFolder, folderTreeUri: Uri): Boolean {
        val folderRelative = primaryRelativePathFromTreeUri(folderTreeUri) ?: return false
        val normalizedMediaPath = relativePath.trim('/')
        return normalizedMediaPath == folderRelative ||
            normalizedMediaPath.startsWith("$folderRelative/")
    }

    fun extensionFromDisplayName(displayName: String): String =
        displayName.substringAfterLast('.', "").lowercase()

    /**
     * Queries MediaStore for images added within the last [sinceMillis], newest first.
     */
    fun queryRecentImages(context: Context, sinceMillis: Long): List<DiscoveredMedia> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.IS_PENDING)
        }

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf((sinceMillis / 1000).toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val results = mutableListOf<DiscoveredMedia>()
        context.contentResolver.query(
            collection, projection.toTypedArray(), selection, selectionArgs, sortOrder
        )?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val isPending = pendingCol >= 0 && cursor.getInt(pendingCol) != 0
                results.add(
                    DiscoveredMedia(
                        contentUri = uri,
                        displayName = cursor.getString(nameCol) ?: "",
                        relativePath = cursor.getString(pathCol) ?: "",
                        isPending = isPending,
                        dateAdded = cursor.getLong(dateCol) * 1000,
                        size = cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return results
    }

    /**
     * Re-reads a single row's pending state and size, used while waiting for a
     * file write to finish. Returns null if the row no longer exists.
     */
    fun refreshMedia(context: Context, uri: Uri): DiscoveredMedia? {
        val projection = mutableListOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.IS_PENDING)
        }
        context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
            } else -1
            val isPending = pendingCol >= 0 && cursor.getInt(pendingCol) != 0
            return DiscoveredMedia(
                contentUri = uri,
                displayName = cursor.getString(nameCol) ?: "",
                relativePath = cursor.getString(pathCol) ?: "",
                isPending = isPending,
                dateAdded = cursor.getLong(dateCol) * 1000,
                size = cursor.getLong(sizeCol)
            )
        }
        return null
    }
}
