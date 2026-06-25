package com.vnote.appilot.read.projection

import android.content.Intent

/**
 * Session-scoped consent holder for MediaProjection.
 *
 * The user grants screen-capture consent ONCE per app session via the system
 * dialog (see [ProjectionConsentActivity]). The result - a `resultCode` plus the
 * returned `data` [Intent] - is cached here so that repeated single-frame
 * captures within the same session do NOT re-prompt. The cache lives only in
 * process memory (a `companion object` singleton): it is intentionally NOT
 * persisted, because the system consent token is bound to the process and must
 * not survive a cold restart.
 *
 * Thread-safety: reads/writes are guarded by the monitor lock so the consent
 * Activity (UI thread) and the capture service (a worker thread) see a
 * consistent token.
 *
 * Wave 6 note: the orchestration service obtains consent once (interactively, or
 * via a pre-granted token in tests) and thereafter calls
 * [ScreenCapture.captureFrame] repeatedly; each call reuses this cached token.
 */
class MediaProjectionSession private constructor() {

    /** Immutable snapshot of a granted consent token. */
    data class Token(val resultCode: Int, val data: Intent)

    companion object {
        @Volatile
        private var cached: Token? = null
        private val lock = Any()

        /**
         * Cache the consent result for the rest of the session.
         *
         * The [data] intent is cloned so later external mutation cannot corrupt
         * the stored token.
         */
        fun store(resultCode: Int, data: Intent) {
            synchronized(lock) {
                cached = Token(resultCode, Intent(data))
            }
        }

        /** The cached token, or `null` if consent has not been granted yet. */
        fun token(): Token? = synchronized(lock) {
            cached?.let { Token(it.resultCode, Intent(it.data)) }
        }

        /** `true` once consent has been granted and cached this session. */
        fun hasConsent(): Boolean = synchronized(lock) { cached != null }

        /**
         * Forget the cached consent (e.g. the projection was revoked via the
         * mandatory [android.media.MediaProjection.Callback], or a test wants a
         * clean slate). The next capture must obtain consent again.
         */
        fun clear() {
            synchronized(lock) { cached = null }
        }
    }
}
