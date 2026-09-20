package be.cookit.pos.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import be.cookit.pos.android.service.FiscalAgentServiceController
import be.cookit.pos.android.ui.CookitApp
import be.cookit.pos.android.ui.theme.CookitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // C4: once an authorized operator enabled Auto, reopening Cookit resumes the device-level
        // foreground Fiscal Agent even before the POS UI finishes its cloud bootstrap.
        FiscalAgentServiceController.resumeIfEnabled(this)

        // Dark status bar: date/time/Wi-Fi/battery remain readable on restaurant tablets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.rgb(20, 31, 24)),
            navigationBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.rgb(20, 31, 24)
            )
        )

        setContent {
            CookitTheme {
                CookitApp()
            }
        }
    }
}
