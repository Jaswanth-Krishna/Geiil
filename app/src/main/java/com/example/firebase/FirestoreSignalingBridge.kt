package com.example.firebase

import android.util.Log
import com.example.data.IndianNumberValidator
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Cloud Firestore bridge for WebRTC VoIP signaling (`/callRooms/{roomCode}`)
 * and persistent user-scoped call logs (`/users/{uid}/callLogs`).
 *
 * Allows the bundled VoIP dialer inside the Android app to work automatically
 * right after Google Sign-In without requiring manual Firebase Realtime Database setup.
 */
class FirestoreSignalingBridge(
  private val db: FirebaseFirestore,
  private val scope: CoroutineScope,
  private val dispatchToWeb: (jsCall: String) -> Unit,
) {
  private var callLogsListener: ListenerRegistration? = null
  private val roomListeners = mutableMapOf<String, ListenerRegistration>()
  private val candidateListeners = mutableMapOf<String, ListenerRegistration>()

  private fun currentUid(): String? = Firebase.auth.currentUser?.uid

  fun startCallLogsSubscription() {
    val uid = currentUid() ?: return
    callLogsListener?.remove()
    callLogsListener =
      db.collection("users")
        .document(uid)
        .collection("callLogs")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .limit(30)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            Log.w(TAG, "Call logs listener error", error)
            handleFirestoreError(error, OperationType.LIST, "users/$uid/callLogs")
            return@addSnapshotListener
          }
          val arr = JSONArray()
          snapshot?.documents?.forEach { doc ->
            val ts =
              doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: Timestamp.now()
            val item =
              JSONObject().apply {
                put("id", doc.id)
                put("roomCode", doc.getString("roomCode") ?: "")
                val callerNum = doc.getString("callerNumber")
                if (!callerNum.isNullOrBlank()) put("callerNumber", callerNum)
                put("direction", doc.getString("direction") ?: "Outgoing")
                put("status", doc.getString("status") ?: "Missed")
                put("voicePreset", doc.getString("voicePreset") ?: "Normal (0 st)")
                put("durationSec", doc.getLong("durationSec") ?: 0L)
                put("timestamp", ts.toDate().time)
              }
            arr.put(item)
          }
          dispatchToWeb(
            "if (window.__nativeCallLogsUpdated) { window.__nativeCallLogsUpdated($arr); }"
          )
        }
  }

  fun pushCallLog(jsonPayload: String) {
    val uid = currentUid() ?: return
    scope.launch(Dispatchers.IO) {
      try {
        val obj = JSONObject(jsonPayload)
        val rawRoom = obj.optString("roomCode").trim()
        val normalizedTarget = IndianNumberValidator.normalize(rawRoom)
        val roomCode =
          if (normalizedTarget != null) {
            IndianNumberValidator.docIdForNumber(normalizedTarget)
          } else {
            rawRoom.filter { it.isDigit() }.take(12)
          }
        if (!roomCode.matches(Regex("^([0-9]{6}|91[6-9][0-9]{9})$"))) return@launch
        val direction =
          obj.optString("direction").takeIf { it == "Outgoing" || it == "Incoming" } ?: "Outgoing"
        val status =
          obj.optString("status").takeIf {
            it in setOf("Completed", "Declined", "Missed", "Blocked")
          } ?: "Missed"
        val voicePreset = obj.optString("voicePreset", "Normal (0 st)").trim().take(60).ifEmpty {
          "Normal (0 st)"
        }
        val durationSec = obj.optLong("durationSec", 0L).coerceIn(0L, 86400L)
        val rawCaller = obj.optString("callerNumber", "").trim()
        val normalizedCaller =
          if (rawCaller.isNotEmpty()) IndianNumberValidator.normalize(rawCaller) else null

        val data =
          mutableMapOf<String, Any>(
            "userId" to uid,
            "roomCode" to roomCode,
            "direction" to direction,
            "status" to status,
            "voicePreset" to voicePreset,
            "durationSec" to durationSec,
            "createdAt" to FieldValue.serverTimestamp(),
          )
        if (normalizedCaller != null) {
          data["callerNumber"] = normalizedCaller
        }

        db.collection("users").document(uid).collection("callLogs").document().set(data).await()
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "users/$uid/callLogs")
      }
    }
  }

  fun clearAllCallLogs() {
    val uid = currentUid() ?: return
    scope.launch(Dispatchers.IO) {
      try {
        val snap = db.collection("users").document(uid).collection("callLogs").get().await()
        if (snap.isEmpty) return@launch
        val batch = db.batch()
        snap.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.DELETE, "users/$uid/callLogs")
      }
    }
  }

  fun openHostRoom(roomCode: String, reqId: String) {
    val uid = currentUid()
    if (uid == null) {
      resolveCallback(reqId, ok = false, errorMsg = "Sign in required")
      return
    }
    scope.launch(Dispatchers.IO) {
      try {
        val roomRef = db.collection("callRooms").document(roomCode)
        val existing = roomRef.get().await()
        if (existing.exists()) {
          val oldCands = roomRef.collection("candidates").get().await()
          if (!oldCands.isEmpty) {
            val batch = db.batch()
            oldCands.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
          }
          if (existing.getString("hostUid") == uid) {
            roomRef.delete().await()
          }
        }
        roomRef
          .set(
            mapOf(
              "hostUid" to uid,
              "status" to "waiting",
              "createdAt" to FieldValue.serverTimestamp(),
              "updatedAt" to FieldValue.serverTimestamp(),
            )
          )
          .await()
        resolveCallback(reqId, ok = true)
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.CREATE, "callRooms/$roomCode")
        resolveCallback(reqId, ok = false, errorMsg = e.localizedMessage ?: "Failed to open room")
      }
    }
  }

  fun getRoomState(roomCode: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val snap = db.collection("callRooms").document(roomCode).get().await()
        if (!snap.exists()) {
          resolveCallback(reqId, ok = true, dataJson = "null")
        } else {
          resolveCallback(reqId, ok = true, dataJson = roomDocToJson(snap).toString())
        }
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.GET, "callRooms/$roomCode")
        resolveCallback(reqId, ok = false, errorMsg = e.localizedMessage ?: "Failed to read room")
      }
    }
  }

  fun sendCallOffer(roomCode: String, offerSdp: String, callerNumber: String?, reqId: String) {
    val uid = currentUid()
    if (uid == null) {
      resolveCallback(reqId, ok = false, errorMsg = "Sign in required")
      return
    }
    scope.launch(Dispatchers.IO) {
      try {
        val updates =
          mutableMapOf<String, Any>(
            "callerUid" to uid,
            "status" to "ringing",
            "offerType" to "offer",
            "offerSdp" to offerSdp.take(32768),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        val normCaller =
          callerNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { IndianNumberValidator.normalize(it) }
        if (normCaller != null) {
          updates["callerNumber"] = normCaller
        }
        db.collection("callRooms").document(roomCode).update(updates).await()
        resolveCallback(reqId, ok = true)
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "callRooms/$roomCode")
        resolveCallback(reqId, ok = false, errorMsg = e.localizedMessage ?: "Failed to send offer")
      }
    }
  }

  fun sendCallAnswer(roomCode: String, answerSdp: String, reqId: String) {
    scope.launch(Dispatchers.IO) {
      try {
        db.collection("callRooms")
          .document(roomCode)
          .update(
            mapOf(
              "status" to "connected",
              "answerType" to "answer",
              "answerSdp" to answerSdp.take(32768),
              "updatedAt" to FieldValue.serverTimestamp(),
            )
          )
          .await()
        resolveCallback(reqId, ok = true)
      } catch (e: Exception) {
        handleFirestoreError(e, OperationType.UPDATE, "callRooms/$roomCode")
        resolveCallback(reqId, ok = false, errorMsg = e.localizedMessage ?: "Failed to send answer")
      }
    }
  }

  fun updateRoomStatus(roomCode: String, status: String, blocked: Boolean) {
    scope.launch(Dispatchers.IO) {
      try {
        val updates =
          mutableMapOf<String, Any>(
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        if (blocked) updates["blocked"] = true
        db.collection("callRooms").document(roomCode).update(updates).await()
      } catch (e: Exception) {
        Log.w(TAG, "updateRoomStatus($roomCode, $status) failed", e)
      }
    }
  }

  fun deleteRoom(roomCode: String) {
    scope.launch(Dispatchers.IO) {
      try {
        val roomRef = db.collection("callRooms").document(roomCode)
        val cands = roomRef.collection("candidates").get().await()
        if (!cands.isEmpty) {
          val batch = db.batch()
          cands.documents.forEach { batch.delete(it.reference) }
          batch.commit().await()
        }
        roomRef.delete().await()
      } catch (e: Exception) {
        Log.w(TAG, "deleteRoom($roomCode) failed", e)
      }
    }
  }

  fun addIceCandidate(roomCode: String, role: String, candidateJson: String) {
    val uid = currentUid() ?: return
    val safeRole = if (role == "caller") "caller" else "callee"
    scope.launch(Dispatchers.IO) {
      try {
        db.collection("callRooms")
          .document(roomCode)
          .collection("candidates")
          .document()
          .set(
            mapOf(
              "senderUid" to uid,
              "role" to safeRole,
              "candidateJson" to candidateJson.take(4096),
              "createdAt" to FieldValue.serverTimestamp(),
            )
          )
          .await()
      } catch (e: Exception) {
        Log.w(TAG, "addIceCandidate($roomCode) failed", e)
      }
    }
  }

  fun subscribeRoom(roomCode: String) {
    roomListeners.remove(roomCode)?.remove()
    roomListeners[roomCode] =
      db.collection("callRooms").document(roomCode).addSnapshotListener { snap, error ->
        if (error != null) {
          Log.w(TAG, "subscribeRoom($roomCode) error", error)
          return@addSnapshotListener
        }
        val payload = if (snap != null && snap.exists()) roomDocToJson(snap).toString() else "null"
        val quotedRoom = JSONObject.quote(roomCode)
        dispatchToWeb(
          "if (window.__nativeRoomUpdate) { window.__nativeRoomUpdate($quotedRoom, $payload); }"
        )
      }
  }

  fun unsubscribeRoom(roomCode: String) {
    roomListeners.remove(roomCode)?.remove()
  }

  fun subscribeCandidates(roomCode: String, targetRole: String) {
    val key = "$roomCode:$targetRole"
    candidateListeners.remove(key)?.remove()
    candidateListeners[key] =
      db.collection("callRooms")
        .document(roomCode)
        .collection("candidates")
        .whereEqualTo("role", targetRole)
        .addSnapshotListener { snap, error ->
          if (error != null) {
            Log.w(TAG, "subscribeCandidates($key) error", error)
            return@addSnapshotListener
          }
          snap?.documentChanges?.forEach { change ->
            if (change.type == DocumentChange.Type.ADDED) {
              val candJson = change.document.getString("candidateJson") ?: return@forEach
              val quotedRoom = JSONObject.quote(roomCode)
              val quotedRole = JSONObject.quote(targetRole)
              val quotedCand = JSONObject.quote(candJson)
              dispatchToWeb(
                "if (window.__nativeIceCandidate) { window.__nativeIceCandidate($quotedRoom, $quotedRole, $quotedCand); }"
              )
            }
          }
        }
  }

  fun unsubscribeCandidates(roomCode: String) {
    val prefix = "$roomCode:"
    val keys = candidateListeners.keys.filter { it.startsWith(prefix) }
    keys.forEach { candidateListeners.remove(it)?.remove() }
  }

  fun dispose() {
    callLogsListener?.remove()
    callLogsListener = null
    roomListeners.values.forEach { it.remove() }
    roomListeners.clear()
    candidateListeners.values.forEach { it.remove() }
    candidateListeners.clear()
  }

  private fun roomDocToJson(doc: DocumentSnapshot): JSONObject {
    val obj = JSONObject()
    obj.put("status", doc.getString("status") ?: "")
    val callerNum = doc.getString("callerNumber")
    if (!callerNum.isNullOrBlank()) obj.put("callerNumber", callerNum)
    if (doc.getBoolean("blocked") == true) obj.put("blocked", true)
    val offerSdp = doc.getString("offerSdp")
    if (!offerSdp.isNullOrBlank()) {
      obj.put(
        "offer",
        JSONObject().apply {
          put("type", doc.getString("offerType") ?: "offer")
          put("sdp", offerSdp)
        },
      )
    }
    val answerSdp = doc.getString("answerSdp")
    if (!answerSdp.isNullOrBlank()) {
      obj.put(
        "answer",
        JSONObject().apply {
          put("type", doc.getString("answerType") ?: "answer")
          put("sdp", answerSdp)
        },
      )
    }
    return obj
  }

  private fun resolveCallback(reqId: String, ok: Boolean, dataJson: String = "null", errorMsg: String? = null) {
    val quotedId = JSONObject.quote(reqId)
    val quotedErr = if (errorMsg != null) JSONObject.quote(errorMsg) else "null"
    dispatchToWeb(
      "if (window.__nativeResolve) { window.__nativeResolve($quotedId, $ok, $dataJson, $quotedErr); }"
    )
  }

  private companion object {
    const val TAG = "FirestoreSignaling"
  }
}
