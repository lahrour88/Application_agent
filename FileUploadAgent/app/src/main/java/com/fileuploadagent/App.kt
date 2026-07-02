package com.fileuploadagent

import android.app.Application
import com.fileuploadagent.logging.UploadLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm up the logger singleton so the log file/handler exist before
        // the service or activity needs them.
        UploadLogger.getInstance(this)
    }
}
