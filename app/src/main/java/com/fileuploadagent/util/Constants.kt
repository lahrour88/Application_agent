package com.fileuploadagent.util

object Constants {

    // SharedPreferences
    const val PREFS_NAME = "file_upload_agent_prefs"
    const val KEY_HOST = "pref_host"
    const val KEY_PORT = "pref_port"
    const val KEY_TIMEOUT_SECONDS = "pref_timeout_seconds"
    const val KEY_WATCHED_FOLDERS = "pref_watched_folders_json"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_TIMEOUT_SECONDS = 30

    // Notification
    const val NOTIFICATION_CHANNEL_ID = "upload_agent_channel"
    const val NOTIFICATION_ID_SERVICE = 1001

    // Service actions
    const val ACTION_START_SERVICE = "com.fileuploadagent.action.START_SERVICE"
    const val ACTION_STOP_SERVICE = "com.fileuploadagent.action.STOP_SERVICE"

    // Supported image formats (extensions, lowercase, without dot)
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

    // File stability check (waiting until a write is complete)
    const val STABILITY_CHECK_INTERVAL_MS = 700L
    const val STABILITY_CHECK_MAX_ATTEMPTS = 15
    const val STABILITY_REQUIRED_STABLE_READS = 2

    // Upload retry
    const val UPLOAD_MAX_RETRIES = 3
    const val UPLOAD_RETRY_BASE_DELAY_MS = 2000L

    // Upload endpoint path
    const val UPLOAD_PATH = "/upload"
}
