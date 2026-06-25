package com.vnote.appilot.read.projection

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stateful, session-scoped screen-capture engine.
 *
 * On Android 14+ a [MediaProjection] permits exactly ONE
 * [MediaProjection.createVirtualDisplay] call for its whole lifetime - a second
 * call (even after releasing the first display) throws "Don't take multiple
 * captures...". So to serve repeated single-frame captures within one session we
 * keep a SINGLE [VirtualDisplay] + [ImageReader] alive: [start] wires them once,
 * a listener keeps the most-recent frame decoded into [latest], and each
 * [captureFrame] returns a fresh copy of it. [release] tears the whole thing down
 * at session end (no leak: VirtualDisplay/ImageReader/worker thread all freed).
 *
 * Frames are pulled from the reader honouring `rowStride`/`pixelStride` padding.
 */
class ScreenCaptureEngine(
    private val widthPx: Int,
    private val heightPx: Int,
    private val densityDpi: Int,
    private val firstFrameTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "display size must be positive" }
        require(densityDpi > 0) { "densityDpi must be positive" }
    }

    private val firstFrame = CountDownLatch(1)
    private val frameLock = Any()
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var latest: Bitmap? = null

    /** Wire the projection to a mirroring VirtualDisplay -> ImageReader (once). */
    fun start(projection: MediaProjection) {
        val worker = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(worker.looper)
        val imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader.setOnImageAvailableListener({ onFrame(it) }, handler)
        thread = worker
        reader = imageReader
        virtualDisplay = projection.createVirtualDisplay(
            "screen-capture",
            widthPx,
            heightPx,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            /* callback = */ null,
            handler,
        )
    }

    private fun onFrame(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        val bitmap = image.use { toBitmap(it) }
        synchronized(frameLock) {
            latest?.recycle()
            latest = bitmap
        }
        firstFrame.countDown()
    }

    /**
     * Return a copy of the most recently mirrored frame. Blocks the calling
     * thread (never the main thread) until the first frame arrives or
     * [firstFrameTimeoutMs] elapses.
     *
     * @throws IllegalStateException if no frame is produced within the timeout.
     */
    fun captureFrame(): Bitmap {
        check(firstFrame.await(firstFrameTimeoutMs, TimeUnit.MILLISECONDS)) {
            "no frame within ${firstFrameTimeoutMs}ms"
        }
        synchronized(frameLock) {
            return checkNotNull(latest) { "no frame available" }.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        synchronized(frameLock) {
            latest?.recycle()
            latest = null
        }
    }

    /**
     * Copy an RGBA_8888 [Image] plane into an ARGB_8888 [Bitmap], honouring the
     * reader's `rowStride`/`pixelStride`. The plane row may be wider than the
     * display (right-edge padding), so we allocate a padded bitmap then crop back
     * to the true display width.
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * widthPx

        val paddedWidth = widthPx + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, heightPx, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)

        if (paddedWidth == widthPx) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, widthPx, heightPx)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private companion object {
        const val MAX_IMAGES = 2
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
