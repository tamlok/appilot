package com.vnote.appilot.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * AlarmManager-backed reschedule loop for the regulation cycle.
 *
 * Each cycle ends by scheduling the next one [intervalMinutes] later via a single
 * cancel-and-replace [PendingIntent] that re-starts [RegulatorService] as a
 * foreground service. We prefer `setExactAndAllowWhileIdle` (paired with the
 * Doze-exemption prompt) so the cadence does not drift; but exact alarms need the
 * `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` grant, so when it is not held we fall
 * back to `setAndAllowWhileIdle` (inexact but permission-free) rather than crash.
 *
 * The PendingIntent is stable (same request code + action), so [isScheduled] can
 * probe it with `FLAG_NO_CREATE` and tests can prove the next cycle was armed.
 */
object RegulatorScheduler {

    const val REQUEST_CODE = 0x5C0D
    const val ACTION_RUN_CYCLE = "com.vnote.appilot.service.RUN_CYCLE"

    /** Arm the next cycle [intervalMinutes] from now. */
    fun scheduleNext(context: Context, intervalMinutes: Int) {
        val triggerAt = SystemClock.elapsedRealtime() + intervalMinutes * 60_000L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, create = true)!!
        if (canScheduleExact(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending,
            )
        }
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Cancel any armed cycle (e.g. when the user stops the regulator). */
    fun cancel(context: Context) {
        val existing = pendingIntent(context, create = false) ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(existing)
        existing.cancel()
    }

    /** True when a next cycle is currently armed. */
    fun isScheduled(context: Context): Boolean =
        pendingIntent(context, create = false) != null

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, RegulatorService::class.java)
            .setAction(ACTION_RUN_CYCLE)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) 0 else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getForegroundService(context, REQUEST_CODE, intent, flags)
    }
}
