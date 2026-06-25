package com.vnote.appilot.actuate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.vnote.appilot.core.model.RatioRect

/**
 * Coordinate-only gesture tapper for the actuate layer.
 *
 * The whole point (Wave 0 finding): vendor AC buttons are *drawn* inside a
 * WebView and expose no reliable accessibility nodes, so taps MUST be
 * coordinate-based. [tap] resolves a stored [RatioRect] against the REAL display
 * bounds at tap time and fires a single `dispatchGesture` stroke at its center —
 * making it resolution- and orientation-independent, and entirely generic (no
 * per-vendor node IDs, strings, or coordinates).
 *
 * Callers (Wave 6 + instrumented tests) reach the live, system-bound instance via
 * the process-scoped [instance] handle, which is set in [onServiceConnected] and
 * cleared in [onUnbind] / [onDestroy].
 */
class RegulatorAccessibilityService : AccessibilityService(), Tapper {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    // No node inspection: this service taps by coordinate only.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun tap(target: RatioRect, onDone: (Boolean) -> Unit) {
        val (width, height) = realDisplaySize()
        // Resolve the ratio center against the live display, clamped on-screen.
        val x = (target.centerX.value * width).toFloat().coerceIn(0f, (width - 1).toFloat())
        val y = (target.centerY.value * height).toFloat().coerceIn(0f, (height - 1).toFloat())

        // A non-degenerate (>0 length) path so the system samples a real DOWN..UP
        // touch stream; a bare moveTo() point is dispatched but injects no
        // deliverable MotionEvent on some images.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }

        // dispatchGesture returns false if the service cannot currently dispatch;
        // surface that as a failed completion so callers never hang.
        if (!dispatchGesture(gesture, callback, mainHandler)) onDone(false)
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    companion object {
        /** Brief press; long enough to register as a click, short enough to feel instant. */
        private const val TAP_DURATION_MS = 60L

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * The live, system-bound service, or null when not connected. Process
         * scoped so Wave 6 (the orchestrator) and instrumented tests can invoke
         * [tap] on the real instance.
         */
        @Volatile
        var instance: RegulatorAccessibilityService? = null
            private set
    }
}
