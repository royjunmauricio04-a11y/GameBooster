package com.gamebooster.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamebooster.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BoosterTheme {
                val st by vm.state.collectAsStateWithLifecycle()
                val consent by vm.needConsent.collectAsStateWithLifecycle()
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    vm.onConsentResult(it.resultCode == Activity.RESULT_OK)
                }
                LaunchedEffect(consent) { consent?.let { launcher.launch(it) } }
                if (!st.signedIn) LoginScreen(st, vm) else Dashboard(st, vm)
            }
        }
    }
}
