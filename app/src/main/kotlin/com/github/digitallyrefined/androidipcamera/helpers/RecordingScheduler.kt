package com.github.digitallyrefined.androidipcamera.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.ScheduleAlarmReceiver
import com.github.digitallyrefined.androidipcamera.StreamingService
import java.util.TimeZone

/**
 * Stores the recording schedule and drives the single alarm that wakes the app at the next
 * start/stop boundary. The actual start/stop happens in [StreamingService.reconcileSchedule].
 *
 * Rules live in the default SharedPreferences as JSON, so they survive app restarts and reboots.
 * Alarms do not survive a reboot; [com.github.digitallyrefined.androidipcamera.BootReceiver]
 * re-arms them.
 */
object RecordingScheduler {
    private const val TAG = "RecordingScheduler"
    const val PREF_ENABLED = "schedule_enabled"
    const val PREF_RULES = "schedule_rules_json"
    private const val REQUEST_CODE = 4401

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    fun loadRules(context: Context): List<ScheduleRule> =
        ScheduleRule.listFromJson(
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_RULES, null)
        )

    fun saveRules(context: Context, rules: List<ScheduleRule>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_RULES, ScheduleRule.listToJson(rules))
            .apply()
    }

    /** Rules that count right now: none when the master switch is off. */
    private fun effectiveRules(context: Context): List<ScheduleRule> =
        if (isEnabled(context)) loadRules(context) else emptyList()

    fun shouldRecordNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        ScheduleMath.shouldRecord(effectiveRules(context), nowMs, TimeZone.getDefault())

    fun nextChange(context: Context, nowMs: Long = System.currentTimeMillis()): Long? =
        ScheduleMath.nextChange(effectiveRules(context), nowMs, TimeZone.getDefault())

    /**
     * Sets the alarm for the next boundary (or [retryAtMs] if that is sooner, used when a scheduled
     * start failed). Cancels it when there is nothing to schedule. Returns the time armed, or null.
     */
    fun armNextAlarm(context: Context, retryAtMs: Long? = null): Long? {
        val am = context.getSystemService(AlarmManager::class.java) ?: return null
        val pi = alarmIntent(context)
        val next = listOfNotNull(nextChange(context), retryAtMs).minOrNull()
        if (next == null) {
            am.cancel(pi)
            Log.i(TAG, "No schedule boundary ahead; alarm cancelled")
            return null
        }
        try {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            when {
                exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                exactAllowed -> am.setExact(AlarmManager.RTC_WAKEUP, next, pi)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                else -> am.set(AlarmManager.RTC_WAKEUP, next, pi)
            }
            Log.i(TAG, "Next schedule check armed for ${java.util.Date(next)} (exact=$exactAllowed)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot arm exact alarm, falling back to inexact", e)
            am.set(AlarmManager.RTC_WAKEUP, next, pi)
        }
        return next
    }

    /** Asks the service to re-check the schedule now (after edits, at boot, or from the alarm). */
    fun requestCheck(context: Context) {
        val intent = Intent(context, StreamingService::class.java).setAction(StreamingService.ACTION_SCHEDULE_CHECK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not reach StreamingService for a schedule check", e)
            // Keep the chain alive even if the service could not be started this time.
            armNextAlarm(context, retryAtMs = System.currentTimeMillis() + 60_000L)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java).setAction(ScheduleAlarmReceiver.ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
