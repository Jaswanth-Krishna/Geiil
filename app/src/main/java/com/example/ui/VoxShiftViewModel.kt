package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AudioTranscript
import com.example.data.BlockedNumberEntry
import com.example.data.VoxShiftRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>

  data class Success<T>(val data: T) : UiState<T>

  data class Error(val message: String) : UiState<Nothing>
}

sealed interface TranscribeUiState {
  data object Idle : TranscribeUiState

  data object Recording : TranscribeUiState

  data object Transcribing : TranscribeUiState

  data class Success(val text: String, val durationSec: Long) : TranscribeUiState

  data class Error(val message: String) : TranscribeUiState
}

class VoxShiftViewModel(
  private val repository: VoxShiftRepository,
  private val currentUserId: String,
) : ViewModel() {

  // Lazily initialized per Firebase AI Logic contract (never eagerly in constructor)
  private val transcriptionModel by lazy { Firebase.ai.generativeModel("gemini-3.8-flash") }

  val blockedNumbersState: StateFlow<UiState<List<BlockedNumberEntry>>> =
    repository
      .observeBlockedNumbers(currentUserId)
      .map<List<BlockedNumberEntry>, UiState<List<BlockedNumberEntry>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing blocked numbers", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load blocked numbers"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  val transcriptsState: StateFlow<UiState<List<AudioTranscript>>> =
    repository
      .observeTranscripts(currentUserId)
      .map<List<AudioTranscript>, UiState<List<AudioTranscript>>> { UiState.Success(it) }
      .catch { error ->
        val firestoreError =
          generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing transcripts", rootError)
        emit(UiState.Error(rootError.message ?: "Failed to load transcripts"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = UiState.Loading,
      )

  private val _actionMessage = MutableStateFlow<String?>(null)
  val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

  private val _transcribeState = MutableStateFlow<TranscribeUiState>(TranscribeUiState.Idle)
  val transcribeState: StateFlow<TranscribeUiState> = _transcribeState.asStateFlow()

  fun clearActionMessage() {
    _actionMessage.value = null
  }

  fun addNotifyMessage(message: String) {
    _actionMessage.value = message
  }

  fun addBlockedNumber(rawPhoneNumber: String, displayLabel: String? = null, reason: String? = null) {
    viewModelScope.launch {
      val result = repository.addBlockedNumber(rawPhoneNumber, displayLabel, reason)
      result
        .onSuccess { normalized -> _actionMessage.value = "Blocked $normalized" }
        .onFailure { err ->
          _actionMessage.value = err.localizedMessage ?: "Failed to block number"
        }
    }
  }

  fun removeBlockedNumber(entryId: String) {
    viewModelScope.launch {
      val result = repository.removeBlockedNumber(entryId)
      result
        .onSuccess { _actionMessage.value = "Removed from blocklist" }
        .onFailure { err ->
          _actionMessage.value = err.localizedMessage ?: "Failed to unblock number"
        }
    }
  }

  fun setRecordingActive() {
    _transcribeState.value = TranscribeUiState.Recording
  }

  fun setRecordingError(message: String) {
    _transcribeState.value = TranscribeUiState.Error(message)
  }

  fun resetTranscribeState() {
    _transcribeState.value = TranscribeUiState.Idle
  }

  fun transcribeAudio(audioBytes: ByteArray, mimeType: String, durationSec: Long) {
    viewModelScope.launch {
      _transcribeState.value = TranscribeUiState.Transcribing
      try {
        val response =
          withTimeout(60_000L) {
            transcriptionModel.generateContent(
              content {
                inlineData(audioBytes, mimeType)
                text(
                  "Transcribe the spoken audio accurately verbatim. " +
                    "The speaker may use Indian English, Hindi, or Hinglish. " +
                    "Return only the clean transcript text without commentary."
                )
              }
            )
          }

        val transcriptText = response.text?.trim()
        if (!transcriptText.isNullOrBlank()) {
          _transcribeState.value = TranscribeUiState.Success(transcriptText, durationSec)
          repository.saveTranscript(
            transcriptText = transcriptText,
            durationSec = durationSec,
            languageHint = "English / Hindi (IN)",
          )
        } else {
          _transcribeState.value =
            TranscribeUiState.Error("No speech detected in the recording. Please try again.")
        }
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Audio transcription timed out", e)
        _transcribeState.value =
          TranscribeUiState.Error("Transcription timed out after 60 seconds. Please try again.")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Firebase AI audio transcription failed", e)
        _transcribeState.value =
          TranscribeUiState.Error(e.localizedMessage ?: "Audio transcription failed.")
      }
    }
  }

  fun deleteTranscript(transcriptId: String) {
    viewModelScope.launch {
      val result = repository.deleteTranscript(transcriptId)
      result.onFailure { err ->
        _actionMessage.value = err.localizedMessage ?: "Failed to delete transcript"
      }
    }
  }

  private companion object {
    const val TAG = "VoxShiftVM"
    const val STOP_TIMEOUT_MILLIS = 5000L
  }
}
