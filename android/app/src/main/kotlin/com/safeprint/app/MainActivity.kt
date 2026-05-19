package com.safeprint.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val methodChannelName = "gcash_capture/methods"
    private val eventChannelName = "gcash_capture/events"
    private val postNotificationsRequestCode = 2001
    private var pendingNotificationPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openNotificationAccessSettings" -> {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                        result.success(true)
                    }

                    "isNotificationAccessGranted" -> {
                        result.success(hasNotificationListenerAccess())
                    }

                    "isAppNotificationPermissionGranted" -> {
                        result.success(hasAppNotificationPermission())
                    }

                    "requestAppNotificationPermission" -> {
                        requestAppNotificationPermission(result)
                    }

                    "loadSavedNotifications" -> {
                        result.success(NotificationCaptureService.loadSavedNotifications(this))
                    }

                    "isCaptureEnabled" -> {
                        result.success(NotificationCaptureService.isCaptureEnabled(this))
                    }

                    "setCaptureEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        if (enabled) {
                            NotificationCaptureService.requestListenerResume(this)
                        } else {
                            NotificationCaptureService.setCaptureEnabled(this, false)
                            startService(Intent(this, NotificationCaptureService::class.java).apply {
                                action = "com.safeprint.app.action.STOP_CAPTURE"
                            })
                        }
                        result.success(true)
                    }

                    "syncSavedNotificationsToFirebase" -> {
                        NotificationCaptureService.syncSavedNotificationsToFirebase(this)
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(NotificationEventStreamHandler)
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabled.contains(packageName)
    }

    private fun hasAppNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAppNotificationPermission(result: MethodChannel.Result) {
        if (hasAppNotificationPermission()) {
            result.success(true)
            return
        }

        if (pendingNotificationPermissionResult != null) {
            result.error(
                "permission_request_in_progress",
                "A notification permission request is already in progress.",
                null
            )
            return
        }

        pendingNotificationPermissionResult = result
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), postNotificationsRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != postNotificationsRequestCode) {
            return
        }

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingNotificationPermissionResult?.success(granted)
        pendingNotificationPermissionResult = null
    }
}
