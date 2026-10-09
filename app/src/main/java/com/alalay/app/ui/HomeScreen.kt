package com.alalay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alalay.app.MainViewModel
import com.alalay.app.Screen
import com.alalay.app.agent.AgentLoop

/**
 * Home: one giant hold-to-talk control, one status line, one stop
 * affordance. The screen exists for judges and setup; operation is
 * eyes-free via speech (NFR-05).
 */
@Composable
fun HomeScreen(vm: MainViewModel) {
    val pipeline by vm.pipeline.collectAsState()
    val typed by vm.typedCommand.collectAsState()
    val lastSpoken = vm.agent.loop.lastSpoken

    val busy = pipeline != AgentLoop.AgentState.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Alalay",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Surface(
                color = if (busy) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    pipeline.name,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (busy) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (lastSpoken.isBlank()) "Press and hold the button, then tell me what to do."
            else lastSpoken,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        )

        Spacer(Modifier.weight(1f, fill = false).height(24.dp))

        // The one focal control: hold to talk (DEC-10).
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(
                    color = if (pipeline == AgentLoop.AgentState.LISTENING)
                        MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                )
                .semantics {
                    contentDescription =
                        "Hold to talk. Speak a command, then release."
                }
                .pointerInput(busy) {
                    detectTapGestures(
                        onPress = {
                            if (!busy) {
                                vm.onPttDown()
                                tryAwaitRelease()
                                vm.onPttUp()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pipeline == AgentLoop.AgentState.LISTENING) "Listening"
                else "Hold\nto talk",
                color = if (pipeline == AgentLoop.AgentState.LISTENING)
                    MaterialTheme.colorScheme.onSecondary
                else MaterialTheme.colorScheme.onPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (busy) {
            Button(
                onClick = { vm.onStop() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics { contentDescription = "Stop" },
            ) {
                Text("Stop", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = typed,
            onValueChange = vm::onTypedCommandChange,
            label = { Text("Or type a command") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::submitTyped,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = typed.isNotBlank(),
        ) {
            Text("Run command", fontSize = 18.sp)
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { vm.navigate(Screen.DIAGNOSTICS) }) {
                Text("Diagnostics", fontSize = 16.sp)
            }
            TextButton(onClick = { vm.navigate(Screen.SETUP) }) {
                Text("Setup", fontSize = 16.sp)
            }
        }
    }
}
