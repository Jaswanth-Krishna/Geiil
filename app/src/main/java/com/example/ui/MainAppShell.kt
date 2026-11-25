package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.VoxShiftScreen
import com.example.data.IndianNumberValidator
import com.example.data.VoxShiftRepository
import com.example.firebase.signOutUser
import com.google.firebase.firestore.FirebaseFirestore

private enum class AppSection {
  DIALER,
  BLOCKLIST,
  TRANSCRIBE,
}

@Composable
fun MainAppShell(currentUserId: String) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember { CredentialManager.create(context) }
  val snackbarHostState = remember { SnackbarHostState() }

  val viewModel: VoxShiftViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY])
            val databaseId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            VoxShiftViewModel(VoxShiftRepository(db), currentUserId)
          }
        },
    )

  val blockedState by viewModel.blockedNumbersState.collectAsStateWithLifecycle()
  val transcriptsState by viewModel.transcriptsState.collectAsStateWithLifecycle()
  val transcribeState by viewModel.transcribeState.collectAsStateWithLifecycle()
  val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

  var activeSection by remember { mutableStateOf(AppSection.DIALER) }
  var showShareSheet by remember { mutableStateOf(false) }

  LaunchedEffect(actionMessage) {
    val msg = actionMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearActionMessage()
    }
  }

  if (activeSection != AppSection.DIALER) {
    BackHandler { activeSection = AppSection.DIALER }
  }

  val blockedPhoneList =
    remember(blockedState) {
      when (val st = blockedState) {
        is UiState.Success -> st.data.map { it.phoneNumber }
        else -> emptyList()
      }
    }

  Scaffold(
    modifier = Modifier.fillMaxSize().testTag("voxshift_main_scaffold"),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FilterChip(
              selected = activeSection == AppSection.DIALER,
              onClick = { activeSection = AppSection.DIALER },
              label = { Text("Dialer", fontWeight = FontWeight.SemiBold) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Filled.PhoneInTalk,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                  selectedLabelColor = MaterialTheme.colorScheme.primary,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                ),
              modifier = Modifier.testTag("tab_dialer"),
            )

            FilterChip(
              selected = activeSection == AppSection.BLOCKLIST,
              onClick = { activeSection = AppSection.BLOCKLIST },
              label = {
                val count = blockedPhoneList.size
                Text(
                  if (count > 0) "+91 Block ($count)" else "+91 Block",
                  fontWeight = FontWeight.SemiBold,
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Filled.Block,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                  selectedLabelColor = MaterialTheme.colorScheme.error,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.error,
                ),
              modifier = Modifier.testTag("tab_blocklist"),
            )

            FilterChip(
              selected = activeSection == AppSection.TRANSCRIBE,
              onClick = { activeSection = AppSection.TRANSCRIBE },
              label = { Text("Transcribe", fontWeight = FontWeight.SemiBold) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Filled.GraphicEq,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                  selectedLabelColor = MaterialTheme.colorScheme.secondary,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.secondary,
                ),
              modifier = Modifier.testTag("tab_transcribe"),
            )
          }

          IconButton(
            onClick = { showShareSheet = true },
            modifier = Modifier.testTag("share_app_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Share,
              contentDescription = stringResource(R.string.share_app_button),
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          IconButton(
            onClick = { signOutUser(credentialManager = credentialManager, scope = scope) },
            modifier = Modifier.testTag("sign_out_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Logout,
              contentDescription = "Sign out",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
      when (activeSection) {
        AppSection.DIALER -> {
          VoxShiftScreen(
            blockedNumbers = blockedPhoneList,
            onBlockFromWeb = { rawNum, reason ->
              viewModel.addBlockedNumber(rawNum, null, reason ?: "Blocked from Call Logs")
            },
            onUnblockFromWeb = { rawNum ->
              val normalized = IndianNumberValidator.normalize(rawNum)
              if (normalized != null) {
                viewModel.removeBlockedNumber(IndianNumberValidator.docIdForNumber(normalized))
              }
            },
            onRequestShareApp = { showShareSheet = true },
          )
        }
        AppSection.BLOCKLIST -> {
          BlocklistScreen(
            blockedState = blockedState,
            onAddBlockedNumber = { phone, label, reason ->
              viewModel.addBlockedNumber(phone, label, reason)
            },
            onRemoveBlockedNumber = { entryId -> viewModel.removeBlockedNumber(entryId) },
          )
        }
        AppSection.TRANSCRIBE -> {
          TranscribeScreen(
            transcribeState = transcribeState,
            transcriptsState = transcriptsState,
            onRecordingStarted = { viewModel.setRecordingActive() },
            onRecordingError = { msg -> viewModel.setRecordingError(msg) },
            onAudioCaptured = { bytes, mimeType, durationSec ->
              viewModel.transcribeAudio(bytes, mimeType, durationSec)
            },
            onDeleteTranscript = { id -> viewModel.deleteTranscript(id) },
            onNotify = { msg -> viewModel.addNotifyMessage(msg) },
          )
        }
      }
    }
  }

  if (showShareSheet) {
    ShareAppSheet(
      onDismiss = { showShareSheet = false },
      onResultMessage = { msg -> viewModel.addNotifyMessage(msg) },
    )
  }
}
