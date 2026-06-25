package com.vnote.appilot.ui.calibration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vnote.appilot.ui.theme.AppilotTheme

/**
 * Hosts the [CalibrationFlow]. The [viewModel] is a plain state holder exposed
 * for instrumented tests, which read [CalibrationViewModel.config] directly off
 * the activity to assert that the persisted config equals what was entered.
 */
class CalibrationActivity : ComponentActivity() {

    lateinit var viewModel: CalibrationViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = CalibrationViewModel(applicationContext)
        enableEdgeToEdge()
        setContent {
            AppilotTheme {
                CalibrationFlow(viewModel = viewModel)
            }
        }
    }
}
