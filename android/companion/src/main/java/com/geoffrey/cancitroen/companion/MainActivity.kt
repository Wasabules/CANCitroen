package com.geoffrey.cancitroen.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val carState = mutableStateOf("inconnu")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarConnection(this).type.observe(this) { type ->
            // Écran ouvert pendant Android Auto : le suivi peut démarrer, l'app est au premier plan.
            if (type == CarConnection.CONNECTION_TYPE_PROJECTION && !CarSessionService.status.running) {
                CarSessionService.start(this, "Android Auto, écran du compagnon ouvert")
            }
            carState.value = when (type) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "connecté"
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "non connecté"
                else -> "type $type"
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        TABS.forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                        }
                    }
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 12.dp)) {
                        when (tab) {
                            0 -> VehicleTab()
                            1 -> Page { TrackingSection(onShare = ::share) }
                            else -> Page {
                                TransferSection()
                                RetentionSetting()
                                VehicleOptions()
                                HorizontalDivider()
                                HomeAssistantSection()
                                HorizontalDivider()
                                LinkTestSection(carState.value, onShare = ::share)
                            }
                        }
                    }
                }
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

private const val JOURNAL_LINES = 30
private val TABS = listOf("Voiture", "Suivi", "Réglages")

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Ce que l'utilisateur choisit de la voiture : son carburant, et le partage de sa position. */
@Composable
private fun VehicleOptions() {
    val context = LocalContext.current
    val prefs = remember { TransferPrefs(context) }
    var fuel by remember { mutableStateOf(prefs.fuelType) }
    var position by remember { mutableStateOf(prefs.shareCarPosition) }

    Text("Carburant de la voiture, pour le CO₂ des trajets", color = MaterialTheme.colorScheme.onBackground)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in FuelType.entries) {
            FilterChip(
                selected = fuel == choice,
                onClick = {
                    prefs.fuelType = choice
                    fuel = choice
                },
                label = { Text(choice.label) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = position,
            onCheckedChange = {
                prefs.shareCarPosition = it
                position = it
            },
        )
        Text("Position de la voiture", color = MaterialTheme.colorScheme.onBackground)
    }
    Text(
        "Le GPS de l'Atoto, envoyé chiffré au téléphone pendant les trajets, puis à Home Assistant s'il est connecté : où la voiture est garée, et ses trajets sur la carte. Désactivé par défaut ; une fois coupé, plus aucune position ne part.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** La durée de conservation du détail ; ce qui serait retiré est d'abord annoncé. */
@Composable
private fun RetentionSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { TransferPrefs(context) }
    var retention by remember { mutableStateOf(prefs.retention) }
    var pending by remember { mutableStateOf<Pair<Retention, Int>?>(null) }
    var message by remember { mutableStateOf("") }

    fun apply(choice: Retention) {
        prefs.retention = choice
        retention = choice
        scope.launch {
            val removed = withContext(Dispatchers.IO) { TelemetrySync.purge(context) }
            message = if (removed > 0) "$removed jour(s) de détail retiré(s) du téléphone." else ""
        }
    }

    Text("Conserver le détail à 5 s sur le téléphone", color = MaterialTheme.colorScheme.onBackground)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in Retention.entries) {
            FilterChip(
                selected = retention == choice,
                onClick = {
                    scope.launch {
                        val expired = withContext(Dispatchers.IO) {
                            choice.firstKeptDay(System.currentTimeMillis())
                                ?.let { Retention.expired(TelemetryFiles(context).sampleFiles(), it).size } ?: 0
                        }
                        if (expired > 0) pending = choice to expired else apply(choice)
                    }
                },
                label = { Text(choice.label) },
            )
        }
    }
    Text(
        "Au-delà, seuls les fichiers echantillons-… sont retirés : trajets, pleins et agrégats restent, et les graphiques s'en servent. Copie le détail sur le PC avant pour le garder.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onBackground)
    pending?.let { (choice, count) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Retirer $count jour(s) de détail ?") },
            text = {
                Text("Les fichiers d'échantillons d'avant le ${choice.firstKeptDay(System.currentTimeMillis())} seront supprimés du téléphone. Les copies déjà faites sur le PC ne sont pas touchées.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    apply(choice)
                }) { Text("Retirer") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annuler") } },
        )
    }
}

@SuppressLint("BatteryLife") // Exemption demandée à l'utilisateur : le suivi doit démarrer seul en voiture.
@Composable
private fun TrackingSection(onShare: (String) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(SessionReport.rows(context)) }
    var checks by remember { mutableStateOf(Reliability.checks(context)) }
    var journal by remember { mutableStateOf(CompanionLog.tail(context, JOURNAL_LINES)) }
    var running by remember { mutableStateOf(CarSessionService.status.running) }
    var car by remember { mutableStateOf(TransferPrefs(context).carBluetooth) }
    var notice by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks = Reliability.checks(context)
        notice = if (granted) "" else "Autorisation refusée : à donner dans les réglages de l'app, rubrique Autorisations."
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checks = Reliability.checks(context)
    }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    // Le suivi tourne sans cet écran : son état se relit.
    LaunchedEffect(Unit) {
        while (true) {
            rows = SessionReport.rows(context)
            checks = Reliability.checks(context)
            journal = CompanionLog.tail(context, JOURNAL_LINES)
            running = CarSessionService.status.running
            car = TransferPrefs(context).carBluetooth
            delay(2_000)
        }
    }

    fun fix(fix: Reliability.Fix) {
        notice = ""
        when (fix) {
            Reliability.Fix.BATTERY -> settings.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
            Reliability.Fix.BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            Reliability.Fix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            Reliability.Fix.APP_SETTINGS -> settings.launch(appDetails)
        }
    }

    Text("Suivi de la voiture", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "Démarre seul quand le téléphone se connecte à la voiture : l'Atoto est interrogé toutes les 30 s, Home Assistant reçoit chaque changement, l'historique arrive toutes les 10 min.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((label, value) in rows) {
        Row {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
            Text(value, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            OutlinedButton(onClick = { CarSessionService.stop() }) { Text("Arrêter le suivi") }
        } else {
            Button(
                onClick = {
                    notice = if (CarSessionService.start(context, "démarré à la main")) "" else "Associe d'abord le téléphone à l'Atoto, plus bas."
                },
            ) { Text("Démarrer le suivi") }
        }
    }
    if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.error)

    Text("Fiabilité", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    for (check in checks) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${check.label} : ${if (check.ok) "OK" else "à régler"}",
                    color = if (check.ok) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error,
                )
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!check.ok) TextButton(onClick = { fix(check.fix) }) { Text("Régler") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (car.isEmpty()) "Voiture Bluetooth : reconnue au premier trajet suivi." else "Voiture Bluetooth : ${CarSessionService.maskedAddress(car)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (car.isNotEmpty()) {
            TextButton(onClick = {
                TransferPrefs(context).carBluetooth = ""
                car = ""
            }) { Text("Oublier") }
        }
    }

    Text("Journal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = journal.isNotEmpty(),
            onClick = { onShare(CompanionLog.tail(context, 400).joinToString("\n")) },
        ) { Text("Partager le journal") }
        TextButton(onClick = {
            CompanionLog.clear(context)
            journal = emptyList()
        }) { Text("Effacer") }
    }
    SelectionContainer {
        Text(
            journal.joinToString("\n").ifEmpty { "Rien pour l'instant." },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun TransferSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { TransferPrefs(context) }
    var paired by remember { mutableStateOf(prefs.paired) }
    var auto by remember { mutableStateOf(prefs.auto) }
    var manualIp by rememberSaveable { mutableStateOf(prefs.manualIp) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(prefs.lastResult) }
    var lastCheck by remember { mutableStateOf(prefs.lastCheck) }
    var pairingAnswer by remember { mutableStateOf("") }

    // Le transfert automatique tourne sans cet écran : son résultat se relit.
    LaunchedEffect(Unit) {
        TransferScheduler.refresh(context)
        while (true) {
            if (!busy) message = prefs.lastResult.ifEmpty { message }
            lastCheck = prefs.lastCheck
            delay(2_000)
        }
    }

    fun work(onAnswer: (String) -> Unit, block: () -> String) {
        busy = true
        prefs.manualIp = manualIp
        scope.launch {
            onAnswer(withContext(Dispatchers.IO) { block() })
            paired = prefs.paired
            TransferScheduler.refresh(context)
            busy = false
        }
    }

    Text("Télémétrie de la voiture", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(
        value = manualIp,
        onValueChange = { manualIp = it },
        label = { Text("Adresse de l'Atoto (facultatif)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (paired) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = auto,
                onCheckedChange = {
                    auto = it
                    prefs.auto = it
                    TransferScheduler.refresh(context)
                },
            )
            Text("Transfert pendant les trajets", color = MaterialTheme.colorScheme.onBackground)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { work({ message = it }) { TelemetrySync.run(context).message } }) {
                Text(if (busy) "Transfert…" else "Transférer maintenant")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    prefs.forgetPairing()
                    paired = false
                    TransferScheduler.refresh(context)
                },
            ) { Text("Dissocier") }
        }
        OutlinedButton(
            enabled = !busy,
            onClick = { work({ message = it }) { TelemetrySync.verify(context).message } },
        ) { Text("Vérifier et récupérer") }
        Text(
            "Fichiers dans Documents/CANCitroen, à copier sur le PC. « Vérifier et récupérer » redemande ce qui manque à l'Atoto, y compris les fichiers déplacés sur le PC tant qu'il les garde (7 jours)." +
                if (lastCheck.isNotEmpty()) "\nDernière vérification périodique : $lastCheck" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Avec Android Auto connecté : active le transfert dans CANCitroen (Réglages → Développeur → App compagnon), puis saisis son code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code affiché par l'Atoto") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !busy, onClick = { work({ pairingAnswer = it }) { TelemetrySync.pair(context, code) } }) {
            Text(if (busy) "Association…" else "Associer")
        }
        if (pairingAnswer.isNotEmpty()) Text(pairingAnswer, color = MaterialTheme.colorScheme.onBackground)
    }
    if (paired && message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun HomeAssistantSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(HomeAssistant.isConnected(context)) }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf(HomeAssistant.headersText(context)) }
    var showHeaders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(HomeAssistant.lastResult(context)) }
    var testResult by remember { mutableStateOf("") }

    // Les envois automatiques tournent sans cet écran : leur résultat se relit.
    LaunchedEffect(Unit) {
        while (true) {
            if (!busy && connected) message = HomeAssistant.lastResult(context).ifEmpty { message }
            delay(2_000)
        }
    }

    fun work(onAnswer: (String) -> Unit, block: () -> String) {
        HomeAssistant.saveHeaders(context, headers)?.let { invalid ->
            message = ""
            testResult = ""
            onAnswer(invalid)
            return
        }
        busy = true
        // Une réponse d'un essai précédent ferait croire que celui-ci a échoué.
        message = ""
        testResult = ""
        scope.launch {
            onAnswer(withContext(Dispatchers.IO) { block() })
            connected = HomeAssistant.isConnected(context)
            if (connected) token = ""
            TransferScheduler.refresh(context)
            busy = false
        }
    }

    Text("Home Assistant", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    if (connected) {
        Text(
            "La voiture est un appareil « Citroën C2 » sur ${HomeAssistant.address(context)}. Publication pendant les trajets, à chaque changement et toutes les 5 minutes au moins, sans position GPS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Adresse de Home Assistant") },
            placeholder = { Text("https://ma-maison.example") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Jeton longue durée") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = headers,
        onValueChange = { headers = it },
        label = { Text("En-têtes personnalisés (facultatif)") },
        placeholder = { Text("CF-Access-Client-Id: …\nCF-Access-Client-Secret: …") },
        minLines = 2,
        visualTransformation = if (showHeaders) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Un par ligne, envoyés seulement à cette adresse. Pour Cloudflare Access : un jeton de service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showHeaders = !showHeaders }) { Text(if (showHeaders) "Masquer" else "Afficher") }
    }
    if (!connected) {
        Text(
            "Le jeton se crée dans Home Assistant : profil → Sécurité. Il ne sert qu'à l'inscription et n'est pas conservé. Pour une publication sur la route, le serveur doit être joignable depuis Internet ; sinon elle attend le retour à la maison.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (connected) {
            Button(
                enabled = !busy,
                onClick = {
                    work({ message = it }) { HomeAssistant.publish(context, TransferScheduler.isProjecting(context), force = true).message }
                },
            ) { Text(if (busy) "Envoi…" else "Envoyer maintenant") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    HomeAssistant.disconnect(context)
                    connected = false
                    message = ""
                    testResult = ""
                    TransferScheduler.refresh(context)
                },
            ) { Text("Déconnecter") }
        } else {
            Button(enabled = !busy, onClick = { work({ message = it }) { HomeAssistant.connect(context, url, token) } }) {
                Text(if (busy) "Connexion…" else "Connecter")
            }
        }
    }
    OutlinedButton(
        enabled = !busy,
        onClick = { work({ testResult = it }) { HomeAssistant.test(context, url, token) } },
    ) { Text(if (busy) "Test en cours…" else "Tester l'accès") }
    if (testResult.isNotEmpty()) Text(testResult, color = MaterialTheme.colorScheme.onBackground)
    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun LinkTestSection(carState: String, onShare: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manualIp by rememberSaveable { mutableStateOf("") }
    var report by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Test de liaison avec l'Atoto", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
