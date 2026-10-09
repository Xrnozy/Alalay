package com.alalay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alalay.app.MainViewModel
import com.alalay.app.Screen

/**
 * Diagnostics (FR-11): model identity, latencies, network badge, pipeline
 * state, type-only event log. Every value is real (R-17/R-38).
 */
@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val d by vm.diag.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Diagnostics", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.navigate(Screen.HOME) }) { Text("Back", fontSize = 16.sp) }
        }

        Spacer(Modifier.height(8.dp))

        // The judging headline: is this really local?
        Surface(
            color = if (d.networkOnline) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                if (d.networkOnline) "NETWORK: ONLINE" else "NETWORK: OFFLINE",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (d.networkOnline) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.height(20.dp))

        DiagRow("Planner", d.modelIdentity)
        DiagRow("Model file", if (d.modelFilePresent) "present" else "not found (rules planner active)")
        DiagRow("Speech engine", d.sttEngine)
        DiagRow("Last inference", d.lastInferenceMs?.let { "$it ms" } ?: "none yet")
        DiagRow("Last STT", d.lastSttMs?.let { "$it ms" } ?: "none yet")
        DiagRow("Screen control", if (d.accessibilityOn) "ON" else "OFF")
        DiagRow("Voice (offline TTS)", if (d.ttsReady) "ready" else "not ready")
        DiagRow("Pipeline", d.pipeline)
        DiagRow("Elements in last dump", "${d.lastElementCount}")

        Spacer(Modifier.height(20.dp))
        Text("Event log (types only)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        if (d.events.isEmpty()) {
            Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            d.events.forEach { e ->
                Text(
                    "• $e",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}
