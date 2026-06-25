package com.vnote.appilot.actuate

import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.TapTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Maps an [AcAction] to its configured [TapTarget] and issues a SEQUENCE of taps
 * through a [Tapper], spaced by [interTapDelayMs].
 *
 * Depends only on the [Tapper] seam (never the concrete service), so this mapping
 * is pure/JVM unit-testable. e.g. `TEMP_DOWN` with N steps issues exactly N taps
 * on the bound target; an action with no configured target is a no-op.
 *
 * [perform] BLOCKS: each tap waits for the [Tapper]'s completion callback before
 * the next is issued, so on-device gestures never overlap. Call it OFF the main
 * thread (the real tapper delivers its callback on the main looper).
 *
 * @param sleep injected so tests can record inter-tap delays without real waiting.
 */
class Actuator(
    private val tapper: Tapper,
    private val tapTargets: List<TapTarget>,
    private val interTapDelayMs: Long = DEFAULT_INTER_TAP_DELAY_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Issue [steps] taps on the target bound to [action], returning the number of
     * taps dispatched. An unmapped [action] or non-positive [steps] dispatches
     * nothing (returns 0). Each tap waits up to [perTapTimeoutMs] for completion.
     */
    fun perform(
        action: AcAction,
        steps: Int = 1,
        perTapTimeoutMs: Long = DEFAULT_PER_TAP_TIMEOUT_MS,
    ): Int {
        val rect = tapTargets.firstOrNull { it.action == action }?.rect ?: return 0
        if (steps <= 0) return 0

        for (i in 0 until steps) {
            if (i > 0) sleep(interTapDelayMs)
            val done = CountDownLatch(1)
            tapper.tap(rect) { done.countDown() }
            done.await(perTapTimeoutMs, TimeUnit.MILLISECONDS)
        }
        return steps
    }

    companion object {
        const val DEFAULT_INTER_TAP_DELAY_MS = 150L
        const val DEFAULT_PER_TAP_TIMEOUT_MS = 2_000L
    }
}
