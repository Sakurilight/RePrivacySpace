package com.reprivacyspace.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // AppCenter (analytics/crashes) was retired upstream; the module no longer
        // phones home.  Keep the class so the manifest entry stays valid.
    }
}
