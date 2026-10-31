package com.github.digitallyrefined.androidipcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.digitallyrefined.androidipcamera.helpers.RecordingScheduler

/**
 * Woken by the scheduler's exact alarm at each start/stop boundary. It does no work itself: it
 * forwards to StreamingService, which owns the camera and the recorder and re-arms the next alarm.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        Log.i(TAG, "Schedule alarm fired")
        RecordingScheduler.requestCheck(context)
    }

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        const val ACTION_ALARM = "com.github.digitallyrefined.androidipcamera.SCHEDULE_ALARM"
    }
}
