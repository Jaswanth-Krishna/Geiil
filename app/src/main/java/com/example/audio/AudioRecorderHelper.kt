package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

data class RecordedAudio(
  val bytes: ByteArray,
  val mimeType: String,
  val durationSec: Long,
)

class AudioRecorderHelper(private val context: Context) {
  private var recorder: MediaRecorder? = null
  private var outputFile: File? = null
  private var startTimeMs: Long = 0L

  fun startRecording(): Result<Unit> {
    return try {
      stopAndDiscard()
      val file = File(context.cacheDir, "voxshift_transcribe_${System.currentTimeMillis()}.m4a")
      val mediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          MediaRecorder(context)
        } else {
          @Suppress("DEPRECATION") MediaRecorder()
        }

      mediaRecorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(16000)
        setAudioEncodingBitRate(64000)
        setOutputFile(file.absolutePath)
        prepare()
        start()
      }

      recorder = mediaRecorder
      outputFile = file
      startTimeMs = System.currentTimeMillis()
      Result.success(Unit)
    } catch (e: Exception) {
      stopAndDiscard()
      Result.failure(e)
    }
  }

  fun stopAndRead(): Result<RecordedAudio> {
    val activeRecorder = recorder
    val file = outputFile
    val elapsedSec =
      if (startTimeMs > 0L) ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
      else 1L

    return try {
      activeRecorder?.stop()
      activeRecorder?.release()
      recorder = null

      if (file == null || !file.exists() || file.length() == 0L) {
        return Result.failure(IllegalStateException("Recorded audio is empty. Please try again."))
      }
      val bytes = file.readBytes()
      file.delete()
      outputFile = null
      startTimeMs = 0L

      if (bytes.size > 18 * 1024 * 1024) {
        return Result.failure(
          IllegalArgumentException("Recording exceeds 18 MB limit. Please record a shorter clip.")
        )
      }
      Result.success(RecordedAudio(bytes = bytes, mimeType = "audio/mp4", durationSec = elapsedSec))
    } catch (e: Exception) {
      stopAndDiscard()
      Result.failure(e)
    }
  }

  fun stopAndDiscard() {
    try {
      recorder?.stop()
    } catch (_: Exception) {}
    try {
      recorder?.release()
    } catch (_: Exception) {}
    recorder = null
    try {
      outputFile?.delete()
    } catch (_: Exception) {}
    outputFile = null
    startTimeMs = 0L
  }
}
