package com.example.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppFileSharer {
  private const val SHARED_DIR = "shared"
  private const val APK_FILE_NAME = "VoxShift.apk"
  private const val HTML_FILE_NAME = "VoxShift-VoIP.html"
  private const val MIME_APK = "application/vnd.android.package-archive"
  private const val MIME_HTML = "text/html"

  private fun authority(context: Context): String = "${context.packageName}.fileprovider"

  private fun sharedCacheDir(context: Context): File =
    File(context.cacheDir, SHARED_DIR).apply { if (!exists()) mkdirs() }

  fun getApkSizeLabel(context: Context): String {
    val srcPath = context.applicationInfo.publicSourceDir ?: context.applicationInfo.sourceDir
    val bytes = srcPath?.let { File(it).length() } ?: 0L
    return formatBytes(bytes)
  }

  fun getWebAppSizeLabel(context: Context): String {
    return runCatching {
        context.assets.openFd("index.html").use { it.length }
      }
      .recoverCatching {
        context.assets.open("index.html").use { input ->
          var total = 0L
          val buf = ByteArray(8192)
          while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
          }
          total
        }
      }
      .map { formatBytes(it) }
      .getOrDefault("Single HTML file")
  }

  suspend fun shareAppApk(context: Context): Result<String> {
    return runCatching {
      val stagedFile =
        withContext(Dispatchers.IO) {
          val srcPath =
            context.applicationInfo.publicSourceDir
              ?: context.applicationInfo.sourceDir
              ?: error("Installed APK path is unavailable on this device.")
          val sourceApk = File(srcPath)
          if (!sourceApk.exists() || !sourceApk.canRead()) {
            error("Unable to read installed APK file.")
          }
          val outFile = File(sharedCacheDir(context), APK_FILE_NAME)
          sourceApk.inputStream().use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
          }
          outFile
        }

      launchFileShareChooser(
        context = context,
        file = stagedFile,
        mimeType = MIME_APK,
        subject = "VoxShift Android App (APK)",
        text = "Install VoxShift for live voice pitch-shifting VoIP calls and +91 call blocking.",
        chooserTitle = "Share VoxShift APK",
      )
      "Ready to share $APK_FILE_NAME (${formatBytes(stagedFile.length())})"
    }
  }

  suspend fun shareWebAppHtml(context: Context): Result<String> {
    return runCatching {
      val stagedFile =
        withContext(Dispatchers.IO) {
          val outFile = File(sharedCacheDir(context), HTML_FILE_NAME)
          context.assets.open("index.html").use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, bufferSize = 16 * 1024) }
          }
          outFile
        }

      launchFileShareChooser(
        context = context,
        file = stagedFile,
        mimeType = MIME_HTML,
        subject = "VoxShift Standalone VoIP Web App",
        text = "Open VoxShift-VoIP.html in any modern browser for live voice-changing VoIP calls.",
        chooserTitle = "Share VoxShift Web App File",
      )
      "Ready to share $HTML_FILE_NAME (${formatBytes(stagedFile.length())})"
    }
  }

  private fun launchFileShareChooser(
    context: Context,
    file: File,
    mimeType: String,
    subject: String,
    text: String,
    chooserTitle: String,
  ) {
    val contentUri = FileProvider.getUriForFile(context, authority(context), file)
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        clipData = ClipData.newRawUri(file.name, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    val chooser =
      Intent.createChooser(shareIntent, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }
    context.startActivity(chooser)
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
  }
}
