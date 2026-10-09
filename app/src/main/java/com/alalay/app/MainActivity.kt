package com.alalay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.alalay.app.ui.AlalayRoot
import com.alalay.app.ui.AlalayTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlalayTheme {
                AlalayRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.agent.refreshDiagnostics()
        // Mic state may have changed in settings; re-read it.
        vm.onMicResult(
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
}
