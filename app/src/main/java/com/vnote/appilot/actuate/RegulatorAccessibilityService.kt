package com.vnote.appilot.actuate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.vnote.appilot.core.model.GestureStep
import com.vnote.appilot.core.model.LaunchTarget
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

    @Volatile
    private var recorder: MacroRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rec = recorder ?: return
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                rec.onScrolled(toRightPage = event.scrollDeltaX >= 0)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val (width, height) = realDisplaySize()
                if (width > 0 && height > 0) {
                    rec.onClicked(
                        event.packageName?.toString().orEmpty(),
                        rect.exactCenterX().toDouble() / width,
                        rect.exactCenterY().toDouble() / height,
                    )
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                rec.onWindowState(event.packageName?.toString().orEmpty(), packageName)
        }
    }

    /**
     * Arms a [MacroRecorder] and jumps to the launcher so the user can
     * demonstrate opening their device shortcut. [onComplete] fires once on the
     * main thread when the demonstration lands a non-launcher app.
     */
    fun startRecording(onComplete: (LaunchTarget.LauncherGesture) -> Unit) {
        recorder = MacroRecorder { gesture ->
            mainHandler.post {
                recorder = null
                onComplete(gesture)
            }
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun stopRecording() {
        recorder = null
    }

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

    /**
     * Replays a desktop-open [steps] macro sequentially: home, swipe(s), tap,
     * each followed by a settle delay so the launcher page animation and the
     * target launch can complete before the next step. Calls [onDone] with
     * `false` on the first failed step (so callers never hang), `true` when all
     * steps complete.
     */
    fun playMacro(steps: List<GestureStep>, onDone: (Boolean) -> Unit) {
        val (width, height) = realDisplaySize()
        fun px(x: Double, y: Double): Pair<Float, Float> =
            (x * width).toFloat().coerceIn(0f, (width - 1).toFloat()) to
                (y * height).toFloat().coerceIn(0f, (height - 1).toFloat())

        fun run(i: Int) {
            if (i >= steps.size) { onDone(true); return }
            when (val step = steps[i]) {
                is GestureStep.Home -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                    if (!ok) { onDone(false); return }
                    mainHandler.postDelayed({ run(i + 1) }, HOME_SETTLE_MS)
                }
                is GestureStep.Swipe -> {
                    val (fx, fy) = px(step.from.x.value, step.from.y.value)
                    val (tx, ty) = px(step.to.x.value, step.to.y.value)
                    stroke(fx, fy, tx, ty, step.durationMs) { ok ->
                        if (!ok) onDone(false) else mainHandler.postDelayed({ run(i + 1) }, SWIPE_SETTLE_MS)
                    }
                }
                is GestureStep.Tap -> {
                    val (x, y) = px(step.spot.x.value, step.spot.y.value)
                    stroke(x, y, x + 1f, y + 1f, step.durationMs) { ok ->
                        if (!ok) onDone(false) else mainHandler.postDelayed({ run(i + 1) }, TAP_SETTLE_MS)
                    }
                }
            }
        }
        run(0)
    }

    private fun stroke(fx: Float, fy: Float, tx: Float, ty: Float, durationMs: Long, onDone: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(fx, fy); lineTo(tx, ty) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }
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

        private const val HOME_SETTLE_MS = 700L
        private const val SWIPE_SETTLE_MS = 600L
        private const val TAP_SETTLE_MS = 300L

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
