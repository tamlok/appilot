package com.vnote.appilot.actuate.harness

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only QA harness for the actuate layer.
 *
 * A single full-bleed [Button] whose REAL `onClick` handler bumps a
 * process-scoped [clicks] counter. The actuate instrumented tests resolve a
 * [com.vnote.appilot.core.model.RatioRect] over this button and assert [clicks]
 * increments — proving `dispatchGesture` landed an actual tap on a live widget,
 * NOT merely that the gesture API reported success (the misleading_success
 * trap). Lives in `src/debug/` so it never reaches the release surface.
 */
class TapHarnessActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "TAP"
            setOnClickListener {
                text = "TAPPED ${clicks.incrementAndGet()}"
            }
        }
        val root = FrameLayout(this).apply {
            addView(
                button,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply { gravity = Gravity.CENTER },
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    companion object {
        /** Number of real button clicks observed this process. */
        val clicks = AtomicInteger(0)

        /** True while the harness is foreground-resumed, so tests tap only when it is on top. */
        @Volatile
        var resumed = false

        fun resetClicks() = clicks.set(0)
    }
}
