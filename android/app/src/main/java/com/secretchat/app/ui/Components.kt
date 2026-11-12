package com.secretchat.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretchat.app.data.AppUpdate
import com.secretchat.app.data.AvatarLoader

private val AvatarColors = listOf(
    Color(0xFF5B3FD9),
    Color(0xFF0E7C86),
    Color(0xFFB4235A),
    Color(0xFF2E7D32),
    Color(0xFFB45309),
    Color(0xFF1D4ED8),
)

/** Round profile picture, or the first letter of the name on a coloured circle. */
@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let { AvatarLoader.cached(it) }, url) {
        if (value == null && !url.isNullOrBlank()) value = AvatarLoader.load(url)
    }
    val clean = name.trim().removePrefix("@")
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AvatarColors[(clean.hashCode() and Int.MAX_VALUE) % AvatarColors.size]),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "$clean's picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                clean.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** "Update available" dialog with download progress. */
@Composable
fun UpdateDialog(viewModel: AppViewModel) {
    val update: AppUpdate = viewModel.availableUpdate ?: return
    val progress = viewModel.updateProgress
    AlertDialog(
        onDismissRequest = { if (progress == null) viewModel.dismissUpdate() },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Secret Chat ${update.version} is ready to install.")
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes.take(600),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading… ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                viewModel.updateError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.startUpdate() }, enabled = progress == null) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissUpdate() }, enabled = progress == null) {
                Text("Later")
            }
        },
    )
}

/** Your profile: picture (tap to change), username and sign out. */
@Composable
fun ProfileDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val profile = viewModel.myProfile
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.updateAvatar(uri)
    }
    val name = profile?.displayName?.ifBlank { null } ?: profile?.username ?: "Me"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your profile") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Avatar(profile?.avatarUrl, name, 96.dp)
                    if (viewModel.updatingAvatar) CircularProgressIndicator(Modifier.size(40.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(name, style = MaterialTheme.typography.titleMedium)
                profile?.username?.let {
                    Text(
                        "@$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !viewModel.updatingAvatar,
                ) {
                    Text(if (profile?.avatarUrl.isNullOrBlank()) "Add a picture" else "Change picture")
                }
                viewModel.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    viewModel.signOut()
                },
            ) {
                Text("Sign out")
            }
        },
    )
}
