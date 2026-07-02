# OkHttp / Okio rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep model classes used for SharedPreferences JSON serialization
-keep class com.fileuploadagent.settings.WatchedFolder { *; }
-keep class com.fileuploadagent.logging.LogEntry { *; }
