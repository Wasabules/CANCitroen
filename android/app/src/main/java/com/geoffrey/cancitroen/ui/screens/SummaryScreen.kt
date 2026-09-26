package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.vm.SummaryViewModel
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import java.util.Calendar

/**
 * Récap textuel multi-périodes : aujourd'hui / semaine / mois / année.
 * Pas de graphique — uniquement un tableau / des cards de chiffres clés.
 */
@Composable
fun SummaryScreen(
    modifier: Modifier = Modifier,
    vm: SummaryViewModel = viewModel(),
) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val refuels by vm.refuels.collectAsStateWithLifecycle()

    val periods = remember(trips, refuels) { computePeriods(trips, refuels) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "📒 Récap",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )

        if (trips.isEmpty() && refuels.isEmpty()) {
            EmptyHint()
            return@Column
        }

        // Grille 2x2 pour les 4 périodes
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PeriodCard(periods[0], modifier = Modifier.weight(1f))
            PeriodCard(periods[1], modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PeriodCard(periods[2], modifier = Modifier.weight(1f))
            PeriodCard(periods[3], modifier = Modifier.weight(1f))
        }

        // Carte odométrique global (depuis 1ère donnée connue)
        OdometerCard(trips)

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private data class PeriodSummary(
    val title: String,
    val tripCount: Int,
    val distanceKm: Double,
    val durationMin: Long,
    val avgSpeed: Double?,
    val avgConsL100: Double?,
    val refuelCount: Int,
    val totalLiters: Double,
    val totalCost: Double,
)

private fun computePeriods(
    trips: List<Trip>,
    refuels: List<RefuelEvent>,
): List<PeriodSummary> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()

    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis

    cal.timeInMillis = now
    cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val weekStart = cal.timeInMillis

    cal.timeInMillis = now
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val monthStart = cal.timeInMillis

    cal.timeInMillis = now
    cal.set(Calendar.DAY_OF_YEAR, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val yearStart = cal.timeInMillis

    return listOf(
        summary("Aujourd'hui", today, trips, refuels),
        summary("Cette semaine", weekStart, trips, refuels),
        summary("Ce mois", monthStart, trips, refuels),
        summary("Cette année", yearStart, trips, refuels),
    )
}

private fun summary(
    title: String,
    sinceMs: Long,
    trips: List<Trip>,
    refuels: List<RefuelEvent>,
): PeriodSummary {
    val periodTrips = trips.filter {
        it.startTs >= sinceMs && it.endTs != null
    }
    val periodRefuels = refuels.filter { it.ts >= sinceMs }

    val totalDist = periodTrips.sumOf { it.distanceKm ?: 0.0 }
    val totalDurationMs = periodTrips.sumOf { it.durationMs ?: 0L }
    val avgSpeed = if (totalDurationMs > 0)
        totalDist / (totalDurationMs / 3600_000.0) else null
    // Moyenne de conso pondérée par distance
    val totalConsKm = periodTrips
        .filter { it.avgConsL100 != null && (it.distanceKm ?: 0.0) > 0 }
        .sumOf { it.distanceKm!! }
    val totalLiters100 = periodTrips
        .filter { it.avgConsL100 != null && (it.distanceKm ?: 0.0) > 0 }
        .sumOf { it.avgConsL100!!.toDouble() * (it.distanceKm!! / 100.0) }
    val avgCons = if (totalConsKm > 0) totalLiters100 / (totalConsKm / 100.0) else null

    val totalLitersAdded = periodRefuels.sumOf { it.litersAdded.toDouble() }
    val totalCost = periodRefuels.sumOf { it.totalCost ?: 0.0 }

    return PeriodSummary(
        title = title,
        tripCount = periodTrips.size,
        distanceKm = totalDist,
        durationMin = totalDurationMs / 60_000,
        avgSpeed = avgSpeed,
        avgConsL100 = avgCons,
        refuelCount = periodRefuels.size,
        totalLiters = totalLitersAdded,
        totalCost = totalCost,
    )
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PeriodCard(p: PeriodSummary, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                p.title.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            StatLine("Distance", "%.1f km".format(p.distanceKm))
            StatLine("Trajets", "${p.tripCount}")
            StatLine("Conduite", formatMinutes(p.durationMin))
            StatLine("Vitesse moy",
                p.avgSpeed?.let { "%.0f km/h".format(it) } ?: "—")
            StatLine("Conso moy",
                p.avgConsL100?.let { "%.1f l/100".format(it) } ?: "—")
            StatLine("Pleins",
                if (p.refuelCount > 0)
                    "${p.refuelCount} (~${"%.0f".format(p.totalLiters)} L)"
                else "—")
            StatLine("Dépense",
                if (p.totalCost > 0) "%.2f €".format(p.totalCost) else "—")
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OdometerCard(trips: List<Trip>) {
    val firstOdo = trips.lastOrNull { it.startOdoKm != null }?.startOdoKm
    val lastOdo = trips.firstOrNull { it.endOdoKm != null }?.endOdoKm
        ?: trips.firstOrNull()?.startOdoKm
    val totalKm = if (firstOdo != null && lastOdo != null) lastOdo - firstOdo else null

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                "🚗 Compteur",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            StatLine("Compteur actuel",
                lastOdo?.let { "%.0f km".format(it) } ?: "—")
            StatLine("Compteur (1ère donnée)",
                firstOdo?.let { "%.0f km".format(it) } ?: "—")
            StatLine("Cumul depuis l'install",
                totalKm?.let { "%.0f km".format(it) } ?: "—")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📒",
                style = MaterialTheme.typography.displaySmall)
            Text("Pas encore de trajets enregistrés",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "Active le simulateur dans Réglages pour générer des données de démo, ou attends ton premier trajet réel.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatMinutes(min: Long): String {
    val h = min / 60
    val m = min % 60
    return when {
        h > 0 -> "%dh%02d".format(h, m)
        else -> "%d min".format(m)
    }
}
