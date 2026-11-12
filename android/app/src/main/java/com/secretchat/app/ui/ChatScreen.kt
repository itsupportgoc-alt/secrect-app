package com.secretchat.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secretchat.app.data.ConversationSummary
import com.secretchat.app.data.Message
import com.secretchat.app.data.PendingImage
import com.secretchat.app.outbox.PendingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel, conversation: ConversationSummary) {
    val messages = viewModel.messages
    val pending = viewModel.pendingMessages
    val pendingImages = viewModel.pendingImages
    val currentUserId = viewModel.currentUserId
    var draft by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Bitmap?>(null) }
    val listState = rememberLazyListState()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.sendImage(uri)
    }

    BackHandler {
        if (viewing != null) viewing = null else viewModel.closeConversation()
    }

    LaunchedEffect(messages.size, pending.size, pendingImages.size) {
        val total = messages.size + pending.size + pendingImages.size
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conversation.title)
                        Text(
                            "Real-time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { viewModel.closeConversation() }) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (messages.isEmpty() && pending.isEmpty() && pendingImages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No messages yet. Say hello.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            mine = message.senderId == currentUserId,
                            viewModel = viewModel,
                            onOpenImage = { viewing = it },
                        )
                    }
                    items(pending, key = { it.clientId }) { message ->
                        PendingBubble(message)
                    }
                    items(pendingImages, key = { "img-" + it.clientId }) { image ->
                        PendingImageBubble(image, viewModel.pendingImageFile(image.clientId))
                    }
                }
            }

            viewModel.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            viewModel.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        viewModel.dismissMessages()
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !viewModel.sendingPhoto,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (viewModel.sendingPhoto) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("📷")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        viewModel.dismissMessages()
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.sendMessage(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("Send")
                }
            }
        }
    }

    viewing?.let { bitmap ->
        FullScreenImage(bitmap) { viewing = null }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    mine: Boolean,
    viewModel: AppViewModel,
    onOpenImage: (Bitmap) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                message.imagePath?.let { path ->
                    ChatImage(path, viewModel, onOpenImage)
                }
                if (message.body.isNotBlank()) {
                    Text(message.body, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Text(
                    formatTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Ready(val bitmap: Bitmap) : ImageState
}

@Composable
private fun ChatImage(path: String, viewModel: AppViewModel, onOpenImage: (Bitmap) -> Unit) {
    val state by produceState<ImageState>(
        initialValue = viewModel.cachedImage(path)?.let { ImageState.Ready(it) } ?: ImageState.Loading,
        path,
    ) {
        if (value !is ImageState.Ready) {
            value = viewModel.loadImage(path)?.let { ImageState.Ready(it) } ?: ImageState.Failed
        }
    }

    when (val current = state) {
        is ImageState.Ready -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenImage(current.bitmap) },
        )

        ImageState.Loading -> ImagePlaceholder { CircularProgressIndicator(Modifier.size(24.dp)) }

        ImageState.Failed -> ImagePlaceholder {
            Text(
                "Photo unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImagePlaceholder(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 220.dp, height = 160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PendingBubble(message: PendingMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message.body)
                Text(
                    if (message.attempts == 0) {
                        "Sending…"
                    } else {
                        "Sending… retry ${message.attempts}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingImageBubble(image: PendingImage, file: File) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 4 },
                )
            }.getOrNull()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(8.dp)) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo waiting to send",
                        contentScale = ContentScale.Fit,
                        alpha = 0.6f,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    ImagePlaceholder { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                Text(
                    if (image.attempts == 0) "Sending photo…" else "Sending photo… retry ${image.attempts}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FullScreenImage(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
