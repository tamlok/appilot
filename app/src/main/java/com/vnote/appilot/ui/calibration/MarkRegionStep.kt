package com.vnote.appilot.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vnote.appilot.core.model.RatioRect
import kotlinx.coroutines.launch

/** Format a [RatioRect] compactly for on-screen status. */
fun formatRect(rect: RatioRect): String = "L%.2f T%.2f R%.2f B%.2f".format(
    rect.left.value, rect.top.value, rect.right.value, rect.bottom.value,
)

/**
 * Step (b): capture a screenshot, drag the temperature [RatioRect] over it, and
 * capture the 0-9 (+dot/minus) glyph templates. The drag stores a resolution-
 * independent rect via [DragRectOverlay]; glyph cells persist through the store.
 */
@Composable
fun MarkRegionStep(viewModel: CalibrationViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Mark temperature region", style = MaterialTheme.typography.titleLarge)
        Text("Region: ${formatRect(viewModel.config.readRegion.rect)}", Modifier.testTag("regionLabel"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { viewModel.captureScreenshot() } },
                modifier = Modifier.testTag("captureScreenshot"),
            ) { Text("Capture screen") }
            Button(
                onClick = { scope.launch { viewModel.captureGlyphTemplates() } },
                modifier = Modifier.testTag("captureGlyphs"),
            ) { Text("Capture 0-9") }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            val bitmap = viewModel.screenshot
            if (bitmap != null) {
                DragRectOverlay(
                    bitmap = bitmap,
                    rects = listOf(viewModel.config.readRegion.rect),
                    onRectDrawn = viewModel::setReadRegion,
                    testTag = "regionOverlay",
                )
            } else {
                Text("Capture a screenshot, then drag over the temperature digits.")
            }
        }
    }
}
