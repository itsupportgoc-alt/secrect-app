package com.secretchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secretchat.app.notifications.ActiveChat
import com.secretchat.app.notifications.MessageNotifications
import com.secretchat.app.ui.AppViewModel
import com.secretchat.app.ui.AuthScreen
import com.secretchat.app.ui.ChatScreen
import com.secretchat.app.ui.ConversationsScreen
import com.secretchat.app.ui.UpdateDialog
import com.secretchat.app.ui.theme.SecretChatTheme
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationTap(intent)
        setContent {
            SecretChatTheme {
                SecretChatApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    override fun onResume() {
        super.onResume()
        ActiveChat.appVisible = true
        viewModel.activeConversation?.let {
            MessageNotifications.cancel(this, it.conversationId)
        }
        viewModel.checkForUpdates()
    }

    override fun onPause() {
        ActiveChat.appVisible = false
        super.onPause()
    }

    private fun handleNotificationTap(intent: Intent?) {
        val conversationId = intent?.getStringExtra(MessageNotifications.EXTRA_CONVERSATION_ID) ?: return
        val title = intent.getStringExtra(MessageNotifications.EXTRA_TITLE).orEmpty()
        intent.removeExtra(MessageNotifications.EXTRA_CONVERSATION_ID)
        viewModel.openFromNotification(conversationId, title)
    }
}

@Composable
private fun SecretChatApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (sessionStatus) {
            SessionStatus.Initializing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is SessionStatus.Authenticated -> {
                val conversation = viewModel.activeConversation
                if (conversation == null) {
                    ConversationsScreen(viewModel)
                } else {
                    ChatScreen(viewModel, conversation)
                }
            }

            else -> AuthScreen(viewModel)
        }
    }

    UpdateDialog(viewModel)
}
