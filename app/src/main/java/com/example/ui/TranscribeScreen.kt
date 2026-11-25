package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.AudioRecorderHelper
import com.example.data.AudioTranscript
import kotlinx.coroutines.delay

@Composable
fun TranscribeScreen(
  transcribeState: TranscribeUiState,
  transcriptsState: UiState<List<AudioTranscript>>,
  onRecordingStarted: () -> Unit,
  onRecordingError: (String) -> Unit,
  onAudioCaptured: (bytes: ByteArray, mimeType: String, durationSec: Long) -> Unit,
  onDeleteTranscript: (String) -> Unit,
  onNotify: (String) -> Unit,
) {
  val context = LocalContext.current
  val recorder = remember { AudioRecorderHelper(context.applicationContext) }
  var elapsedSec by remember { mutableIntStateOf(0) }

  val isRecording = transcribeState is TranscribeUiState.Recording
  val isTranscribing = transcribeState is TranscribeUiState.Transcribing

  LaunchedEffect(isRecording) {
    if (isRecording) {
      elapsedSec = 0
      while (true) {
        delay(1000L)
        elapsedSec += 1
      }
    }
  }

  DisposableEffect(Unit) { onDispose { recorder.stopAndDiscard() } }

  fun beginRecording() {
    recorder
      .startRecording()
      .onSuccess { onRecordingStarted() }
      .onFailure { err ->
        onRecordingError(err.localizedMessage ?: "Unable to access microphone.")
      }
  }

  val micLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        beginRecording()
      } else {
        onRecordingError("Microphone permission is required to record and transcribe audio.")
      }
    }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Card(
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Gemini Live Audio Transcription",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "Speak in English, Hindi, or Hinglish. Your transcript is saved securely to Firestore.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
          modifier =
            Modifier.size(72.dp)
              .clip(CircleShape)
              .background(
                if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
              ),
          contentAlignment = Alignment.Center,
        ) {
          if (isTranscribing) {
            CircularProgressIndicator(
              modifier = Modifier.size(32.dp),
              color = MaterialTheme.colorScheme.primary,
            )
          } else {
            Icon(
              imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
              contentDescription = if (isRecording) "Stop recording" else "Start recording",
              tint =
                if (isRecording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(34.dp),
            )
          }
        }

        if (isRecording) {
          Text(
            text = "Recording · ${formatSec(elapsedSec)}",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
          )
        } else if (isTranscribing) {
          Text(
            text = "Transcribing with Gemini...",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        Button(
          onClick = {
            if (isRecording) {
              recorder
                .stopAndRead()
                .onSuccess { audio ->
                  onAudioCaptured(audio.bytes, audio.mimeType, audio.durationSec)
                }
                .onFailure { err ->
                  onRecordingError(err.localizedMessage ?: "Failed to read recorded audio.")
                }
            } else {
              val hasMic =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              if (hasMic) {
                beginRecording()
              } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
              }
            }
          },
          enabled = !isTranscribing,
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (isRecording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              contentColor =
                if (isRecording) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary,
            ),
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("record_transcribe_button"),
        ) {
          Text(
            text =
              when {
                isRecording -> "Stop & Transcribe"
                isTranscribing -> "Transcribing..."
                else -> "Start Recording"
              },
            fontWeight = FontWeight.Bold,
          )
        }

        if (transcribeState is TranscribeUiState.Error) {
          Text(
            text = transcribeState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().testTag("transcribe_error_text"),
          )
        } else if (transcribeState is TranscribeUiState.Success) {
          Card(
            shape = RoundedCornerShape(14.dp),
            colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = transcribeState.text,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(12.dp).testTag("latest_transcript_text"),
            )
          }
        }
      }
    }

    Text(
      text = "Saved Transcripts",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (transcriptsState) {
      is UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      is UiState.Error -> {
        Text(text = transcriptsState.message, color = MaterialTheme.colorScheme.error)
      }
      is UiState.Success -> {
        val items = transcriptsState.data
        if (items.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "Tap Start Recording to create your first transcript.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(items, key = { it.id }) { item ->
              Card(
                shape = RoundedCornerShape(16.dp),
                colors =
                  CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = item.transcriptText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(
                      text = "${formatSec(item.durationSec.toInt())} · ${item.languageHint.ifBlank { "Audio" }}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                      IconButton(
                        onClick = {
                          val cb =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                          cb?.setPrimaryClip(ClipData.newPlainText("Transcript", item.transcriptText))
                          onNotify("Transcript copied")
                        }
                      ) {
                        Icon(
                          imageVector = Icons.Filled.ContentCopy,
                          contentDescription = "Copy transcript",
                          tint = MaterialTheme.colorScheme.primary,
                        )
                      }
                      IconButton(onClick = { onDeleteTranscript(item.id) }) {
                        Icon(
                          imageVector = Icons.Filled.DeleteOutline,
                          contentDescription = "Delete transcript",
                          tint = MaterialTheme.colorScheme.error,
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun formatSec(totalSec: Int): String {
  val m = totalSec / 60
  val s = totalSec % 60
  return "%02d:%02d".format(m, s)
}
