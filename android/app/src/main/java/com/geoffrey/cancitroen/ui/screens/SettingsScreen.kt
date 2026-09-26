package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geoffrey.cancitroen.settings.AppSettings
import com.geoffrey.cancitroen.swc.SwcAction
import com.geoffrey.cancitroen.swc.SwcButton
import com.geoffrey.cancitroen.swc.SwcEndpoint
import com.geoffrey.cancitroen.system.LauncherGuard
import com.geoffrey.cancitroen.system.PermissionsHelper
import com.geoffrey.cancitroen.ui.components.SectionCard
import com.geoffrey.cancitroen.ui.screens.settings.SwcMappingSection
import com.geoffrey.cancitroen.ui.theme.AccentPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onglets de la page Paramètres. L'ordre reflète la fréquence d'usage : le
 * mapping volant en premier (souvent retouché après une session de conduite),
 * puis le thème (changement rare mais visible), enfin les sections plus
 * statiques (capteurs/carburant/permissions/dev).
 *
 * Chaque onglet est un Composable autonome qui reçoit `settings` + ses
 * callbacks dédiés — pas de logique partagée entre eux pour limiter les
 * recompositions croisées.
 */
private enum class SettingsTab(val emoji: String, val title: String) {
    SWC          ("🎛", "Volant"),
    THEME        ("🎨", "Thème"),
    BEHAVIOR     ("⚙",  "Comportement"),
    SENSORS_FUEL ("🌡", "Capteurs & ⛽"),
    PERMISSIONS  ("🔐", "Permissions"),
    DEV          ("🧪", "Dev"),
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    guardStatus: LauncherGuard.Status?,
    onLaunchAsHomeChange: (Boolean) -> Unit,
    onForceHomeRootChange: (Boolean) -> Unit,
    onAggressiveDisableChange: (Boolean) -> Unit,
    onReapplyGuard: () -> Unit,
    onSimulatorChange: (Boolean) -> Unit,
    onFuelPriceChange: (Double) -> Unit,
    onTankCapacityChange: (Float) -> Unit,
    onTExtOffsetChange: (Int) -> Unit,
    onSwcMappingChange: (SwcButton, SwcAction, SwcEndpoint) -> Unit,
    onSwcMappingReset: () -> Unit,
    onAccentPresetChange: (AccentPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // L'onglet sélectionné est rememberSaveable → préservé sur rotation et
    // recreation d'activity. Pas besoin de hisser dans le VM, c'est de l'état
    // purement UI.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { SettingsTab.values().toList() }

    // Refresh permissions seulement à chaque ON_RESUME : voir commentaire dans
    // PermissionsTab.
    var permTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Barre d'onglets scrollable (assez d'onglets pour ne pas tenir
        // sur 800 dp en mode landscape Atoto) ────────────────────────────
        // Variante « secondaire » : indicateur pleine largeur, comme l'ancien ScrollableTabRow.
        SecondaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEachIndexed { idx, tab ->
                Tab(
                    selected = selected == idx,
                    onClick = { selected = idx },
                    text = {
                        Text(
                            "${tab.emoji} ${tab.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected == idx) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        HorizontalDivider()

        // ── Contenu de l'onglet ──
        // Chaque tab a son propre scroll state pour que la position défile
        // soit conservée individuellement par onglet.
        when (tabs[selected]) {
            SettingsTab.SWC -> TabContent {
                SwcMappingSection(
                    mapping = settings.swcMapping,
                    onChange = onSwcMappingChange,
                    onReset = onSwcMappingReset,
                )
            }

            SettingsTab.THEME -> TabContent {
                SectionCard("🎨 Thème") {
                    Text(
                        "Choisis la teinte principale pour matcher l'ambiance lumineuse de ta voiture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    AccentPresetPicker(
                        current = settings.theme.accent,
                        onPick = onAccentPresetChange,
                    )
                }
            }

            SettingsTab.BEHAVIOR -> TabContent {
                val isHome = remember(permTick) { PermissionsHelper.isDefaultHome(ctx) }
                val currentHome = remember(permTick) { PermissionsHelper.currentDefaultHomePackage(ctx) }
                SectionCard("⚙ Comportement") {
                    SwitchRow("Lancer comme launcher Atoto (standard)",
                        settings.behavior.launchAsHome, onLaunchAsHomeChange)
                    if (settings.behavior.launchAsHome) {
                        LauncherStatusRow(
                            isDefault = isHome,
                            currentHome = currentHome,
                            onReassign = { PermissionsHelper.openHomeSettings(ctx) },
                        )
                    }
                }

                LauncherRootSection(
                    status = guardStatus,
                    forceHomeRoot = settings.behavior.forceHomeRoot,
                    aggressive = settings.behavior.aggressiveDisableLaunchers,
                    onForceHomeRootChange = onForceHomeRootChange,
                    onAggressiveDisableChange = onAggressiveDisableChange,
                    onReapply = onReapplyGuard,
                )
            }

            SettingsTab.SENSORS_FUEL -> TabContent {
                SectionCard("🌡 Calibration capteurs") {
                    NumberRow(
                        label = "Décalage T° extérieure",
                        value = settings.calibration.tExtOffsetC.toDouble(),
                        suffix = "°C",
                        step = 1.0,
                        min = -30.0,
                        max = 30.0,
                        decimals = 0,
                        onChange = { onTExtOffsetChange(it.toInt()) },
                    )
                    Text(
                        "Si l'app indique -1 °C alors que ta voiture mesure 11 °C, " +
                            "règle le décalage à +12. La valeur s'applique en temps réel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard("⛽ Carburant") {
                    NumberRow(
                        label = "Prix au litre",
                        value = settings.fuel.pricePerLiter,
                        suffix = "€/L",
                        step = 0.01,
                        min = 0.5,
                        max = 5.0,
                        decimals = 2,
                        onChange = onFuelPriceChange,
                    )
                    Spacer(Modifier.height(4.dp))
                    NumberRow(
                        label = "Capacité réservoir",
                        value = settings.fuel.tankCapacityLiters.toDouble(),
                        suffix = "L",
                        step = 1.0,
                        min = 20.0,
                        max = 100.0,
                        decimals = 0,
                        onChange = { onTankCapacityChange(it.toFloat()) },
                    )
                    Text(
                        "Le coût d'un plein est calculé à partir de ces deux valeurs au moment de la détection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsTab.PERMISSIONS -> TabContent {
                // Re-derive les états permission à chaque ON_RESUME — sinon
                // après retour du Settings system les checkmarks restent ✗.
                val locOk = remember(permTick) { PermissionsHelper.isLocationGranted(ctx) }
                val notifOk = remember(permTick) { PermissionsHelper.isNotificationGranted(ctx) }
                val battOk = remember(permTick) { PermissionsHelper.isIgnoringBatteryOptimizations(ctx) }
                val notifLisOk = remember(permTick) { PermissionsHelper.isNotificationListenerEnabled(ctx) }

                SectionCard("🔐 Permissions") {
                    Text(
                        "Sur Atoto Android 10, certaines permissions sont des accès spéciaux à activer manuellement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        label = "Localisation (vitesse GPS)",
                        granted = locOk,
                        onActivate = { PermissionsHelper.openAppDetails(ctx) },
                    )
                    PermissionRow(
                        label = "Notifications",
                        granted = notifOk,
                        onActivate = { PermissionsHelper.openAppDetails(ctx) },
                    )
                    PermissionRow(
                        label = "Optimisation batterie ignorée",
                        granted = battOk,
                        onActivate = { PermissionsHelper.requestIgnoreBatteryOptimizations(ctx) },
                    )
                    PermissionRow(
                        label = "Accès notifications (volant + Android Auto)",
                        granted = notifLisOk,
                        onActivate = { PermissionsHelper.openNotificationListenerSettings(ctx) },
                    )
                    Text(
                        "Le dernier est requis pour que les boutons SUIVANT/PRÉCÉDENT/PLAY au volant agissent sur Android Auto. Tape \"Activer\" puis coche CANCitroen dans la liste.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsTab.DEV -> TabContent {
                SectionCard("🧪 Démo / développement") {
                    SwitchRow("Mode simulateur (données factices)",
                        settings.dev.simulatorEnabled, onSimulatorChange)
                    Text(
                        "Génère des données véhicule réalistes (vitesse, RPM, carburant…) sans avoir besoin du CANable branché. Cycle de 2 min.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Wrapper de contenu d'onglet : scroll vertical + padding cohérent. */
@Composable
private fun TabContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Sélecteur de preset d'accent : un disque coloré par preset, le courant
 * encadré par un anneau plus épais. Tap → onPick.
 */
@Composable
private fun AccentPresetPicker(
    current: AccentPreset,
    onPick: (AccentPreset) -> Unit,
) {
    val presets = remember { AccentPreset.values().toList() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEach { p ->
            val selected = p == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickable { onPick(p) },
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 44.dp else 36.dp)
                        .clip(CircleShape)
                        .background(p.accent)
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = Color.White.copy(alpha = if (selected) 0.85f else 0f),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    p.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) p.accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onActivate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)) {
            Text(if (granted) "✓" else "✗",
                color = if (granted)
                    androidx.compose.ui.graphics.Color(0xFF66BB6A)
                else androidx.compose.ui.graphics.Color(0xFFEF5350),
                style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (!granted) {
            FilledTonalButton(onClick = onActivate, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Text("Activer", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text("Accordé", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Statut du launcher par défaut sous le toggle "Lancer comme launcher Atoto".
 *
 * Cas d'usage : sur Atoto Android 10, le ROM réassigne parfois son launcher
 * d'origine après un reboot / une mise à jour OTA / un retour de session
 * CarPlay. Si l'utilisateur a coché le toggle mais Android pointe vers un
 * autre package, on affiche un warning + un bouton pour rouvrir le picker.
 */
@Composable
private fun LauncherStatusRow(
    isDefault: Boolean,
    currentHome: String?,
    onReassign: () -> Unit,
) {
    val accent = if (isDefault)
        androidx.compose.ui.graphics.Color(0xFF66BB6A)
    else androidx.compose.ui.graphics.Color(0xFFFFA726)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isDefault) "✓ Launcher par défaut actuel"
                else "⚠ Un autre launcher est actif",
                color = accent,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isDefault) {
                Text(
                    "Actuel : ${currentHome ?: "(aucun — Android affiche le picker)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!isDefault) {
            FilledTonalButton(
                onClick = onReassign,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Redéfinir", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Bloc "launcher forcé par root". Distinct du toggle "standard" (qui ne fait
 * qu'ouvrir le picker Android) : ici on pilote [LauncherGuard] — home par
 * défaut + launcher natif FYT via root, et service root qui nous ramène au
 * premier plan quand le launcher d'origine repasse devant (et seulement lui).
 * [status] est null tant que le premier probe root n'a pas répondu.
 */
@Composable
private fun LauncherRootSection(
    status: LauncherGuard.Status?,
    forceHomeRoot: Boolean,
    aggressive: Boolean,
    onForceHomeRootChange: (Boolean) -> Unit,
    onAggressiveDisableChange: (Boolean) -> Unit,
    onReapply: () -> Unit,
) {
    val green = Color(0xFF66BB6A)
    val amber = Color(0xFFFFA726)
    val red = Color(0xFFEF5350)
    val rootKnown = status != null
    val rootOk = status?.rootAvailable == true

    SectionCard("🏠 Launcher forcé (root)") {
        Text(
            "Remplace le launcher d'origine : CANCitroen devient l'écran d'accueil du " +
                "système, et un service root le ramène au premier plan si le launcher " +
                "Atoto repasse devant. Seul le launcher d'origine est visé : Android Auto, " +
                "Maps, les Réglages ou toute autre app s'ouvrent normalement. Le service " +
                "relance aussi l'app si Android la tue. Nécessite le root.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Statut root
        StatusLine(
            symbol = if (!rootKnown) "…" else if (rootOk) "✓" else "✗",
            symbolColor = if (!rootKnown) MaterialTheme.colorScheme.onSurfaceVariant
                          else if (rootOk) green else red,
            text = when {
                !rootKnown -> "Root — vérification…"
                rootOk -> "Root détecté (uid=0)"
                else -> "Root indisponible"
            },
        )
        if (rootKnown && !rootOk) {
            Text(
                "Accorde l'accès superuser à CANCitroen dans Magisk, puis rouvre cette page.",
                style = MaterialTheme.typography.bodySmall,
                color = amber,
            )
        }

        Spacer(Modifier.height(4.dp))
        SwitchRow(
            "Forcer CANCitroen comme launcher",
            forceHomeRoot,
            onForceHomeRootChange,
            enabled = rootOk || !rootKnown,
        )

        if (forceHomeRoot) {
            val running = status?.guardRunning == true
            StatusLine(
                symbol = if (running) "✓" else "⏳",
                symbolColor = if (running) green else amber,
                text = when {
                    !running -> "Service root en attente…"
                    status.detectionMode == "poll" ->
                        "Service root actif — détection par sondage (repli)"
                    else -> "Service root actif — détection instantanée"
                },
            )
            StatusLine(
                symbol = if (status?.isDefaultHome == true) "✓" else "⚠",
                symbolColor = if (status?.isDefaultHome == true) green else amber,
                text = if (status?.isDefaultHome == true)
                    "Home par défaut : CANCitroen"
                else "Home par défaut : ${status?.currentHome ?: "—"}",
            )
            status?.fytLauncher?.let { romLauncher ->
                StatusLine(
                    symbol = if (status.isFytLauncher) "✓" else "⚠",
                    symbolColor = if (status.isFytLauncher) green else amber,
                    text = if (status.isFytLauncher)
                        "Launcher du ROM : CANCitroen (au lieu de $romLauncher)"
                    else "Launcher du ROM : $romLauncher",
                )
            }
            if (status?.bootHookSupported == true) {
                StatusLine(
                    symbol = if (status.bootHookInstalled) "✓" else "⏳",
                    symbolColor = if (status.bootHookInstalled) green else amber,
                    text = if (status.bootHookInstalled)
                        "Démarrage au boot via Magisk (service.d)"
                    else "Démarrage au boot : en attente…",
                )
            }
            if (running) {
                Text(
                    guardActivitySummary(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.lastConflictAt?.let { at ->
                Text(
                    "Conflit à ${hhmm(at)} : le ROM relance son launcher en boucle. " +
                        "Si ça se répète, active le mode agressif.",
                    style = MaterialTheme.typography.bodySmall,
                    color = amber,
                )
            }
            val competitors = status?.competitors.orEmpty()
            if (competitors.isNotEmpty()) {
                Text(
                    "Launchers d'origine visés : " + competitors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val disabled = status?.disabledCompetitors.orEmpty()
            if (disabled.isNotEmpty()) {
                Text(
                    "Désactivés (agressif) : " + disabled.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = amber,
                )
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow(
                "Mode agressif : désactiver le launcher d'origine",
                aggressive,
                onAggressiveDisableChange,
                enabled = rootOk,
            )
            Text(
                "Fait `pm disable` sur l'activité d'accueil du launcher d'origine " +
                    "uniquement, pas sur l'app entière (réversible : décoche pour la " +
                    "réactiver). À utiliser seulement si le forçage simple ne tient pas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Le service root survit à l'arrêt forcé de l'app (il la relance) : " +
                    "pour l'arrêter, décoche « Forcer CANCitroen comme launcher ».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReapply, enabled = rootOk) {
                Text("Réappliquer maintenant")
            }
        }
    }
}

/** Activité du service root depuis son démarrage, en une ligne. */
private fun guardActivitySummary(s: LauncherGuard.Status): String {
    val catches = if (s.catches == 0) "aucun retour forcé"
        else "${s.catches} retour(s) forcé(s), dernier à ${hhmm(s.lastCatchAt)}" +
            (s.lastCatchFrom?.let { " ($it)" } ?: "")
    val revives = if (s.revives == 0) ""
        else " · app relancée ${s.revives} fois, dernière à ${hhmm(s.lastReviveAt)}"
    return "Depuis le démarrage du service : $catches$revives."
}

private fun hhmm(epochSeconds: Long?): String =
    epochSeconds?.let { SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(it * 1000)) } ?: "—"

/** Ligne "symbole coloré + texte" réutilisée pour les statuts. */
@Composable
private fun StatusLine(symbol: String, symbolColor: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(symbol, color = symbolColor, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Ligne avec label + valeur numérique modifiable via boutons +/-. */
@Composable
private fun NumberRow(
    label: String,
    value: Double,
    suffix: String,
    step: Double,
    min: Double,
    max: Double,
    decimals: Int,
    onChange: (Double) -> Unit,
) {
    val fmt = "%.${decimals}f"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { onChange((value - step).coerceAtLeast(min)) },
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("−", style = MaterialTheme.typography.titleMedium) }
            Text(
                "${fmt.format(value)} $suffix",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            FilledTonalButton(
                onClick = { onChange((value + step).coerceAtMost(max)) },
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("+", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
