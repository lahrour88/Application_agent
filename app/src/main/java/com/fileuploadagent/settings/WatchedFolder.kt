package com.fileuploadagent.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a folder the user has selected via the Storage Access Framework.
 *
 * [treeUri] is the persisted SAF tree URI string (content://...tree/...).
 * [displayPath] is a human-readable path used only for display in the UI/logs;
 * it is derived once at selection time and is not used for filesystem access.
 */
data class WatchedFolder(
    val treeUri: String,
    val displayPath: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("treeUri", treeUri)
        put("displayPath", displayPath)
    }

    companion object {
        fun fromJson(json: JSONObject): WatchedFolder = WatchedFolder(
            treeUri = json.getString("treeUri"),
            displayPath = json.getString("displayPath")
        )

        fun listToJson(folders: List<WatchedFolder>): String {
            val array = JSONArray()
            folders.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String?): List<WatchedFolder> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
