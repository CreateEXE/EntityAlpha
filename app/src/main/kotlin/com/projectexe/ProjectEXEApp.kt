package com.projectexe

import android.app.Application

class ProjectEXEApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Room and LlamaEngine are initialized lazily on first access.
        // LlamaEngine JNI library is loaded in MainActivity.onCreate()
        // before Compose starts to guarantee the .so is available.
    }
}
