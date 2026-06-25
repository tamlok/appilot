package com.vnote.appilot.read.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager

/**
 * Foreground service of type `mediaProjection` that owns the session's screen
 * projection and serves on-demand single-frame captures.
 *
 * Android 14+ ordering: the FGS of type `mediaProjection` MUST be running before
 * [MediaProjectionManager.getMediaProjection]; so a capture command first calls
 * [startForeground], then (lazily, exactly once per session) obtains the
 * projection from the cached consent token and registers the mandatory
 * [MediaProjection.Callback].
 *
 * Why the projection AND its single VirtualDisplay are kept ALIVE between
 * captures: on Android 14+ the consent token from `createScreenCaptureIntent` is
 * SINGLE-USE (a second `getMediaProjection` yields an invalid projection) AND a
 * projection permits exactly ONE `createVirtualDisplay` for its lifetime (a
 * second call throws "Don't take multiple captures..."). So to honour the
 * "consent once, no re-prompt, reuse across captures" contract, the projection +
 * [ScreenCaptureEngine] (which owns the one VirtualDisplay/ImageReader) are
 * created once and reused; successive frames are pulled from the live reader.
 * They are torn down together - no leak - on [ACTION_RELEASE] (or [onDestroy]).
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var engine: ScreenCaptureEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELEASE) {
            releaseAll()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val token = MediaProjectionSession.token()
        if (token == null) {
            CaptureResultBus.fail(IllegalStateException("no cached consent token"))
            releaseAll()
            return START_NOT_STICKY
        }

        Thread({ runCapture(token) }, "screen-capture-driver").start()
        return START_NOT_STICKY
    }

    private fun runCapture(token: MediaProjectionSession.Token) {
        try {
            val activeEngine = synchronized(lock) {
                engine ?: createEngine(obtainProjectionLocked(token)).also { engine = it }
            }
            CaptureResultBus.deliver(activeEngine.captureFrame())
        } catch (t: Throwable) {
            // Hard failure: drop the (single-use) projection and consent so the
            // next attempt re-prompts instead of reusing a dead session.
            releaseAll()
            MediaProjectionSession.clear()
            CaptureResultBus.fail(t)
        }
    }

    private fun obtainProjectionLocked(token: MediaProjectionSession.Token): MediaProjection =
        projection ?: createProjection(token).also { projection = it }

    private fun createEngine(activeProjection: MediaProjection): ScreenCaptureEngine {
        val (width, height) = realDisplaySize()
        return ScreenCaptureEngine(width, height, resources.configuration.densityDpi)
            .also { it.start(activeProjection) }
    }

    private fun createProjection(token: MediaProjectionSession.Token): MediaProjection {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = try {
            manager.getMediaProjection(token.resultCode, token.data)
                ?: error("getMediaProjection returned null")
        } catch (revoked: SecurityException) {
            MediaProjectionSession.clear()
            throw revoked
        }
        // Mandatory callback (Android 14+) BEFORE any VirtualDisplay is created.
        created.registerCallback(object : MediaProjection.Callback() {}, mainHandler)
        return created
    }

    private fun releaseAll() {
        synchronized(lock) {
            engine?.release()
            engine = null
            projection?.stop()
            projection = null
        }
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            engine?.release()
            engine = null
            projection?.stop()
            projection = null
        }
        super.onDestroy()
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RELEASE = "com.vnote.appilot.read.projection.RELEASE"
        private const val NOTIFICATION_ID = 0x5C0E
        private const val CHANNEL_ID = "screen_capture"
        private const val CHANNEL_NAME = "Screen capture"
    }
}
