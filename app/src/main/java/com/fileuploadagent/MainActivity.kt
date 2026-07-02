package com.fileuploadagent

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.recyclerview.widget.LinearLayoutManager
import com.fileuploadagent.databinding.ActivityMainBinding
import com.fileuploadagent.service.UploadForegroundService
import com.fileuploadagent.settings.SettingsRepository
import com.fileuploadagent.settings.WatchedFolder
import com.fileuploadagent.ui.FolderAdapter
import com.fileuploadagent.ui.LogAdapter
import com.fileuploadagent.util.Constants
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository

    private val folderAdapter = FolderAdapter(onRemove = { folder -> removeFolder(folder) })
    private val logAdapter = LogAdapter()

    private var serviceRunning = false

    private val openDocumentTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            try {
                if (uri != null) addFolder(uri)
            } catch (t: Throwable) {
                showErrorDialog(t)
            }
        }

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Media read permission is required to detect new images",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)

        setupServerSettingsUi()
        setupFolderList()
        setupLogList()
        setupServiceControls()
        setupBatteryOptimizationButton()

        ensureMediaPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshFolderList()
    }

    // --- Error reporting -----------------------------------------------------

    private fun showErrorDialog(t: Throwable) {
        val stringWriter = StringWriter()
        t.printStackTrace(PrintWriter(stringWriter))
        val fullStackTrace = stringWriter.toString()

        val fullMessage = buildString {
            append("Exception: ${t.javaClass.name}\n")
            append("Message: ${t.message}\n\n")
            append(fullStackTrace)
        }

        val textView = TextView(this).apply {
            text = fullMessage
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("Unexpected Error")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Error", fullMessage))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // --- Server settings -----------------------------------------------------

    private fun setupServerSettingsUi() {
        binding.editHost.setText(settingsRepository.host)
        binding.editPort.setText(settingsRepository.port.takeIf { it > 0 }?.toString() ?: "")
        binding.editTimeout.setText(settingsRepository.timeoutSeconds.toString())

        binding.buttonSaveSettings.setOnClickListener {
            val host = binding.editHost.text?.toString()?.trim().orEmpty()
            val port = binding.editPort.text?.toString()?.trim()?.toIntOrNull()
                ?: Constants.DEFAULT_PORT
            val timeout = binding.editTimeout.text?.toString()?.trim()?.toIntOrNull()
                ?: Constants.DEFAULT_TIMEOUT_SECONDS

            settingsRepository.host = host
            settingsRepository.port = port
            settingsRepository.timeoutSeconds = timeout

            Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Watched folders -------------------------------------------------------

    private fun setupFolderList() {
        binding.recyclerFolders.layoutManager = LinearLayoutManager(this)
        binding.recyclerFolders.adapter = folderAdapter
        binding.buttonAddFolder.setOnClickListener {
            openDocumentTree.launch(null)
        }
        refreshFolderList()
    }

    // الدالة المعدّلة كما هو مطلوب
    private fun addFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Cannot access this folder. Please select a different folder.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val displayPath = uri.lastPathSegment ?: uri.toString()
        settingsRepository.addFolder(
            WatchedFolder(
                treeUri = uri.toString(),
                displayPath = displayPath
            )
        )
        refreshFolderList()
    }

    private fun removeFolder(folder: WatchedFolder) {
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission may already have been released by the system; safe to ignore.
        }
        settingsRepository.removeFolder(folder.treeUri)
        refreshFolderList()
    }

    private fun refreshFolderList() {
        val folders = settingsRepository.watchedFolders
        folderAdapter.submitList(folders)
        binding.textEmptyFolders.visibility =
            if (folders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // --- Activity log ------------------------------------------------------

    private fun setupLogList() {
        binding.recyclerLog.layoutManager = LinearLayoutManager(this)
        binding.recyclerLog.adapter = logAdapter

        com.fileuploadagent.logging.UploadLogger.getInstance(this).entries.observe(this) { entries ->
            logAdapter.submitList(entries.asReversed())
        }
    }

    // --- Service controls ----------------------------------------------------

    private fun setupServiceControls() {
        binding.buttonStartService.setOnClickListener { startWatching() }
        binding.buttonStopService.setOnClickListener { stopWatching() }
        updateServiceButtons()
    }

    private fun startWatching() {
        if (!settingsRepository.isServerConfigured()) {
            Toast.makeText(this, R.string.error_no_server_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (settingsRepository.watchedFolders.isEmpty()) {
            Toast.makeText(this, R.string.error_no_folders_configured, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, UploadForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        updateServiceButtons()
    }

    private fun stopWatching() {
        val intent = Intent(this, UploadForegroundService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        startService(intent)
        serviceRunning = false
        updateServiceButtons()
    }

    private fun updateServiceButtons() {
        binding.buttonStartService.isEnabled = !serviceRunning
        binding.buttonStopService.isEnabled = serviceRunning
    }

    // --- Battery optimization -------------------------------------------------

    private fun setupBatteryOptimizationButton() {
        binding.buttonIgnoreBattery.setOnClickListener {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Permissions -----------------------------------------------------------

    private fun ensureMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PermissionChecker.PERMISSION_GRANTED

        if (!granted) {
            requestMediaPermission.launch(permission)
        }
    }
}