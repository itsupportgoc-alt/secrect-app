package com.secretchat.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.secretchat.app.data.AuthRepository
import com.secretchat.app.data.ChatRepository
import com.secretchat.app.data.ConversationSummary
import com.secretchat.app.data.Message
import com.secretchat.app.data.PendingImage
import com.secretchat.app.data.SendOutcome
import com.secretchat.app.data.SupabaseModule
import com.secretchat.app.outbox.PendingMessage
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val client = SupabaseModule.client()
    private val authRepository = AuthRepository(client)
    private val chatRepository = ChatRepository(client, application)

    val sessionStatus = authRepository.sessionStatus
    val currentUserId: String?
        get() = authRepository.currentUserId

    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList())
        private set
    var activeConversation by mutableStateOf<ConversationSummary?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var pendingMessages by mutableStateOf<List<PendingMessage>>(emptyList())
        private set
    var pendingImages by mutableStateOf<List<PendingImage>>(emptyList())
        private set
    var sendingPhoto by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var messageJob: Job? = null
    private var flushJob: Job? = null
    private var authenticatedUserId: String? = null
    private var pushTokenRegistered = false

    init {
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val userId = status.session.user?.id
                        if (userId != null && userId != authenticatedUserId) {
                            authenticatedUserId = userId
                            pushTokenRegistered = false
                            refreshConversations()
                            registerPushToken(userId)
                        }
                    }

                    is SessionStatus.NotAuthenticated -> {
                        authenticatedUserId = null
                        conversations = emptyList()
                        closeConversation()
                    }

                    else -> Unit
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        runAuth { authRepository.signIn(email.trim(), password) }
    }

    fun signUp(email: String, password: String, username: String) {
        val normalized = username.trim().lowercase().removePrefix("@")
        if (!normalized.matches(USERNAME_PATTERN)) {
            error = "Usernames are 3-24 lowercase letters, numbers or underscores."
            return
        }
        if (password.length < 6) {
            error = "Use a password with at least 6 characters."
            return
        }
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try {
                authRepository.signUp(email.trim(), password, normalized)
                if (authRepository.currentUserId == null) {
                    notice = "Account created. Confirm your email, then sign in."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { authRepository.signOut() }
        }
    }

    fun dismissMessages() {
        error = null
        notice = null
    }

    fun refreshConversations() {
        viewModelScope.launch {
            runCatching { chatRepository.conversations() }
                .onSuccess { conversations = it }
                .onFailure {
                    if (authenticatedUserId != null) error = friendly(it)
                }
        }
    }

    fun startChat(username: String, onOpened: () -> Unit) {
        val normalized = username.trim().lowercase().removePrefix("@")
        if (normalized.isBlank()) {
            error = "Enter a username."
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val conversationId = chatRepository.startDm(normalized)
                conversations = chatRepository.conversations()
                val conversation = conversations.firstOrNull { it.conversationId == conversationId }
                    ?: ConversationSummary(conversationId, title = "@$normalized")
                openConversation(conversation)
                onOpened()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                busy = false
            }
        }
    }

    fun openConversation(conversation: ConversationSummary) {
        activeConversation = conversation
        messages = emptyList()
        pendingMessages = emptyList()
        pendingImages = emptyList()

        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            chatRepository.messages(conversation.conversationId).collect { latest ->
                messages = latest
            }
        }

        // Retry queued messages and photos while this chat is open.
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            while (isActive) {
                chatRepository.flushOutbox()
                refreshPendingNow()
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    fun closeConversation() {
        messageJob?.cancel()
        messageJob = null
        flushJob?.cancel()
        flushJob = null
        activeConversation = null
        messages = emptyList()
        pendingMessages = emptyList()
        pendingImages = emptyList()
    }

    fun sendMessage(text: String) {
        val conversation = activeConversation ?: return
        val body = text.trim()
        if (body.isEmpty()) return

        notice = null
        viewModelScope.launch {
            when (chatRepository.sendMessage(conversation.conversationId, body)) {
                SendOutcome.QUEUED ->
                    notice = "You're offline. The message is queued and will be retried."
                SendOutcome.SENT -> Unit
            }
            refreshPendingNow()
            runCatching { chatRepository.conversations() }
                .onSuccess { conversations = it }
        }
    }

    /** Sends a photo picked from the gallery. */
    fun sendImage(uri: Uri) {
        val conversation = activeConversation ?: return
        notice = null
        error = null
        sendingPhoto = true
        viewModelScope.launch {
            try {
                when (chatRepository.sendImage(conversation.conversationId, uri)) {
                    SendOutcome.QUEUED ->
                        notice = "Photo couldn't be sent yet. It's queued and will be retried."
                    SendOutcome.SENT -> Unit
                }
                refreshPendingNow()
                runCatching { chatRepository.conversations() }
                    .onSuccess { conversations = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                error = "Couldn't use that photo: ${friendly(t)}"
            } finally {
                sendingPhoto = false
            }
        }
    }

    fun cachedImage(path: String): Bitmap? = chatRepository.cachedImage(path)

    suspend fun loadImage(path: String): Bitmap? = chatRepository.loadImage(path)

    fun pendingImageFile(clientId: String): File = chatRepository.pendingImageFile(clientId)

    private fun runAuth(block: suspend () -> Unit) {
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun refreshPendingNow() {
        val conversationId = activeConversation?.conversationId
        pendingMessages = chatRepository.pending(conversationId)
        pendingImages = chatRepository.pendingImages(conversationId)
    }

    private fun registerPushToken(userId: String) {
        if (pushTokenRegistered) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                pushTokenRegistered = true
                viewModelScope.launch {
                    runCatching { chatRepository.registerPushToken(token, userId) }
                }
            }
        } catch (_: Throwable) {
            // FCM is optional: without google-services.json the app still works.
        }
    }

    private fun friendly(t: Throwable): String =
        (t.message ?: "Something went wrong").take(MAX_ERROR_LENGTH)

    override fun onCleared() {
        chatRepository.close()
        super.onCleared()
    }

    private companion object {
        val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,24}$")
        const val FLUSH_INTERVAL_MS = 15_000L
        const val MAX_ERROR_LENGTH = 240
    }
}
