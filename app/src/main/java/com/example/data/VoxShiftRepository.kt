package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class VoxShiftRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) {
  constructor(
    context: Context
  ) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  fun observeBlockedNumbers(userId: String): Flow<List<BlockedNumberEntry>> = flow {
    require(userId.isNotBlank()) { "Authenticated userId is required" }
    val path = "users/$userId/blockedNumbers"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("blockedNumbers")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              BlockedNumberEntry::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun addBlockedNumber(
    rawPhoneNumber: String,
    displayLabel: String? = null,
    reason: String? = null,
  ): Result<String> {
    val uid = auth.currentUser?.uid
    val fallbackPath = "users/${uid ?: "unauthenticated"}/blockedNumbers"
    return try {
      val activeUid = requireUserId()
      val normalized =
        IndianNumberValidator.normalize(rawPhoneNumber)
          ?: return Result.failure(
            IllegalArgumentException(
              "Enter a valid 10-digit Indian mobile number (+91 6xxx–9xxx) or 6-digit Room ID."
            )
          )
      val docId = IndianNumberValidator.docIdForNumber(normalized)
      val docRef =
        db.collection("users")
          .document(activeUid)
          .collection("blockedNumbers")
          .document(docId)

      val cleanLabel = displayLabel?.trim()?.take(60)?.ifBlank { null }
      val cleanReason = reason?.trim()?.take(120)?.ifBlank { null }

      val payload =
        mapOf<String, Any?>(
            "userId" to activeUid,
            "phoneNumber" to normalized,
            "displayLabel" to cleanLabel,
            "reason" to cleanReason,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
          .filterValues { it != null } as Map<String, Any>

      docRef.set(payload).await()
      Result.success(normalized)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, fallbackPath)
      Result.failure(e)
    }
  }

  suspend fun removeBlockedNumber(entryId: String): Result<Unit> {
    val uid = auth.currentUser?.uid
    val path = "users/${uid ?: "unauthenticated"}/blockedNumbers/$entryId"
    return try {
      val activeUid = requireUserId()
      require(entryId.isNotBlank()) { "Entry ID must not be blank" }
      db.collection("users")
        .document(activeUid)
        .collection("blockedNumbers")
        .document(entryId)
        .delete()
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  fun observeTranscripts(userId: String): Flow<List<AudioTranscript>> = flow {
    require(userId.isNotBlank()) { "Authenticated userId is required" }
    val path = "users/$userId/transcripts"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("transcripts")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              AudioTranscript::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun saveTranscript(
    transcriptText: String,
    durationSec: Long,
    languageHint: String? = "English / Hindi (IN)",
  ): Result<Unit> {
    val uid = auth.currentUser?.uid
    val fallbackPath = "users/${uid ?: "unauthenticated"}/transcripts"
    return try {
      val activeUid = requireUserId()
      val trimmed = transcriptText.trim().take(10000)
      require(trimmed.isNotEmpty()) { "Transcript text cannot be empty" }
      val clampedDuration = durationSec.coerceIn(0L, 3600L)
      val cleanLang = languageHint?.trim()?.take(40)?.ifBlank { null }

      val docRef =
        db.collection("users").document(activeUid).collection("transcripts").document()

      val payload =
        mapOf<String, Any?>(
            "userId" to activeUid,
            "transcriptText" to trimmed,
            "durationSec" to clampedDuration,
            "languageHint" to cleanLang,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
          .filterValues { it != null } as Map<String, Any>

      docRef.set(payload).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, fallbackPath)
      Result.failure(e)
    }
  }

  suspend fun deleteTranscript(transcriptId: String): Result<Unit> {
    val uid = auth.currentUser?.uid
    val path = "users/${uid ?: "unauthenticated"}/transcripts/$transcriptId"
    return try {
      val activeUid = requireUserId()
      require(transcriptId.isNotBlank()) { "Transcript ID must not be blank" }
      db.collection("users")
        .document(activeUid)
        .collection("transcripts")
        .document(transcriptId)
        .delete()
        .await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }
}
