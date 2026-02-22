package com.openclaw.chat

import android.app.Application

class ChatApplication : Application() {
    
    companion object {
        lateinit var instance: ChatApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
