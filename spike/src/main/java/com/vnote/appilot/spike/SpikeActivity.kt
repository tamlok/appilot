package com.vnote.appilot.spike

// THROWAWAY spike — Wave 0 launch-mechanism validation. Deleted in Task 2.
// Each button fires one candidate cross-app launch intent from a NORMAL app
// context and logs the outcome (success / SecurityException / ActivityNotFound /
// other) under logcat tag "SPIKE". Wrong-page is judged externally via
// `dumpsys activity activities | findstr mResumedActivity` + screencap.

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val TAG = "SPIKE"

class SpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var last by remember { mutableStateOf("idle") }
                    val onResult: (String) -> Unit = { last = it }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Cross-app launch spike", style = MaterialTheme.typography.titleMedium)
                        Text("last: $last", style = MaterialTheme.typography.bodySmall)

                        Button(onClick = { onResult(fireTuyaExplicit()) }) {
                            Text("1. Tuya · explicit-component")
                        }
                        Button(onClick = { onResult(fireTuyaPackageLaunch()) }) {
                            Text("2. Tuya · getLaunchIntentForPackage")
                        }
                        Button(onClick = { onResult(fireTuyaDeepLink()) }) {
                            Text("3. Tuya · raw VIEW deep-link")
                        }
                        Button(onClick = { onResult(fireHaierExplicit()) }) {
                            Text("4. Haier · explicit-component")
                        }
                        Button(onClick = { onResult(fireHaierPackageLaunch()) }) {
                            Text("5. Haier · getLaunchIntentForPackage")
                        }
                        Button(onClick = { onResult(fireHaierDeepLink()) }) {
                            Text("6. Haier · raw VIEW deep-link")
                        }
                    }
                }
            }
        }
    }

    // ---- Tuya -------------------------------------------------------------

    // Captured pinned-shortcut intent (full extras recovered from `dumpsys shortcut`).
    private fun fireTuyaExplicit(): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(
                "com.tuya.smartiot",
                "com.thingclips.smart.hometab.activity.shortcut",
            )
            setPackage("com.tuya.smartiot")
            putExtra("url", "thingSmart://pinned_shortcut")
            putExtra("shortcut_dev_id", "6cd07555416b69c0e3zu8u")
            putExtra("type", 1)
            putExtra("shortcut_home_id", "205535327")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fire("tuya-explicit", intent)
    }

    private fun fireTuyaPackageLaunch(): String {
        val intent = packageManager.getLaunchIntentForPackage("com.tuya.smartiot")
        if (intent == null) {
            Log.w(TAG, "[tuya-package] result=not-applicable reason=getLaunchIntentForPackage-null")
            return "tuya-package: not-applicable (null launch intent)"
        }
        return fire("tuya-package", intent)
    }

    private fun fireTuyaDeepLink(): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("thingSmart://pinned_shortcut")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fire("tuya-deeplink", intent)
    }

    // ---- Haier ------------------------------------------------------------

    // Captured pinned-shortcut intent. NOTE: the data URI path is redacted by
    // Uri.toSafeString() in `dumpsys shortcut` (shows http://uplus.haier.com/...)
    // and is unrecoverable on this production/Play emulator image (no root,
    // shortcut XML read-denied). We fire the recoverable host-only URI against
    // the exact captured component to observe where it lands.
    private fun fireHaierExplicit(): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(
                "com.haier.uhome.uplus",
                "com.haier.uhome.uplus.launcher.ShortCutLauncherActivity",
            )
            data = Uri.parse("http://uplus.haier.com/")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fire("haier-explicit", intent)
    }

    private fun fireHaierPackageLaunch(): String {
        val intent = packageManager.getLaunchIntentForPackage("com.haier.uhome.uplus")
        if (intent == null) {
            Log.w(TAG, "[haier-package] result=not-applicable reason=getLaunchIntentForPackage-null")
            return "haier-package: not-applicable (null launch intent)"
        }
        return fire("haier-package", intent)
    }

    private fun fireHaierDeepLink(): String {
        // Haier registers UpSchemeLaunchActivity for uplus:// (authorities data/target/jump).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("uplus://data")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fire("haier-deeplink", intent)
    }

    // ---- shared firing + outcome logging ----------------------------------

    private fun fire(label: String, intent: Intent): String {
        Log.i(TAG, "[$label] firing uri=${intent.toUri(Intent.URI_INTENT_SCHEME)}")
        return try {
            startActivity(intent)
            Log.i(TAG, "[$label] result=SUCCESS (startActivity returned)")
            "$label: SUCCESS"
        } catch (e: SecurityException) {
            Log.e(TAG, "[$label] result=SecurityException msg=${e.message}")
            "$label: SecurityException — ${e.message}"
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "[$label] result=ActivityNotFound msg=${e.message}")
            "$label: ActivityNotFound — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "[$label] result=${e.javaClass.simpleName} msg=${e.message}")
            "$label: ${e.javaClass.simpleName} — ${e.message}"
        }
    }
}
