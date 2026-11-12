package com.secretchat.app

import android.app.Application
import com.secretchat.app.data.SupabaseModule

class SecretChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Fail fast on a missing configuration, but keep the app usable for a
        // first launch so the user sees a meaningful error in the UI.
        SupabaseModule.client()
    }
}
