package dev.labushuya.hushd

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AutostopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO(v2): initialize Timber logging, crash reporter (local file only)
    }
}
