package com.alalay.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alalay.app.MainViewModel
import com.alalay.app.Screen

/**
 * First-time setup (FR-14, 03-§1): accessibility enable incl. the
 * restricted-settings path, mic permission, model presence, offline TTS
 * check, optional alias teaching. Statuses are real, actions do what
 * they say (R-26).
 */
@Composable
fun SetupScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val d by vm.diag.collectAsState()
    val micGranted by vm.micGranted.collectAsState()
    val aliasForm by vm.aliasForm.collectAsState()
    val teachBusy by vm.teachBusy.collectAsState()
    val modelMessage by vm.modelMessage.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onMicResult(granted) }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importModel) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Everything stays on this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        SetupStep(
            number = 1,
            title = "Screen control",
            ok = d.accessibilityOn,
            okText = "Alalay Screen Control is on",
            missingText = "Needed so I can see and tap for you",
        ) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Open accessibility settings")
            }
            Text(
                "If Android blocks the switch: open App info, tap the three dots, " +
                    "then Allow restricted settings, and come back.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }) { Text("Open App info") }
        }

        SetupStep(
            number = 2,
            title = "Microphone",
            ok = micGranted,
            okText = "Mic permission granted",
            missingText = "Needed to hear your commands",
        ) {
            Button(
                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow microphone") }
        }

        SetupStep(
            number = 3,
            title = "Local model",
            ok = d.modelFilePresent,
            okText = "Model file found",
            missingText = "Optional: add a model file, or the built-in rules planner is used",
        ) {
            Button(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose model file") }
            if (modelMessage.isNotEmpty()) {
                Text(
                    modelMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SetupStep(
            number = 4,
            title = "Voice",
            ok = d.ttsReady,
            okText = "Offline voice ready",
            missingText = "Install a downloaded voice in system TTS settings",
        ) {
            TextButton(onClick = {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }) { Text("Open TTS settings") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Teach me a name (optional)", fontWeight = FontWeight.Bold)
                Text(
                    "For example: Mama means Remedios Domingo. I only save after you say yes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = aliasForm.alias,
                    onValueChange = { vm.onAliasChange(it, aliasForm.target) },
                    label = { Text("Name you say") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasForm.target,
                    onValueChange = { vm.onAliasChange(aliasForm.alias, it) },
                    label = { Text("Full name in the app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = vm::teachAlias,
                    enabled = !teachBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (teachBusy) "Listening for yes or no..." else "Save by voice") }
                if (aliasForm.message.isNotEmpty()) {
                    Text(
                        aliasForm.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { vm.navigate(Screen.HOME) },
            enabled = d.accessibilityOn && micGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text("Start using Alalay", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (!(d.accessibilityOn && micGranted)) {
            Text(
                "Screen control and the microphone are required first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    ok: Boolean,
    okText: String,
    missingText: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "$number",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        color = if (ok) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(start = 12.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (ok) okText else missingText,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            if (!ok) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}
