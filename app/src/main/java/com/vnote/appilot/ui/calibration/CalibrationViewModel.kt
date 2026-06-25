package com.vnote.appilot.ui.calibration

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vnote.appilot.core.model.AcAction
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.model.TapTarget
import com.vnote.appilot.core.model.TempBand
import com.vnote.appilot.core.store.ConfigStore
import com.vnote.appilot.core.store.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Glyph keys persisted to [TemplateStore], matching the read layer's
 * `TemplateLoader` mapping (`"0".."9"`, `"dot" -> '.'`, `"minus" -> '-'`).
 */
val GLYPH_KEYS: List<String> =
    listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "dot", "minus")

/**
 * Plain (non-androidx) state holder for the calibration flow. The single
 * [config] snapshot is BOTH what the UI shows and exactly what [save] persists,
 * so a save→reload round-trip yields an equal [RegulatorConfig].
 *
 * Construction defaults wire the real stores; tests inject fakes / read [config]
 * straight off the hosting activity.
 */
class CalibrationViewModel(
    private val appContext: Context,
    private val configStore: ConfigStore = ConfigStore(appContext),
    private val templateStore: TemplateStore = TemplateStore(appContext),
) {
    /** The in-progress config; starts from the store's sensible DEFAULT. */
    var config by mutableStateOf(ConfigStore.DEFAULT)
        private set

    /** Last captured screenshot used to mark regions/targets, or null. */
    var screenshot by mutableStateOf<Bitmap?>(null)
        private set

    /** Human-readable status surfaced by the flow's top bar. */
    var status by mutableStateOf("")
        private set

    fun setSource(target: LaunchTarget) {
        config = config.copy(source = target)
    }

    fun setActuator(target: LaunchTarget) {
        config = config.copy(actuator = target)
    }

    fun setReadRegion(rect: RatioRect) {
        config = config.copy(readRegion = ReadRegion(rect))
    }

    fun addTapTarget(rect: RatioRect, action: AcAction) {
        config = config.copy(tapTargets = config.tapTargets + TapTarget(rect, action))
    }

    fun clearTapTargets() {
        config = config.copy(tapTargets = emptyList())
    }

    fun setBand(lowC: Double, highC: Double) {
        if (lowC <= highC) config = config.copy(band = TempBand(lowC, highC))
    }

    fun setStep(step: Double) {
        if (step > 0.0) config = config.copy(step = step)
    }

    fun setInterval(minutes: Int) {
        if (minutes > 0) config = config.copy(intervalMinutes = minutes)
    }

    /** Grab one frame off the main thread; updates [screenshot] + [status]. */
    suspend fun captureScreenshot() {
        val frame = withContext(Dispatchers.IO) { CalibrationDeps.capture(appContext) }
        screenshot = frame
        status = if (frame == null) "Capture failed — grant screen consent" else "Screenshot ready"
    }

    /**
     * Crop the current screenshot into a 4×3 grid of glyph cells (one per
     * [GLYPH_KEYS] entry) and persist each as a PNG. A real user would tune the
     * grid; the grid keeps the cropping deterministic and demonstrates the
     * template-capture wiring without OCR.
     */
    suspend fun captureGlyphTemplates() {
        val source = screenshot ?: return
        withContext(Dispatchers.IO) {
            val cols = 4
            val rows = 3
            val cellW = source.width / cols
            val cellH = source.height / rows
            if (cellW <= 0 || cellH <= 0) return@withContext
            GLYPH_KEYS.forEachIndexed { index, key ->
                val x = (index % cols) * cellW
                val y = (index / cols) * cellH
                val cell = Bitmap.createBitmap(source, x, y, cellW, cellH)
                templateStore.saveTemplate(key, cell.toPng())
                cell.recycle()
            }
        }
        status = "Captured ${GLYPH_KEYS.size} glyph templates"
    }

    /** Persist the exact [config] snapshot the UI is showing. */
    suspend fun save(): Boolean {
        configStore.save(config)
        status = "Saved"
        return true
    }

    /** Restore a previously saved config (reopening the flow shows it). */
    suspend fun reload() {
        configStore.load()?.let { config = it }
    }
}

private fun Bitmap.toPng(): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
