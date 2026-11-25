package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.firebase.FirestoreSignalingBridge
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray

private const val VIRTUAL_ORIGIN_HOST = "appassets.androidplatform.net"
private const val ENTRY_URL = "https://$VIRTUAL_ORIGIN_HOST/index.html"

private class VoxShiftJsBridge(
  private val context: Context,
  private val activity: Activity?,
  private val onBlockNumber: (String, String?) -> Unit,
  private val onUnblockNumber: (String) -> Unit,
  private val onRequestShareApp: () -> Unit,
  private val signaling: FirestoreSignalingBridge,
) {
  @JavascriptInterface
  fun isNativeCloudReady(): Boolean = true

  @JavascriptInterface
  fun copyToClipboard(text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("VoxShift Room Code", text))
  }

  @JavascriptInterface
  fun setCallActive(active: Boolean) {
    activity?.runOnUiThread {
      if (active) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      audioManager?.mode =
        if (active) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }
  }

  @JavascriptInterface
  fun blockNumberInNative(phoneNumber: String, reason: String?) {
    activity?.runOnUiThread { onBlockNumber(phoneNumber, reason) }
  }

  @JavascriptInterface
  fun unblockNumberInNative(entryId: String) {
    activity?.runOnUiThread { onUnblockNumber(entryId) }
  }

  @JavascriptInterface
  fun openShareAppSheet() {
    activity?.runOnUiThread { onRequestShareApp() }
  }

  @JavascriptInterface
  fun nativeStartCallLogs() {
    signaling.startCallLogsSubscription()
  }

  @JavascriptInterface
  fun nativePushCallLog(jsonPayload: String) {
    signaling.pushCallLog(jsonPayload)
  }

  @JavascriptInterface
  fun nativeClearCallLogs() {
    signaling.clearAllCallLogs()
  }

  @JavascriptInterface
  fun nativeOpenHostRoom(roomCode: String, reqId: String) {
    signaling.openHostRoom(roomCode, reqId)
  }

  @JavascriptInterface
  fun nativeGetRoom(roomCode: String, reqId: String) {
    signaling.getRoomState(roomCode, reqId)
  }

  @JavascriptInterface
  fun nativeSendOffer(roomCode: String, offerSdp: String, callerNumber: String?, reqId: String) {
    signaling.sendCallOffer(roomCode, offerSdp, callerNumber, reqId)
  }

  @JavascriptInterface
  fun nativeSendAnswer(roomCode: String, answerSdp: String, reqId: String) {
    signaling.sendCallAnswer(roomCode, answerSdp, reqId)
  }

  @JavascriptInterface
  fun nativeUpdateRoomStatus(roomCode: String, status: String, blocked: Boolean) {
    signaling.updateRoomStatus(roomCode, status, blocked)
  }

  @JavascriptInterface
  fun nativeDeleteRoom(roomCode: String) {
    signaling.deleteRoom(roomCode)
  }

  @JavascriptInterface
  fun nativeAddIceCandidate(roomCode: String, role: String, candidateJson: String) {
    signaling.addIceCandidate(roomCode, role, candidateJson)
  }

  @JavascriptInterface
  fun nativeSubscribeRoom(roomCode: String) {
    signaling.subscribeRoom(roomCode)
  }

  @JavascriptInterface
  fun nativeUnsubscribeRoom(roomCode: String) {
    signaling.unsubscribeRoom(roomCode)
  }

  @JavascriptInterface
  fun nativeSubscribeCandidates(roomCode: String, targetRole: String) {
    signaling.subscribeCandidates(roomCode, targetRole)
  }

  @JavascriptInterface
  fun nativeUnsubscribeCandidates(roomCode: String) {
    signaling.unsubscribeCandidates(roomCode)
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VoxShiftScreen(
  blockedNumbers: List<String> = emptyList(),
  onBlockFromWeb: (phoneNumber: String, reason: String?) -> Unit = { _, _ -> },
  onUnblockFromWeb: (entryId: String) -> Unit = {},
  onRequestShareApp: () -> Unit = {},
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val scope = rememberCoroutineScope()

  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var pageLoaded by remember { mutableStateOf(false) }
  var pendingWebPermission by remember { mutableStateOf<PermissionRequest?>(null) }
  var showMicDeniedDialog by remember { mutableStateOf(false) }

  val signalingBridge = remember(context) {
    val dbId = context.applicationContext.getString(R.string.firestore_database_id)
    val db = FirebaseFirestore.getInstance(dbId)
    FirestoreSignalingBridge(
      db = db,
      scope = scope,
      dispatchToWeb = { jsCall ->
        activity?.runOnUiThread { webViewRef?.evaluateJavascript(jsCall, null) }
      },
    )
  }

  val latestOnBlock by rememberUpdatedState(onBlockFromWeb)
  val latestOnUnblock by rememberUpdatedState(onUnblockFromWeb)
  val latestOnShare by rememberUpdatedState(onRequestShareApp)

  // Sync Firestore blocked numbers into WebView whenever the list changes or page finishes loading
  LaunchedEffect(blockedNumbers, pageLoaded, webViewRef) {
    val webView = webViewRef
    if (webView != null && pageLoaded) {
      val json = JSONArray(blockedNumbers).toString()
      webView.evaluateJavascript(
        "if (window.syncNativeBlockedNumbers) { window.syncNativeBlockedNumbers($json); }",
        null,
      )
    }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
      val req = pendingWebPermission
      pendingWebPermission = null
      if (granted) {
        req?.grant(req.resources)
      } else {
        req?.deny()
        showMicDeniedDialog = true
      }
    }

  BackHandler {
    val webView = webViewRef
    if (webView != null) {
      webView.evaluateJavascript(
        "(function(){ return window.handleAndroidBack ? window.handleAndroidBack() : false; })();"
      ) { result ->
        if (result == "false" || result == "null") {
          activity?.finish()
        }
      }
    } else {
      activity?.finish()
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      signalingBridge.dispose()
      activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      audioManager?.mode = AudioManager.MODE_NORMAL
      pendingWebPermission?.deny()
      pendingWebPermission = null
      webViewRef?.destroy()
      webViewRef = null
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    AndroidView(
      modifier = Modifier.fillMaxSize().testTag("voxshift_webview"),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(Color.parseColor("#090C14"))

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
          }

          addJavascriptInterface(
            VoxShiftJsBridge(
              context = ctx,
              activity = activity,
              onBlockNumber = { num, reason -> latestOnBlock(num, reason) },
              onUnblockNumber = { id -> latestOnUnblock(id) },
              onRequestShareApp = { latestOnShare() },
              signaling = signalingBridge,
            ),
            "AndroidBridge",
          )

          webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
              }

              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
              ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (
                  url.scheme == "https" &&
                    url.host == VIRTUAL_ORIGIN_HOST &&
                    (url.path == "/" || url.path == "/index.html")
                ) {
                  return try {
                    val stream = ctx.assets.open("index.html")
                    WebResourceResponse(
                      "text/html",
                      "UTF-8",
                      200,
                      "OK",
                      mapOf("Cache-Control" to "no-store"),
                      stream,
                    )
                  } catch (_: Exception) {
                    null
                  }
                }
                return null
              }
            }

          webChromeClient =
            object : WebChromeClient() {
              override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requestsAudio =
                  request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!requestsAudio) {
                  request.deny()
                  return
                }
                val hasMic =
                  ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                  request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                  pendingWebPermission?.deny()
                  pendingWebPermission = request
                  micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
              }
            }

          webViewRef = this
          loadUrl(ENTRY_URL)
        }
      },
    )
  }

  if (showMicDeniedDialog) {
    AlertDialog(
      onDismissRequest = { showMicDeniedDialog = false },
      icon = {
        Icon(
          imageVector = Icons.Filled.Mic,
          contentDescription = stringResource(R.string.mic_permission_title),
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      title = { Text(text = stringResource(R.string.mic_permission_title)) },
      text = { Text(text = stringResource(R.string.mic_permission_rationale)) },
      confirmButton = {
        Button(
          onClick = {
            showMicDeniedDialog = false
            val intent =
              Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
              }
            context.startActivity(intent)
          },
          modifier = Modifier.testTag("open_settings_button"),
        ) {
          Text(text = stringResource(R.string.mic_permission_settings))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showMicDeniedDialog = false },
          modifier = Modifier.testTag("dismiss_mic_dialog_button"),
        ) {
          Text(text = stringResource(R.string.mic_permission_dismiss))
        }
      },
    )
  }
}
