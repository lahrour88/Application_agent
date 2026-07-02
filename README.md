# File Upload Agent

An Android background agent that watches user-selected folders for new images
and automatically uploads each one to a local network server as soon as it
finishes writing.

## Requirements covered

- **Folder selection** via the Storage Access Framework (SAF) — no broad
  filesystem permission (`MANAGE_EXTERNAL_STORAGE`) is requested.
- **Detection** of new images with two layered mechanisms:
  1. `MediaObserverManager` — a `ContentObserver` on `MediaStore.Images`
     (the reliable, primary method on Android 11+).
  2. `FileObserverFallback` — best-effort `FileObserver` (inotify) on the
     resolved filesystem path, for cases where it happens to be observable.
     See **Known limitation** below.
- **Write-completion detection** (`PendingFileTracker`) — waits for
  `MediaStore.IS_PENDING` to clear and for file size to stabilize across
  repeated reads before treating a file as ready to upload.
- **Upload** via `multipart/form-data` POST, streamed directly from a
  `ContentResolver` `InputStream` — never buffered whole into memory, never
  Base64-encoded.
- **Foreground service** (`UploadForegroundService`, type `dataSync`) keeps
  the watcher alive with a persistent notification.
- **Boot persistence** — `BootReceiver` restarts the service after reboot if
  a server and at least one folder were already configured.
- **Retry with exponential backoff** for retryable failures (network errors,
  HTTP 5xx), serial upload queue, short-lived wake lock held only during an
  in-flight upload.
- **Activity log** in the UI plus a rolling log file
  (`upload_agent_log.txt` under app-specific external storage) recording:
  file detected, upload started, upload completed (with duration), upload
  failed (with reason).
- **Settings UI** for server host, port, upload timeout, and the watched
  folder list (add/remove).

## Project structure

```
app/src/main/java/com/fileuploadagent/
  App.kt                        Application class
  MainActivity.kt                Settings UI, folder picker, service controls, log view
  settings/
    SettingsRepository.kt        SharedPreferences-backed settings
    WatchedFolder.kt              Folder model + JSON (de)serialization
  service/
    UploadForegroundService.kt    Foreground service tying watchers + queue together
    MediaObserverManager.kt       Primary detection: ContentObserver on MediaStore
    FileObserverFallback.kt       Fallback detection: FileObserver (best-effort)
    PendingFileTracker.kt         Waits for a file to finish writing
  upload/
    UploadClient.kt                OkHttp multipart upload (streamed, no Base64)
    UploadQueue.kt                 Serial queue, retry/backoff, dedup, wake lock
    UploadResult.kt                Success/Failure result type
  logging/
    UploadLogger.kt                In-memory + file-backed logger, exposes LiveData
    LogEntry.kt
  boot/
    BootReceiver.kt                Restarts the service after reboot
  ui/
    FolderAdapter.kt / LogAdapter.kt  RecyclerView adapters
  util/
    Constants.kt
    MediaStoreUtils.kt              MediaStore query + SAF-tree-to-path matching
```

## Building

This project targets `minSdk 30` (Android 11), `compileSdk`/`targetSdk 35`,
Kotlin, AGP 8.5.2. To build:

1. Open the project root in Android Studio (Koala or newer). Android Studio
   will offer to generate the Gradle wrapper jar automatically — accept it
   (the jar binary isn't checked into this archive since it must be
   downloaded from `services.gradle.org`).
   - Alternatively, from a machine with Gradle installed: run
     `gradle wrapper --gradle-version 8.7` in the project root, then use
     `./gradlew assembleDebug`.
2. Build and run on a device or emulator running Android 11+.

## Using the app

1. Launch the app and grant the media read permission when prompted
   (`READ_MEDIA_IMAGES` on Android 13+, `READ_EXTERNAL_STORAGE` on 11–12).
2. Enter your server's host/IP and port, tap **Save settings**.
3. Tap **Add folder** and pick a folder (e.g. `DCIM/Camera`) via the system
   folder picker.
4. Tap **Start watching**. A persistent notification confirms the service is
   running.
5. Optionally tap **Disable battery optimization** so the OS doesn't kill the
   service to save power.
6. New images saved into the watched folder(s) will appear in the activity
   log as they're detected and uploaded.

### Server contract

The app POSTs to `http://<host>:<port>/upload` as
`multipart/form-data` with a single part named `file` (filename + image
bytes + inferred MIME type). Any server that accepts a standard multipart
file upload on that path will work. A minimal example (Node/Express):

```js
const express = require('express');
const multer = require('multer');
const upload = multer({ dest: 'uploads/' });
const app = express();
app.post('/upload', upload.single('file'), (req, res) => res.sendStatus(200));
app.listen(8080);
```

## Known limitation: FileObserver fallback

Android's scoped storage model (Android 10+) generally prevents apps from
receiving filesystem-level events for directories they don't own unless they
hold `MANAGE_EXTERNAL_STORAGE`. Per the chosen design (SAF-only, no broad
storage permission), `FileObserverFallback` is **best-effort**: it resolves a
watched SAF tree to its real path on the primary storage volume and attempts
to attach an inotify watch, but on many stock Android 11+ builds this will
silently find nothing to observe. The `MediaObserverManager` (ContentObserver
on MediaStore) is the reliable primary path and is always running alongside
it — the fallback exists to opportunistically catch anything the primary
path might miss on devices/OEM builds where direct path access is looser.

Non-primary storage volumes (e.g. removable SD cards) are not matched by
either detector, since SAF tree document IDs for those volumes don't map
cleanly onto `MediaStore.RELATIVE_PATH`.

## Permissions requested

| Permission | Purpose |
|---|---|
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` (≤32) | Query MediaStore for new images |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Upload to the configured server |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep the watcher running |
| `RECEIVE_BOOT_COMPLETED` | Resume watching after reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reduce risk of the OS killing the service |
| `WAKE_LOCK` | Held only for the duration of an individual upload |

No `MANAGE_EXTERNAL_STORAGE` permission is requested.

