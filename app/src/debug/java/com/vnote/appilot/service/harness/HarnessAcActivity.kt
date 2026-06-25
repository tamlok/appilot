package com.vnote.appilot.service.harness

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import com.vnote.appilot.service.ForegroundRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only QA harness standing in for a vendor AC-remote screen.
 *
 * A single full-bleed [Button] whose REAL `onClick` handler bumps a
 * process-scoped [clicks] counter. The orchestration cycle test asserts that one
 * full launch->read->decide->tap cycle drives [clicks] up by EXACTLY the
 * configured `step` — proving the actuate layer landed real coordinate taps on a
 * live widget, NOT merely that the decision returned DOWN (the
 * misleading_success trap).
 *
 * Readiness: reports its class name to [ForegroundRegistry] on window focus so
 * the bounded readiness poll waits for THIS screen (distinct from the source,
 * which shares the package) before tapping.
 *
 * Lives in `src/debug/`, separate from the actuate-layer `TapHarnessActivity`
 * (which other waves' tests depend on), so neither is disturbed.
 */
class HarnessAcActivity : Activity() {

    private val token = HarnessAcActivity::class.java.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "AC"
            setOnClickListener { text = "AC ${clicks.incrementAndGet()}" }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    button,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ).apply { gravity = Gravity.CENTER },
                )
            },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ForegroundRegistry.enter(token) else ForegroundRegistry.leave(token)
    }

    override fun onPause() {
        ForegroundRegistry.leave(token)
        super.onPause()
    }

    companion object {
        /** Number of real button clicks observed this process. */
        val clicks = AtomicInteger(0)

        fun resetClicks() = clicks.set(0)
    }
}
