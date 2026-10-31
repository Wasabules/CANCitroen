package com.geoffrey.cancitroen.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.car.app.connection.CarConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val carState = mutableStateOf("inconnu")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarConnection(this).type.observe(this) { type ->
            carState.value = when (type) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "connecté"
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "non connecté"
                else -> "type $type"
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LinkTestScreen(carState.value, onShare = ::share)
            }
        }
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(send, "Partager le rapport"))
    }
}

@Composable
private fun LinkTestScreen(carState: String, onShare: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manualIp by rememberSaveable { mutableStateOf("") }
    var report by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Test de liaison avec l'Atoto", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Android Auto : $carState", color = MaterialTheme.colorScheme.onBackground)
        Text(
            "À l'arrêt, avec Android Auto lancé : active le test dans CANCitroen (Réglages → Développeur → App compagnon), puis lance-le ici.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("Adresse de l'Atoto (facultatif)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    report = ""
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LinkTest(context) { line ->
                                scope.launch { report += line + "\n" }
                            }.run(manualIp, carState)
                        }
                        running = false
                    }
                },
            ) { Text(if (running) "Test en cours…" else "Lancer le test") }
            OutlinedButton(enabled = !running && report.isNotEmpty(), onClick = { onShare(report) }) {
                Text("Partager le rapport")
            }
        }
        SelectionContainer {
            Text(
                report,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
