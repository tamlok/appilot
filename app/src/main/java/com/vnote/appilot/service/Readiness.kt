package com.vnote.appilot.service

import android.content.Intent
import android.os.SystemClock
import com.vnote.appilot.core.model.LaunchTarget

/**
 * Bounded-timeout readiness polling for the cycle's launch steps.
 *
 * ## Why polling (never a fixed sleep)
 * After [com.vnote.appilot.launch.Launcher] fires an Intent the target window
 * appears asynchronously and at a device/load-dependent moment. A fixed
 * `Thread.sleep` either wastes time (too long) or screenshots/taps the wrong
 * screen (too short). Instead we poll an explicit readiness predicate — "is the
 * target now the foreground/top window?" — until it is true or a hard deadline
 * elapses.
 *
 * ## Timeout + retry policy (documented contract)
 *  - Per attempt: poll [isReady] every [pollMs] (default 200ms) until it returns
 *    true or [timeoutMs] (default 8000ms) elapses. Returns true on success,
 *    false on timeout. NEVER blocks unbounded.
 *  - The caller ([CycleOrchestrator]) wraps this in a small RE-LAUNCH retry loop:
 *    on a timeout it re-fires the launch Intent and polls again, up to a bounded
 *    number of attempts, then aborts the cycle (no read / no tap) and lets the
 *    scheduler try again next interval. This tolerates a dropped/!cold launch
 *    without ever hanging.
 *
 * Pure-ish: the clock + sleep are injected so unit tests need no real waiting.
 */
object Readiness {

    const val DEFAULT_TIMEOUT_MS = 8_000L
    const val DEFAULT_POLL_MS = 200L

    /** Poll [isReady] until true or [timeoutMs] elapses. */
    fun awaitReady(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
        now: () -> Long = SystemClock::uptimeMillis,
        sleep: (Long) -> Unit = SystemClock::sleep,
        isReady: () -> Boolean,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (isReady()) return true
            sleep(pollMs)
        }
        return isReady()
    }

    /**
     * The foreground token to wait for when [target] is launched, used against
     * [ForegroundRegistry.matches].
     *
     *  - [LaunchTarget.CapturedShortcut] — the explicit component's class name
     *    (the precise activity), falling back to its `fallbackPackage` when the
     *    captured intent carries no component.
     *  - [LaunchTarget.App] — the package name.
     *  - [LaunchTarget.DeepLink] — unknown until resolved, so `null` (readiness
     *    for raw deep links is an F1 follow-up; the gate path uses components).
     */
    fun tokenOf(target: LaunchTarget): String? = when (target) {
        is LaunchTarget.App -> target.packageName.ifBlank { null }
        is LaunchTarget.DeepLink -> null
        is LaunchTarget.CapturedShortcut -> componentClassOf(target.intentUri)
            ?: target.fallbackPackage.ifBlank { null }
    }

    private fun componentClassOf(intentUri: String): String? = runCatching {
        Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).component?.className
    }.getOrNull()
}
