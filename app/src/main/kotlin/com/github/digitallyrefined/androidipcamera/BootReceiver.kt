package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.RecordingScheduler

/**
 * Starts the streaming service after the device boots, when the "start_on_boot" preference or the
 * recording schedule is enabled, and re-arms the schedule alarm after clock or app changes.
 * Useful for unattended installations where the device is expected to come back online by itself
 * after a power cut or a reboot.
 *
 * StreamingService owns the camera and the HTTP server, so no activity needs to be shown.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> onBoot(context)
            // Alarms are kept across these, but the next boundary moved (clock/zone) or the
            // process was replaced (app update): recompute and re-arm.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                if (RecordingScheduler.isEnabled(context)) RecordingScheduler.armNextAlarm(context)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (RecordingScheduler.isEnabled(context) && hasCameraPermission(context)) {
                    RecordingScheduler.requestCheck(context)
                }
        }
    }

    private fun onBoot(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val startOnBoot = prefs.getBoolean("start_on_boot", false)
        val scheduleOn = RecordingScheduler.isEnabled(context)
        if (!startOnBoot && !scheduleOn) return

        // The service is a camera foreground service; without the permission it cannot start.
        if (!hasCameraPermission(context)) {
            Log.w(TAG, "start on boot / schedule enabled but the camera permission is not granted")
            return
        }

        // No activity is started on boot, and the activity is what normally calls
        // startStreamingServer() once it binds — so ask the service to start the server itself.
        // Starting the server also runs a schedule check (resume a window in progress, re-arm
        // the alarm that the reboot wiped).
        if (!startOnBoot) {
            // Schedule only: the check starts the server when it needs to record.
            RecordingScheduler.requestCheck(context)
            return
        }
        val serviceIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_SERVER
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming service on boot", e)
        }
    }

    private fun hasCameraPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "BootReceiver"
    }
}
