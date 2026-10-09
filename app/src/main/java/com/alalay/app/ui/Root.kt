package com.alalay.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.alalay.app.MainViewModel
import com.alalay.app.Screen

@Composable
fun AlalayRoot(vm: MainViewModel) {
    val screen by vm.screen.collectAsState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.DIAGNOSTICS -> DiagnosticsScreen(vm)
            Screen.SETUP -> SetupScreen(vm)
        }
    }
}
