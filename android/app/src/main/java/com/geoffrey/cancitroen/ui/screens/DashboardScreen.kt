package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.decode.CanIds
import com.geoffrey.cancitroen.decode.Doors
import com.geoffrey.cancitroen.decode.EmfPage
import com.geoffrey.cancitroen.decode.IgnitionMode
import com.geoffrey.cancitroen.decode.KeyPosition
import com.geoffrey.cancitroen.decode.Lights
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.decode.Warnings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.KvRow
import com.geoffrey.cancitroen.ui.components.Pill
import com.geoffrey.cancitroen.ui.components.PillStyle
import com.geoffrey.cancitroen.ui.components.SectionCard
import com.geoffrey.cancitroen.ui.theme.AppIcons
import com.geoffrey.cancitroen.ui.theme.Dim

/**
 * Chaque carte ne reçoit que ses champs : à chaque échantillon (10 Hz en
 * roulant), seules les cartes dont une valeur a changé recomposent.
 */
@Composable
fun DashboardScreen(state: VehicleState, modifier: Modifier = Modifier) {
    val tankLiters by remember {
        App.get().settings.flow.map { it.fuel.tankCapacityLiters }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = CanIds.FUEL_TANK_LITERS.toFloat())
    val s = state
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),  // 7" landscape : 3 colonnes
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // ── HERO : vitesse + RPM (chacun sur 1.5 colonnes ≈ pleine largeur partagée) ──
        item(span = { GridItemSpan(maxLineSpan / 2) }) {
            HeroCard(
                label = "Vitesse",
                value = s.speed?.let { "%.0f".format(it) } ?: "—",
                unit = "km/h",
            )
        }
        item(span = { GridItemSpan(maxLineSpan - maxLineSpan / 2) }) {
            HeroCard(
                label = "Régime",
                value = s.rpm?.let { "%.0f".format(it) } ?: "—",
                unit = "tr/min",
            )
        }

        item { BsiCard(s.keyPosition, s.ignitionMode, s.economyMode, s.nightMode, s.dashboardBrightness, s.datetimeBsi) }
        item { MoteurCard(s.tCoolant, s.tOil, s.tExt, s.reverseGear, s.gearCmb) }
        item { CarburantCard(s.fuelPct, s.fuelLitersEst, s.fuelInst, s.rangeKm, s.oilLevelPct, s.oilLevelLitersEst, tankLiters) }
        item { DistanceCard(s.odo, s.tripDist, s.tripAvgSpeed, s.tripAvgCons, s.emfPage) }
        item { MaintenanceCard(s.maintDue, s.maintKmRemaining, s.maintDaysRemaining) }
        item { VolantCard(s.wheelButton, s.wheelButtonRaw, s.wheelScroll, s.wheelScrollDelta) }

        item(span = { GridItemSpan(maxLineSpan) }) { FeuxCard(s.lights) }
        item(span = { GridItemSpan(maxLineSpan) }) { PortesCard(s.doors) }
        item(span = { GridItemSpan(maxLineSpan) }) { TemoinsCard(s.warn, s.handbrake, s.warningsOn) }
    }
}

@Composable
private fun HeroCard(label: String, value: String, unit: String) {
    GlassCard(contentPadding = 20.dp) {
        Column {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BsiCard(
    keyPosition: KeyPosition, ignitionMode: IgnitionMode, economyMode: Boolean,
    nightMode: Boolean, dashboardBrightness: Int?, datetimeBsi: String?,
) = SectionCard("Contact (BSI)", icon = AppIcons.Key) {
    KvRow("Position clé", keyPosition.name)
    KvRow("Mode allumage", ignitionMode.name)
    KvRow("Mode économique", if (economyMode) "oui" else "non")
    KvRow("Mode nuit", if (nightMode) "oui" else "non")
    KvRow("Luminosité TdB", dashboardBrightness?.let { "$it/15" } ?: "—")
    KvRow("Heure BSI", datetimeBsi ?: "—")
}

@Composable
private fun MoteurCard(
    tCoolant: Int?, tOil: Int?, tExt: Int?, reverseGear: Boolean, gearCmb: String?,
) = SectionCard("Moteur", icon = AppIcons.Speed) {
    KvRow("T° liquide refroid.", tCoolant?.toString() ?: "—", "°C")
    KvRow("T° huile", tOil?.toString() ?: "—", "°C")
    KvRow("T° extérieure", tExt?.toString() ?: "—", "°C")
    KvRow("Marche arrière", if (reverseGear) "OUI" else "non")
    KvRow("Rapport engagé", gearCmb ?: "—")
}

@Composable
private fun CarburantCard(
    fuelPct: Int?, fuelLitersEst: Double?, fuelInst: Double?, rangeKm: Int?,
    oilLevelPct: Int?, oilLevelLitersEst: Double?, tankLiters: Float,
) = SectionCard("Carburant et huile", icon = AppIcons.LocalGasStation) {
    KvRow("Carburant", fuelPct?.toString() ?: "—", "%")
    KvRow("Estimation", fuelLitersEst?.let { "%.1f".format(it) } ?: "—", "L / %.0f L".format(tankLiters))
    KvRow("Conso instant", fuelInst?.let { "%.1f".format(it) } ?: "—", "l/100")
    KvRow("Autonomie", rangeKm?.toString() ?: "—", "km")
    KvRow("Niveau huile", oilLevelPct?.toString() ?: "—", "%")
    KvRow("Estimation", oilLevelLitersEst?.let { "%.2f".format(it) } ?: "—", "L / 3 L")
}

@Composable
private fun DistanceCard(
    odo: Double?, tripDist: Double?, tripAvgSpeed: Int?, tripAvgCons: Double?, emfPage: EmfPage,
) = SectionCard("Distance et trajet", icon = AppIcons.Straighten) {
    KvRow("Odomètre", odo?.let { "%.1f".format(it) } ?: "—", "km")
    KvRow("Distance du trajet", tripDist?.let { "%.1f".format(it) } ?: "—", "km")
    KvRow("Vitesse moyenne", tripAvgSpeed?.toString() ?: "—", "km/h")
    KvRow("Conso moyenne", tripAvgCons?.let { "%.1f".format(it) } ?: "—", "l/100")
    KvRow("Page EMF", emfPage.name)
}

@Composable
private fun MaintenanceCard(maintDue: Boolean, kmRemaining: Int?, daysRemaining: Int?) =
    SectionCard("Entretien", icon = AppIcons.Build) {
        KvRow("Entretien dû", if (maintDue) "oui" else "non")
        KvRow("Km avant entretien", kmRemaining?.toString() ?: "—", "km")
        KvRow("Jours avant ent.", daysRemaining?.toString() ?: "—", "j")
    }

@Composable
private fun VolantCard(wheelButton: String?, raw: Int, scroll: Int?, scrollDelta: Int) =
    SectionCard("Commandes au volant", icon = AppIcons.Tune) {
        KvRow(
            "Bouton actif", wheelButton ?: "—",
            valueColor = MaterialTheme.colorScheme.primary,
        )
        KvRow("Octet brut [0]", "0x%02X".format(raw))
        KvRow("Molette", scroll?.toString() ?: "—")
        KvRow("Δ molette", scrollDelta.toString())
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeuxCard(lights: Lights) = SectionCard("Feux", icon = AppIcons.Lightbulb) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("Position",   lights.feuxPosition,     PillStyle.On)
        Pill("Croisement", lights.feuxCroisement,   PillStyle.On)
        Pill("Route",      lights.feuxRoute,        PillStyle.On)
        Pill("Antibrouillard AV", lights.antibrouillardAv, PillStyle.On)
        Pill("Antibrouillard AR", lights.antibrouillardAr, PillStyle.On)
        Pill("Clignotant G", lights.clignoG,          PillStyle.Warn)
        Pill("Clignotant D", lights.clignoD,          PillStyle.Warn)
        Pill("Feux de jour",        lights.drl,              PillStyle.On)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortesCard(doors: Doors) = SectionCard("Ouvertures", icon = AppIcons.MeetingRoom) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("Porte AV G",      doors.avg,        PillStyle.Err)
        Pill("Porte AV D",      doors.avd,        PillStyle.Err)
        Pill("Coffre",         doors.coffre,     PillStyle.Err)
        Pill("Capot",          doors.capot,      PillStyle.Err)
        Pill("Vitre AR",       doors.vitreAr,    PillStyle.Err)
        Pill("Trappe carburant",   doors.trappeCarb, PillStyle.Warn)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemoinsCard(warn: Warnings, handbrake: Boolean, warningsOn: Boolean) =
    SectionCard("Témoins et alertes", icon = AppIcons.Warning) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("Frein à main",     handbrake,                 PillStyle.Warn)
        Pill("Réserve carburant",    warn.fuelLow,              PillStyle.Warn)
        Pill("Ceinture conducteur",   warn.driverBelt,           PillStyle.Err)
        Pill("Ceinture passager",   warn.passengerBelt,        PillStyle.Err)
        Pill("Airbag passager désactivé", warn.passengerAirbagOff,   PillStyle.Warn)
        Pill("ABS",              warn.absActive,            PillStyle.Ok)
        Pill("ESP en action",     warn.espInProgress,        PillStyle.On)
        Pill("ESP désactivé",    warn.espInactivated,       PillStyle.Warn)
        Pill("Service",        warn.serviceExclamation,   PillStyle.Err)
        Pill("Pression d'huile",   warn.oilPressureAlert,     PillStyle.Err)
        Pill("Niveau d'huile",     warn.oilLevelAlert,        PillStyle.Err)
        Pill("Liquide de refroidissement",    warn.coolantLevelAlert,    PillStyle.Err)
        Pill("T° moteur maximale",    warn.coolantTempMax,       PillStyle.Err)
        Pill("T° huile maximale",     warn.oilTempMax,           PillStyle.Err)
        Pill("Liquide de frein",       warn.brakeFluidAlert,      PillStyle.Err)
        Pill("Pression des pneus",    warn.tyrePressureLow,      PillStyle.Warn)
        Pill("FAP colmaté",      warn.fapClogged,           PillStyle.Warn)
        Pill("Zone rouge",       warn.maxRpm1,              PillStyle.Warn)
        Pill("Feux de détresse",         warningsOn,                PillStyle.Warn)
    }
}
