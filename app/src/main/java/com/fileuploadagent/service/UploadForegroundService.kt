package com.fileuploadagent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fileuploadagent.MainActivity
import com.fileuploadagent.R
import com.fileuploadagent.logging.UploadLogger
import com.fileuploadagent.settings.SettingsRepository
import com.fileuploadagent.upload.UploadQueue
import com.fileuploadagent.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the lifetime of the folder watchers and the
 * upload queue. Runs as long as the user has started watching; restarted
 * after reboot by [com.fileuploadagent.boot.BootReceiver].
 */
class UploadForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var uploadQueue: UploadQueue
    private lateinit var mediaObserverManager: MediaObserverManager
    private lateinit var fileObserverFallback: FileObserverFallback
    private lateinit var logger: UploadLogger

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        logger = UploadLogger.getInstance(this)
        uploadQueue = UploadQueue(this, settingsRepository, serviceScope)
        mediaObserverManager = MediaObserverManager(this, settingsRepository, uploadQueue, serviceScope)
        fileObserverFallback = FileObserverFallback(this, settingsRepository, uploadQueue, serviceScope)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_STOP_SERVICE -> {
                stopWatching()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        val folderCount = settingsRepository.watchedFolders.size
        startForeground(Constants.NOTIFICATION_ID_SERVICE, buildNotification(folderCount))

        uploadQueue.start()
        mediaObserverManager.start()
        fileObserverFallback.start()

        logger.info("Watcher started ($folderCount folder(s) monitored)")
    }

    private fun stopWatching() {
        mediaObserverManager.stop()
        fileObserverFallback.stop()
        logger.info("Watcher stopped")
    }

    override fun onDestroy() {
        stopWatching()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(folderCount: Int) =
        NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title_watching))
            .setContentText(getString(R.string.notification_text_watching, folderCount))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
