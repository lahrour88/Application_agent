package com.fileuploadagent.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fileuploadagent.service.UploadForegroundService
import com.fileuploadagent.settings.SettingsRepository

/**
 * Restarts the folder watcher after the device reboots, provided the user had
 * at least one watched folder and a configured server previously — i.e. the
 * watcher was set up before the reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val settings = SettingsRepository(context)
        if (settings.watchedFolders.isEmpty() || !settings.isServerConfigured()) {
            return
        }

        val serviceIntent = Intent(context, UploadForegroundService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
