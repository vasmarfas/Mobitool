package com.vasmarfas.card

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.ActivityHolder
import com.vasmarfas.card.core.AppContextHolder
import com.vasmarfas.card.core.PermissionBridge
import com.vasmarfas.card.core.reapplyPlatformLocale
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        PermissionBridge.onResult(result)
    }
    private var link by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppContextHolder.init(this)
        enableEdgeToEdge()
        // in DEFAULT cutout mode a window with hidden bars is letterboxed, the ruler would lose the top of the screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) link = intent.dataString

        setContent {
            App(link = link, onLinkHandled = { link = null })
        }
    }

    override fun onResume() {
        super.onResume()
        ActivityHolder.activity = this
        PermissionBridge.attach(permissionLauncher)
        reapplyPlatformLocale()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { link = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        reapplyPlatformLocale()
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        PermissionBridge.detach(permissionLauncher)
        if (ActivityHolder.activity === this) ActivityHolder.activity = null
        super.onDestroy()
    }
}
