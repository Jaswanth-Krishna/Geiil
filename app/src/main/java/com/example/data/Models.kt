package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class BlockedNumberEntry(
  @DocumentId val id: String = "",
  val userId: String = "",
  val phoneNumber: String = "",
  val displayLabel: String = "",
  val reason: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class AudioTranscript(
  @DocumentId val id: String = "",
  val userId: String = "",
  val transcriptText: String = "",
  val durationSec: Long = 0L,
  val languageHint: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

object IndianNumberValidator {
  private val indianMobileRegex = Regex("^\\+91[6-9][0-9]{9}$")
  private val roomCodeRegex = Regex("^[0-9]{6}$")

  /**
   * Normalizes Indian mobile numbers (e.g. "9876543210", "09876543210", "919876543210",
   * "+91 98765 43210") to canonical "+91XXXXXXXXXX", or keeps a 6-digit VoIP room code.
   * Returns null if invalid.
   */
  fun normalize(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length == 6 && roomCodeRegex.matches(digits)) {
      return digits
    }
    val tenDigits =
      when {
        digits.length == 10 -> digits
        digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
        digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
        else -> return null
      }
    val candidate = "+91$tenDigits"
    return if (indianMobileRegex.matches(candidate)) candidate else null
  }

  fun docIdForNumber(normalized: String): String = normalized.replace("+", "")

  fun formatDisplay(normalized: String): String {
    if (normalized.startsWith("+91") && normalized.length == 13) {
      val ten = normalized.substring(3)
      return "+91 ${ten.substring(0, 5)} ${ten.substring(5)}"
    }
    if (normalized.length == 6) {
      return "${normalized.substring(0, 3)} ${normalized.substring(3)}"
    }
    return normalized
  }
}
