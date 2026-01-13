package com.nosmoke.timer

import android.app.Application

class AirTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Service will be started by BootReceiver or MainActivity
    }
}

