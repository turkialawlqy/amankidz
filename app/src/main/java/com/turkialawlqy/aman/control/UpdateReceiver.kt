package com.turkialawlqy.aman.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** نتيجة تثبيت التحديث + إعادة فتح التطبيق بعد التحديث */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // انثبّت الإصدار الجديد: نرجّع التطبيق لو كان مفتوح (أندرويد قد يمنع ذلك بالخلفية)
            try {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { context.startActivity(it) }
            } catch (_: Exception) {
            }
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        AppUpdater.onInstallResult(
            context.applicationContext,
            status,
            confirm,
            intent.getBooleanExtra(EXTRA_USER, false),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }

    companion object {
        const val EXTRA_USER = "user_initiated"
    }
}
