package com.safeprint.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService

class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!NotificationCaptureService.isCaptureEnabled(context)) {
            return
        }

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> {
                NotificationCaptureService.requestListenerResume(context)

                val serviceIntent = Intent(context, NotificationCaptureService::class.java).apply {
                    action = "com.safeprint.app.action.START_CAPTURE"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                NotificationListenerService.requestRebind(
                    ComponentName(context, NotificationCaptureService::class.java)
                )
            }
        }
    }
}