package com.astralnetwork.poc

import android.app.Application
import android.util.Log

class AstralApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("ASTRAL_NET", "Application initialized")
    }
}
