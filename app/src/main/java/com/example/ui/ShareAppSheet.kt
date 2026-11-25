package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.share.AppFileSharer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareAppSheet(
  onDismiss: () -> Unit,
  onResultMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val apkSize = remember(context) { AppFileSharer.getApkSizeLabel(context) }
  val htmlSize = remember(context) { AppFileSharer.getWebAppSizeLabel(context) }

  var busyOption by remember { mutableStateOf<String?>(null) }

  ModalBottomSheet(
    onDismissRequest = { if (busyOption == null) onDismiss() },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.testTag("share_app_sheet"),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier =
            Modifier.size(40.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        Column {
          Text(
            text = stringResource(R.string.share_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(R.string.share_sheet_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      ShareOptionCard(
        title = stringResource(R.string.share_apk_title),
        subtitle =
          if (apkSize.isNotBlank()) {
            stringResource(R.string.share_apk_subtitle_with_size, apkSize)
          } else {
            stringResource(R.string.share_apk_subtitle)
          },
        icon = Icons.Filled.Android,
        accentColor = MaterialTheme.colorScheme.primary,
        isLoading = busyOption == "apk",
        enabled = busyOption == null,
        tag = "share_apk_option",
        onClick = {
          busyOption = "apk"
          scope.launch {
            val result = AppFileSharer.shareAppApk(context)
            busyOption = null
            result
              .onSuccess { msg ->
                onResultMessage(msg)
                onDismiss()
              }
              .onFailure { err ->
                onResultMessage(err.localizedMessage ?: "Failed to share APK file")
              }
          }
        },
      )

      ShareOptionCard(
        title = stringResource(R.string.share_html_title),
        subtitle =
          if (htmlSize.isNotBlank()) {
            stringResource(R.string.share_html_subtitle_with_size, htmlSize)
          } else {
            stringResource(R.string.share_html_subtitle)
          },
        icon = Icons.Filled.Code,
        accentColor = MaterialTheme.colorScheme.secondary,
        isLoading = busyOption == "html",
        enabled = busyOption == null,
        tag = "share_html_option",
        onClick = {
          busyOption = "html"
          scope.launch {
            val result = AppFileSharer.shareWebAppHtml(context)
            busyOption = null
            result
              .onSuccess { msg ->
                onResultMessage(msg)
                onDismiss()
              }
              .onFailure { err ->
                onResultMessage(err.localizedMessage ?: "Failed to share web app file")
              }
          }
        },
      )

      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@Composable
private fun ShareOptionCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color,
  isLoading: Boolean,
  enabled: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
      ),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
    modifier = Modifier.fillMaxWidth().testTag(tag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier.size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accentColor.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.4.dp,
            color = accentColor,
          )
        } else {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accentColor,
            modifier = Modifier.size(24.dp),
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
