package com.github.digitallyrefined.androidipcamera.helpers

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * One recording window: record from [startMinute] to [endMinute] (minutes after midnight) on the
 * given [days]. When the end is earlier than the start the window runs past midnight and ends the
 * next day (e.g. 22:00 → 06:00). [days] uses [Calendar.DAY_OF_WEEK] values (1 = Sunday … 7 = Saturday)
 * and names the day the window *starts*.
 */
data class ScheduleRule(
    val id: String,
    val enabled: Boolean,
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int
) {
    /** Window length in minutes (1..1439). Start == end is rejected by [isValid]. */
    val durationMinutes: Int get() = ((endMinute - startMinute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

    fun isValid(): Boolean =
        startMinute in 0 until MINUTES_PER_DAY &&
            endMinute in 0 until MINUTES_PER_DAY &&
            startMinute != endMinute &&
            days.isNotEmpty() &&
            days.all { it in Calendar.SUNDAY..Calendar.SATURDAY }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("days", JSONArray(days.sorted()))
        put("start", startMinute)
        put("end", endMinute)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun fromJson(o: JSONObject): ScheduleRule? = try {
            val daysArr = o.getJSONArray("days")
            ScheduleRule(
                id = o.getString("id"),
                enabled = o.optBoolean("enabled", true),
                days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet(),
                startMinute = o.getInt("start"),
                endMinute = o.getInt("end")
            ).takeIf { it.isValid() }
        } catch (_: Exception) {
            null
        }

        fun listToJson(rules: List<ScheduleRule>): String =
            JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String?): List<ScheduleRule> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun formatMinute(m: Int): String = String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }
}

/**
 * Pure schedule arithmetic (no Android dependencies, unit-tested on the JVM).
 *
 * The scheduler never tracks "what it did last"; it only asks two questions at any moment:
 * should we be recording right now, and when does that answer next change? The service reconciles
 * the recorder with the first answer and sets an alarm for the second. Overlapping rules, reboots
 * and missed alarms all fall out of that for free.
 */
object ScheduleMath {

    /** A concrete window in epoch milliseconds, [startMs, endMs). */
    data class Window(val startMs: Long, val endMs: Long)

    /**
     * All windows of the enabled [rules] that touch the range [fromMs - 1 day, fromMs + [daysAhead] days].
     * Built per calendar day in [tz] so DST changes keep the wall-clock times the user picked.
     */
    fun windows(rules: List<ScheduleRule>, fromMs: Long, tz: TimeZone, daysAhead: Int = 8): List<Window> {
        val out = ArrayList<Window>()
        val active = rules.filter { it.enabled && it.isValid() }
        if (active.isEmpty()) return out
        for (offset in -1..daysAhead) {
            val day = Calendar.getInstance(tz).apply {
                timeInMillis = fromMs
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, offset)
            }
            val dow = day.get(Calendar.DAY_OF_WEEK)
            for (rule in active) {
                if (dow !in rule.days) continue
                val start = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, rule.startMinute / 60)
                    set(Calendar.MINUTE, rule.startMinute % 60)
                }
                val end = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, rule.endMinute / 60)
                    set(Calendar.MINUTE, rule.endMinute % 60)
                    if (rule.endMinute <= rule.startMinute) add(Calendar.DAY_OF_MONTH, 1)
                }
                if (end.timeInMillis > start.timeInMillis) out.add(Window(start.timeInMillis, end.timeInMillis))
            }
        }
        return out
    }

    /** True when [nowMs] falls inside any enabled rule's window. */
    fun shouldRecord(rules: List<ScheduleRule>, nowMs: Long, tz: TimeZone): Boolean =
        windows(rules, nowMs, tz).any { nowMs >= it.startMs && nowMs < it.endMs }

    /**
     * The next moment after [nowMs] where [shouldRecord] flips, or null when no rule is enabled.
     * Back-to-back or overlapping windows are merged, so a 20:00–22:00 rule followed by a
     * 22:00–23:00 rule produces one stop at 23:00 rather than a stop and an immediate restart.
     */
    fun nextChange(rules: List<ScheduleRule>, nowMs: Long, tz: TimeZone): Long? {
        val merged = merge(windows(rules, nowMs, tz))
        for (w in merged) {
            if (w.startMs > nowMs) return w.startMs
            if (w.endMs > nowMs) return w.endMs
        }
        return null
    }

    internal fun merge(windows: List<Window>): List<Window> {
        val sorted = windows.sortedBy { it.startMs }
        val out = ArrayList<Window>()
        for (w in sorted) {
            val last = out.lastOrNull()
            if (last != null && w.startMs <= last.endMs) {
                out[out.size - 1] = Window(last.startMs, maxOf(last.endMs, w.endMs))
            } else out.add(w)
        }
        return out
    }
}
