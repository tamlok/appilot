package com.vnote.appilot.read.projection

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent, no-UI activity that drives the system MediaProjection consent
 * dialog via the Activity Result API and caches the grant in
 * [MediaProjectionSession] for the rest of the session.
 *
 * Flow: on create it launches [MediaProjectionManager.createScreenCaptureIntent];
 * on `RESULT_OK` it stores `resultCode + data` in the session token and signals
 * [ConsentResultBus]; then it finishes immediately. Subsequent captures reuse the
 * cached token without showing this dialog again.
 *
 * It deliberately carries no layout/theme content - it exists only to host the
 * `startActivityForResult` contract, which requires an Activity context.
 */
class ProjectionConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionSession.store(result.resultCode, data)
            ConsentResultBus.deliver(true)
        } else {
            ConsentResultBus.deliver(false)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MediaProjectionSession.hasConsent()) {
            ConsentResultBus.deliver(true)
            finish()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
