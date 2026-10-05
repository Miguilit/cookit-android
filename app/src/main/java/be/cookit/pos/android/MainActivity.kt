package be.cookit.pos.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.cookit.pos.android.data.PushNavigationRequest
import be.cookit.pos.android.service.CookitNotificationChannels
import be.cookit.pos.android.service.FiscalAgentServiceController
import be.cookit.pos.android.ui.CookitApp
import be.cookit.pos.android.ui.CookitPosViewModel
import be.cookit.pos.android.ui.theme.CookitTheme

class MainActivity : ComponentActivity() {
    private val vm: CookitPosViewModel by viewModels()

    private var pendingPushNavigation
        by mutableStateOf<PushNavigationRequest?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Durable in-app notifications remain available if denied.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingPushNavigation =
            PushNavigationRequest.fromIntent(intent)

        CookitNotificationChannels.ensureCreated(this)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        // C4: once an authorized operator enabled Auto, reopening Cookit resumes the device-level
        // foreground Fiscal Agent even before the POS UI finishes its cloud bootstrap.
        FiscalAgentServiceController.resumeIfEnabled(this)

        // Dark status bar: date/time/Wi-Fi/battery remain readable on restaurant tablets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.rgb(9, 11, 14)),
            navigationBarStyle = SystemBarStyle.dark(Color.rgb(9, 11, 14))
        )

        setContent {
            CookitTheme {
                CookitApp(
                    vm = vm,
                    pushNavigation =
                        pendingPushNavigation,
                    onPushNavigationConsumed = {
                        pendingPushNavigation = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        pendingPushNavigation =
            PushNavigationRequest.fromIntent(intent)
    }
}
