package com.vnote.appilot.service

import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.decide.Regulator
import com.vnote.appilot.decide.RegulatorAction
import com.vnote.appilot.decide.toAcAction
import com.vnote.appilot.launch.Launcher

/** Observable outcome of one regulation cycle (drives logging + the next `lastAction`). */
sealed interface CycleResult {
    /** A nudge was actuated: [taps] taps landed for [action]. */
    data class Acted(val action: RegulatorAction, val taps: Int) : CycleResult

    /** Read succeeded, inside the band — no tap. */
    data class Held(val temp: Double) : CycleResult

    /** The source screen never became foreground within the readiness budget. */
    data object SourceUnavailable : CycleResult

    /** The source was ready but the temperature could not be read this cycle. */
    data object ReadFailed : CycleResult

    /** Decided to nudge, but the actuator screen never became foreground. */
    data object ActuatorUnavailable : CycleResult
}

/**
 * Runs ONE regulation cycle as pure glue over the frozen layers:
 *
 * ```
 * launch source -> await source foreground -> read temp -> decide
 *   -> if DOWN/UP: launch actuator -> await actuator foreground -> tap N=step
 * ```
 *
 * Every Android-framework dependency arrives through an injected seam, so the
 * orchestration logic is testable and holds NO vendor knowledge:
 *  - [launcher] replays a [LaunchTarget] (Wave 2 ranking + secure fallback);
 *  - [foregroundCheck] reports whether a target is the current top window
 *    ([ForegroundRegistry]-backed), polled by [Readiness] — never a fixed sleep;
 *  - [reader] performs the projection + template read (or returns null);
 *  - [tapper] maps an [AcAction] to N coordinate taps via the actuate layer.
 *
 * Readiness retry policy: each launch step fires the Intent then polls up to
 * [readyTimeoutMs]; on timeout it RE-LAUNCHES, up to [launchAttempts] total,
 * before aborting the cycle (no read / no tap). Bounded everywhere — no hang.
 */
class CycleOrchestrator(
    private val config: RegulatorConfig,
    private val launcher: Launcher,
    private val foregroundCheck: (LaunchTarget) -> Boolean,
    private val reader: TemperatureReader,
    private val tapper: (AcAction, Int) -> Int,
    private val readyTimeoutMs: Long = Readiness.DEFAULT_TIMEOUT_MS,
    private val pollMs: Long = Readiness.DEFAULT_POLL_MS,
    private val launchAttempts: Int = DEFAULT_LAUNCH_ATTEMPTS,
    private val sleep: (Long) -> Unit = { android.os.SystemClock.sleep(it) },
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {

    /** Number of taps to issue per nudge: one press per `step` unit (>= 1). */
    private val tapsPerNudge: Int = config.step.toInt().coerceAtLeast(1)

    fun runCycle(lastAction: RegulatorAction): CycleResult {
        if (!ensureForeground(config.source)) return CycleResult.SourceUnavailable

        val temp = reader.read() ?: return CycleResult.ReadFailed
        val action = Regulator.decide(temp, config, lastAction)
        val acAction = action.toAcAction() ?: return CycleResult.Held(temp)

        if (!ensureForeground(config.actuator)) return CycleResult.ActuatorUnavailable

        val taps = tapper(acAction, tapsPerNudge)
        return CycleResult.Acted(action, taps)
    }

    /**
     * Launch [target] and wait for it to become the foreground window, retrying
     * the launch on timeout up to [launchAttempts] times. Returns false if it
     * never came forward.
     */
    private fun ensureForeground(target: LaunchTarget): Boolean {
        repeat(launchAttempts) {
            launcher.launch(target)
            val ready = Readiness.awaitReady(
                timeoutMs = readyTimeoutMs,
                pollMs = pollMs,
                now = now,
                sleep = sleep,
            ) { foregroundCheck(target) }
            if (ready) return true
        }
        return false
    }

    private companion object {
        const val DEFAULT_LAUNCH_ATTEMPTS = 2
    }
}
