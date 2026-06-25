package com.vnote.appilot.read.projection

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Public entry point of the Read layer's screen-capture pipeline.
 *
 * Two responsibilities:
 *  - [requestConsent]: obtain the one-per-session MediaProjection consent token
 *    (drives the system dialog via [ProjectionConsentActivity]); the token is
 *    cached in [MediaProjectionSession] and reused by every later capture.
 *  - [captureFrame]: grab a SINGLE frame of the current screen. It starts (or
 *    reuses) the `mediaProjection` foreground service ([ScreenCaptureService]) -
 *    required by Android 14+ ordering - which obtains the projection from the
 *    cached token, captures one frame via a per-capture VirtualDisplay, and keeps
 *    the projection alive for the next capture (the consent token is single-use
 *    on Android 14+, so it cannot be re-obtained per capture).
 *
 * Call [release] at the end of the session to stop the projection + foreground
 * service and forget the consent token.
 *
 * Designed to be callable from a plain [Context] (Activity OR Service), so Wave 6
 * orchestration can capture from its background cycle. The calls BLOCK with a
 * bounded timeout and MUST be invoked off the main thread (they wait on the
 * foreground service / consent Activity which run on the main thread).
 */
object ScreenCapture {

    const val DEFAULT_CONSENT_TIMEOUT_MS = 30_000L
    const val DEFAULT_CAPTURE_TIMEOUT_MS = 15_000L

    /**
     * Ensure a session consent token exists, prompting the user if needed.
     *
     * Fast-paths to `true` when consent is already cached (the reuse contract).
     * Otherwise launches [ProjectionConsentActivity] and blocks until the user
     * answers or [timeoutMs] elapses.
     *
     * @return `true` if consent is granted (now or previously), `false` on deny
     *   or timeout.
     */
    fun requestConsent(context: Context, timeoutMs: Long = DEFAULT_CONSENT_TIMEOUT_MS): Boolean {
        if (MediaProjectionSession.hasConsent()) return true
        val pending = ConsentResultBus.begin()
        val intent = Intent(context, ProjectionConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return pending.await(timeoutMs)
    }

    /**
     * Capture one frame of the current screen.
     *
     * @throws IllegalStateException if consent has not been granted, or if no
     *   frame is delivered within [timeoutMs].
     */
    fun captureFrame(context: Context, timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS): Bitmap {
        check(MediaProjectionSession.hasConsent()) {
            "no MediaProjection consent - call requestConsent() first"
        }
        val pending = CaptureResultBus.begin()
        val intent = Intent(context, ScreenCaptureService::class.java)
        context.startForegroundService(intent)
        return pending.await(timeoutMs)
    }

    /**
     * Tear the session down: stop the projection + foreground service and forget
     * the cached consent token. Safe to call when nothing is running.
     */
    fun release(context: Context) {
        MediaProjectionSession.clear()
        val intent = Intent(context, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_RELEASE)
        runCatching { context.startService(intent) }
    }
}

/**
 * Single-slot rendezvous between [ScreenCaptureService] (producer, worker thread)
 * and [ScreenCapture.captureFrame] (consumer). Only one capture runs at a time
 * (v1 = single source), so a one-slot bus is sufficient.
 */
internal object CaptureResultBus {
    private val slot = AtomicReference<Pending?>(null)

    fun begin(): Pending = Pending().also { slot.set(it) }

    /** Producer success path. */
    fun deliver(bitmap: Bitmap) {
        slot.getAndSet(null)?.complete(bitmap, null)
    }

    /** Producer failure path. */
    fun fail(error: Throwable) {
        slot.getAndSet(null)?.complete(null, error)
    }

    class Pending {
        private val latch = CountDownLatch(1)
        @Volatile private var bitmap: Bitmap? = null
        @Volatile private var error: Throwable? = null

        fun complete(bmp: Bitmap?, err: Throwable?) {
            bitmap = bmp
            error = err
            latch.countDown()
        }

        fun await(timeoutMs: Long): Bitmap {
            check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                "capture timed out after ${timeoutMs}ms"
            }
            error?.let { throw IllegalStateException("capture failed", it) }
            return checkNotNull(bitmap) { "capture produced no bitmap" }
        }
    }
}

/** Rendezvous for the consent dialog result; mirrors [CaptureResultBus]. */
internal object ConsentResultBus {
    private val slot = AtomicReference<Pending?>(null)

    fun begin(): Pending = Pending().also { slot.set(it) }

    fun deliver(granted: Boolean) {
        slot.getAndSet(null)?.complete(granted)
    }

    class Pending {
        private val latch = CountDownLatch(1)
        @Volatile private var granted = false

        fun complete(value: Boolean) {
            granted = value
            latch.countDown()
        }

        fun await(timeoutMs: Long): Boolean =
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && granted
    }
}
