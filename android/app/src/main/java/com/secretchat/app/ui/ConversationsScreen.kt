package com.secretchat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(viewModel: AppViewModel) {
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    val conversations = viewModel.conversations

    LaunchedEffect(Unit) {
        viewModel.refreshConversations()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secret Chat") },
                actions = {
                    TextButton(onClick = { viewModel.signOut() }) {
                        Text("Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            viewModel.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No chats yet. Tap + to message someone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.conversationId }) { conversation ->
                        ListItem(
                            headlineContent = { Text(conversation.title) },
                            supportingContent = {
                                Text(conversation.lastMessage ?: "Say hello")
                            },
                            trailingContent = {
                                Text(
                                    formatTimestamp(conversation.lastMessageAt),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.openConversation(conversation)
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Start a chat") },
            text = {
                Column {
                    Text("Enter the username of the person you want to message.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.lowercase() },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.startChat(username) {
                            showNewChat = false
                            username = ""
                        }
                    },
                    enabled = username.isNotBlank() && !viewModel.busy,
                ) {
                    Text(if (viewModel.busy) "Opening…" else "Open chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChat = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
