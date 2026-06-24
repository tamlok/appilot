package com.vnote.appilot.launch

import android.content.Intent
import com.vnote.appilot.core.model.LaunchTarget

/**
 * Lossless (de)serialization between an Android [Intent] and the stored string
 * form held by [LaunchTarget.CapturedShortcut].
 *
 * The Wave 0 spike proved that a captured pinned-shortcut intent — component,
 * action, data URI and every typed extra — must replay byte-for-byte to land on
 * the vendor's exact deep page. `Intent.toUri(URI_INTENT_SCHEME)` /
 * `Intent.parseUri(...)` is the canonical, reversible encoding the framework
 * itself uses, so it preserves typed extras (`S.`/`i.`/`l.`/`B.` prefixes),
 * the explicit component, the action and the data URI.
 *
 * No vendor-specific knowledge lives here: it is a pure structural codec.
 */
object LaunchTargetResolver {

    /** Encodes [intent] to its reversible `URI_INTENT_SCHEME` string form. */
    fun serialize(intent: Intent): String = intent.toUri(Intent.URI_INTENT_SCHEME)

    /** Parses a [serialize]d string back into an equivalent [Intent]. */
    fun deserialize(uri: String): Intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)

    /**
     * Maps a captured framework [intent] to a storable
     * [LaunchTarget.CapturedShortcut], recording [fallbackPackage] for the
     * secure-fallback path used by the launcher.
     */
    fun toCapturedShortcut(
        intent: Intent,
        fallbackPackage: String,
    ): LaunchTarget.CapturedShortcut = LaunchTarget.CapturedShortcut(
        intentUri = serialize(intent),
        fallbackPackage = fallbackPackage,
    )

    /** Rebuilds the replayable [Intent] from a stored [shortcut]. */
    fun toIntent(shortcut: LaunchTarget.CapturedShortcut): Intent =
        deserialize(shortcut.intentUri)
}
