package com.example.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.BlockedNumberEntry
import com.example.data.IndianNumberValidator

@Composable
fun BlocklistScreen(
  blockedState: UiState<List<BlockedNumberEntry>>,
  onAddBlockedNumber: (phone: String, label: String?, reason: String?) -> Unit,
  onRemoveBlockedNumber: (entryId: String) -> Unit,
) {
  var numberInput by remember { mutableStateOf("") }
  var reasonInput by remember { mutableStateOf("") }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = "Indian Call Blocker",
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "Indian (+91) Call Blocklist",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
        }

        Text(
          text =
            "Numbers saved here are synced to Firestore and automatically blocked in the live VoIP dialer.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = numberInput,
          onValueChange = { input ->
            numberInput = input.filter { it.isDigit() || it == '+' || it == ' ' }.take(15)
          },
          label = { Text("Indian Mobile Number or 6-Digit Room ID") },
          prefix = { Text("+91 ", fontWeight = FontWeight.SemiBold) },
          placeholder = { Text("98765 43210") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
          modifier = Modifier.fillMaxWidth().testTag("block_number_input"),
        )

        OutlinedTextField(
          value = reasonInput,
          onValueChange = { reasonInput = it.take(120) },
          label = { Text("Reason (optional)") },
          placeholder = { Text("Spam, telemarketer, unknown caller...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("block_reason_input"),
        )

        Button(
          onClick = {
            if (numberInput.isNotBlank()) {
              onAddBlockedNumber(numberInput, null, reasonInput.ifBlank { "Blocked manually" })
              numberInput = ""
              reasonInput = ""
            }
          },
          enabled = numberInput.isNotBlank(),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("add_block_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Block,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "  Block Number",
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }

    when (blockedState) {
      is UiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      is UiState.Error -> {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = blockedState.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
      is UiState.Success -> {
        val list = blockedState.data
        if (list.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp),
              )
              Text(
                text = "No Blocked Numbers Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "Add any +91 Indian number above or block the last caller from Call Logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(list, key = { it.id }) { item ->
              Card(
                shape = RoundedCornerShape(16.dp),
                colors =
                  CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().testTag("blocked_item_${item.id}"),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = IndianNumberValidator.formatDisplay(item.phoneNumber),
                      style = MaterialTheme.typography.titleMedium,
                      fontFamily = FontFamily.Monospace,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (item.reason.isNotBlank() || item.displayLabel.isNotBlank()) {
                      Text(
                        text =
                          listOf(item.displayLabel, item.reason)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                  IconButton(
                    onClick = { onRemoveBlockedNumber(item.id) },
                    modifier = Modifier.testTag("unblock_button_${item.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.DeleteOutline,
                      contentDescription = "Unblock ${item.phoneNumber}",
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
