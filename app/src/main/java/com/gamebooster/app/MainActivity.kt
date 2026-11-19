package com.gamebooster.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamebooster.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val engine get() = (application as BoosterApp).engine

    // Probing and live stats pause while the UI is hidden (battery); the liveness check keeps running.
    override fun onStart() { super.onStart(); engine.setUiVisible(true) }
    override fun onStop() { super.onStop(); engine.setUiVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BoosterTheme {
                val st by vm.state.collectAsStateWithLifecycle()
                val consent by vm.needConsent.collectAsStateWithLifecycle()
                val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    vm.onConsentResult(it.resultCode == Activity.RESULT_OK)
                }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(consent) { consent?.let { vpnLauncher.launch(it) } }
                LaunchedEffect(Unit) {
                    // Denial is fine: the foreground service still runs, only its notification is hidden.
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (!st.signedIn) LoginScreen(st, vm) else MainScreen(st, vm)
            }
        }
    }
}
