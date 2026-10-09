package com.xiaomi.fixnotification

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FixXiaomi", "CRASH in thread " + thread.name, throwable)
            try {
                val prefs = getSharedPreferences("app_crash_log", Context.MODE_PRIVATE)
                prefs.edit().putString("last_crash_trace", Log.getStackTraceString(throwable)).commit()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            if (plugged > 0) {
                BatteryTrackerService.startTracking(this)
            }
        } catch (_: Throwable) {}
    }
}
