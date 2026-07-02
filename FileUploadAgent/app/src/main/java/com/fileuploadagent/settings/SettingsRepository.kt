package com.fileuploadagent.settings

import android.content.Context
import android.content.SharedPreferences
import com.fileuploadagent.util.Constants

/**
 * Single source of truth for user-configurable settings: server host/port/timeout
 * and the list of watched SAF folder trees. Backed by SharedPreferences.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(Constants.KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(Constants.KEY_PORT, Constants.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(Constants.KEY_PORT, value).apply()

    var timeoutSeconds: Int
        get() = prefs.getInt(Constants.KEY_TIMEOUT_SECONDS, Constants.DEFAULT_TIMEOUT_SECONDS)
        set(value) = prefs.edit().putInt(Constants.KEY_TIMEOUT_SECONDS, value).apply()

    var watchedFolders: List<WatchedFolder>
        get() = WatchedFolder.listFromJson(prefs.getString(Constants.KEY_WATCHED_FOLDERS, null))
        set(value) = prefs.edit()
            .putString(Constants.KEY_WATCHED_FOLDERS, WatchedFolder.listToJson(value))
            .apply()

    fun addFolder(folder: WatchedFolder) {
        val current = watchedFolders.toMutableList()
        if (current.none { it.treeUri == folder.treeUri }) {
            current.add(folder)
            watchedFolders = current
        }
    }

    fun removeFolder(treeUri: String) {
        watchedFolders = watchedFolders.filterNot { it.treeUri == treeUri }
    }

    fun isServerConfigured(): Boolean = host.isNotBlank() && port in 1..65535

    fun uploadUrl(): String = "http://$host:$port${Constants.UPLOAD_PATH}"
}
