package com.xiaomi.fixnotification.util

import android.content.Context
import com.xiaomi.fixnotification.BuildConfig

object AppVersionHelper {
    @JvmStatic
    fun getVersionName(context: Context? = null): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            context?.let { ctx ->
                try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                } catch (_: Throwable) { null }
            } ?: "1.1.3.2.ntd"
        }
    }
}
