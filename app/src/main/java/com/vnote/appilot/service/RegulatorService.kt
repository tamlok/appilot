package com.vnote.appilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.vnote.appilot.actuate.Actuator
import com.vnote.appilot.actuate.RegulatorAccessibilityService
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.store.ConfigStore
import com.vnote.appilot.core.store.TemplateStore
import com.vnote.appilot.launch.Presets
import com.vnote.appilot.decide.RegulatorAction
import com.vnote.appilot.launch.Launcher
import com.vnote.appilot.read.digits.TemplateMatcher
import com.vnote.appilot.read.projection.ScreenCapture
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that drives ONE regulation cycle per start and reschedules
 * itself via [RegulatorScheduler] — the orchestration loop.
 *
 * ## FGS types (Android 14+ / targetSdk 36)
 * Declared `specialUse|mediaProjection`. It promotes in two steps so it never
 * trips the platform rules:
 *  1. [onStartCommand] calls `startForeground(specialUse)` IMMEDIATELY — within
 *     the start deadline, and with no projection prerequisite.
 *  2. On the worker thread, after the screen guard passes, it re-calls
 *     `startForeground(mediaProjection|specialUse)`. Starting a `mediaProjection`
 *     FGS only requires the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission plus
 *     the `android:project_media` appop (granted by the consent screen, or
 *     pre-granted in the instrumented test) — NOT a live projection object. The
 *     frozen read layer ([ScreenCapture]) still owns the actual capture session.
 *
 * ## Guards
 * Runs a cycle only while the screen is interactive AND unlocked — a black or
 * locked screen would yield a useless capture. Otherwise it skips, reschedules
 * and stops. Battery-optimization exemption is offered via [requestBatteryExemption]
 * (a UI-invoked prompt; a background service may not pop a dialog autonomously).
 *
 * ## Readiness
 * The cycle never sleeps blindly: [CycleOrchestrator] polls [ForegroundRegistry]
 * (bounded) for each launched screen to reach the foreground before reading/tapping.
 */
class RegulatorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (1) Promote to a specialUse FGS immediately — no projection prerequisite,
        // satisfies the start-foreground deadline.
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Thread({ runCycleThenReschedule() }, "regulator-cycle").start()
        return START_NOT_STICKY
    }

    private fun runCycleThenReschedule() {
        val config = runBlocking { ConfigStore(applicationContext).load() } ?: Presets.defaultConfig()
        try {
            if (!screenReady()) {
                lastCycleResult = CycleResult.SourceUnavailable
                return
            }
            promoteToProjectionFgs()
            val orchestrator = buildOrchestrator(config)
            val result = orchestrator.runCycle(lastAction)
            (result as? CycleResult.Acted)?.let { lastAction = it.action }
            lastCycleResult = result
            android.util.Log.i(TAG, "cycle result=$result")
        } catch (t: Throwable) {
            // Never let a cycle fault crash the hosting process: record it so the
            // scheduler still re-arms and tests/QA can read the cause.
            lastError = "${t::class.java.name}: ${t.message}"
            android.util.Log.e(TAG, "cycle failed", t)
        } finally {
            // Re-arm the next cycle BEFORE releasing the foreground state so the
            // cadence is preserved even if this cycle aborted.
            runCatching { RegulatorScheduler.scheduleNext(applicationContext, config.intervalMinutes) }
                .onFailure { android.util.Log.e(TAG, "reschedule failed", it) }
            cycleCompletions.incrementAndGet()
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Re-promote with the mediaProjection type now that a cycle is going to capture. */
    private fun promoteToProjectionFgs() {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        startForeground(NOTIFICATION_ID, buildNotification(), types)
        lastForegroundServiceType = types
    }

    private fun buildOrchestrator(config: RegulatorConfig): CycleOrchestrator {
        currentTapTargets = config.tapTargets
        val matcher = TemplateMatcher(TemplateLoader.load(TemplateStore(applicationContext)))
        val reader = ProjectionTemperatureReader(applicationContext, config.readRegion, matcher)
        val launcher = Launcher.from(applicationContext)
        return CycleOrchestrator(
            config = config,
            launcher = launcher,
            foregroundCheck = { target -> ForegroundRegistry.matches(Readiness.tokenOf(target)) },
            reader = reader,
            tapper = ::tap,
        )
    }

    private fun tap(action: AcAction, steps: Int): Int {
        val service = RegulatorAccessibilityService.instance ?: return 0
        return Actuator(service, /* tapTargets */ currentTapTargets).perform(action, steps)
    }

    // Set just before the orchestrator runs so [tap] can map actions -> targets.
    private var currentTapTargets = emptyList<com.vnote.appilot.core.model.TapTarget>()

    private fun screenReady(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Regulating temperature")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // Cleanup receipt: end the projection session (token + VirtualDisplay + FGS).
        ScreenCapture.release(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RegulatorService"
        private const val NOTIFICATION_ID = 0x5C0F
        private const val CHANNEL_ID = "regulator_cycle"
        private const val CHANNEL_NAME = "AC regulator"

        @Volatile
        var lastError: String? = null
            private set

        /** Process-scoped last decision, fed back as `lastAction` (hysteresis). */
        @Volatile
        var lastAction: RegulatorAction = RegulatorAction.NONE

        /** The exact FGS type bitmask last passed to a successful startForeground promote. */
        @Volatile
        var lastForegroundServiceType: Int = 0
            private set

        /** Outcome of the most recent cycle, for QA/logging. */
        @Volatile
        var lastCycleResult: CycleResult? = null
            private set

        /** Incremented at the end of every cycle so tests can await completion. */
        val cycleCompletions = AtomicInteger(0)

        /** Start one cycle now (also used as the AlarmManager wake target). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RegulatorService::class.java))
        }

        /**
         * UI-invoked battery-optimization-exemption prompt. A periodic background
         * regulator needs Doze exemption for its exact alarms; this opens the
         * system request when not already exempt. Must be launched from a
         * user-facing context (a background service can't pop it).
         */
        fun requestBatteryExemption(context: Context) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.isIgnoringBatteryOptimizations(context.packageName)) return
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
