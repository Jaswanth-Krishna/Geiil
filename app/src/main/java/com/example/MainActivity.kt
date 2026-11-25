package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.ui.AuthScreen
import com.example.ui.MainAppShell
import com.example.ui.theme.VoxShiftTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // STEP 1 (MANDATORY): Configure Firebase App Check with launch intent before UI/ViewModels
    configureAppCheck(this, intent)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    setContent {
      VoxShiftTheme {
        VoxShiftRootNavigation()
      }
    }
  }
}

@Composable
private fun VoxShiftRootNavigation() {
  val auth = Firebase.auth
  val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
  val user = currentUser

  if (user == null) {
    AuthScreen(onAuthSuccess = {})
  } else {
    key(user.uid) {
      MainAppShell(currentUserId = user.uid)
    }
  }
}
