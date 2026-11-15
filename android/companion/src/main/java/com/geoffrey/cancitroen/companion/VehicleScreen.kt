package com.geoffrey.cancitroen.companion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Périodes proposées ; un trajet choisi dans la liste en devient une. */
enum class Period(val label: String, val ms: Long?) {
    DAY("24 h", 24L * 3_600_000L),
    WEEK("7 j", 7L * 86_400_000L),
    MONTH("30 j", 30L * 86_400_000L),
    ALL("Tout", null),
}

private const val TRIPS_SHOWN = 20
private const val REFUELS_SHOWN = 10
private const val RELOAD_MS = 30_000L
private const val TRIP_MARGIN_MS = 60_000L

/**
 * L'onglet « Voiture » : l'état reçu de l'Atoto, ses courbes sur une période, les trajets et
 * les pleins. Tout vient des fichiers déjà reçus : il marche aussi loin de la voiture.
 */
@Composable
fun VehicleTab() {
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf(Period.DAY) }
    var trip by remember { mutableStateOf<TripRow?>(null) }
    var view by remember { mutableStateOf<HistoryView?>(null) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var report by remember { mutableStateOf<DrivingReport?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, trip, reload) {
        val now = System.currentTimeMillis()
        val chosen = trip
        val from = if (chosen != null) chosen.startTs - TRIP_MARGIN_MS else period.ms?.let { now - it } ?: 0L
        val to = if (chosen != null) (chosen.endTs ?: now) + TRIP_MARGIN_MS else now
        val loaded = withContext(Dispatchers.IO) { VehicleHistory.load(context, from, to) }
        val current = withContext(Dispatchers.IO) { VehicleHistory.summary(context) }
        report = withContext(Dispatchers.Default) {
            DrivingAnalysis.analyze(loaded.detail, loaded.trips, literPrice(current), TransferPrefs(context).fuelType)
        }
        view = loaded
        summary = current
    }
    // Pendant un trajet, le résumé et le détail arrivent toutes les 30 s.
    LaunchedEffect(Unit) {
        while (true) {
            delay(RELOAD_MS)
            reload++
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("La voiture", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        val current = summary
        if (current == null) {
            Note("Rien de reçu pour l'instant : l'état arrive au premier trajet suivi, une fois le téléphone associé (onglet Réglages).")
        } else {
            SummaryTiles(current)
            PositionLine(current)
        }

        Text("Historique", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in Period.entries) {
                FilterChip(
                    selected = trip == null && period == option,
                    onClick = {
                        trip = null
                        period = option
                    },
                    label = { Text(option.label) },
                )
            }
        }
        trip?.let { Note("Trajet du ${dayTime(it.startTs)} : touche une période pour revenir.") }
        val loaded = view
        if (loaded == null) {
            Note("Chargement…")
        } else {
            Note("Touche ou fais glisser un graphique pour lire ses valeurs.")
            for (kind in ChartKind.entries) {
                Text(kind.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
                Chart(kind, loaded)
            }
            report?.let { DrivingSection(it) }
            Text("Trajets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (loaded.trips.isEmpty()) Note("Aucun trajet sur cette période.")
            val price = literPrice(summary)
            for (row in loaded.trips.asReversed().take(TRIPS_SHOWN)) {
                ListLine(
                    title = dayTime(row.startTs) + (row.endTs?.let { " · ${minutes(it - row.startTs)}" } ?: " · en cours"),
                    detail = listOfNotNull(
                        row.km?.let { "${number(it, 1)} km" },
                        row.cons?.let { "${number(it, 1)} L/100" },
                        row.avgSpeed?.let { "moy. ${number(it, 0)} km/h" },
                        TripEvents.liters(row.km, row.cons)?.let { liters -> price?.let { euros(liters * it) } },
                    ).joinToString(" · "),
                    onClick = { trip = row },
                )
            }
            if (loaded.refuels.isNotEmpty()) {
                Text("Pleins", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                for (row in loaded.refuels.asReversed().take(REFUELS_SHOWN)) {
                    ListLine(
                        title = dayTime(row.ts),
                        detail = listOfNotNull(
                            row.liters?.let { "${number(it, 1)} L" },
                            row.cost?.let { "${number(it, 2)} €" },
                            row.price?.let { "${number(it, 3)} €/L" },
                            row.origin.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryTiles(summary: JSONObject) {
    fun value(key: String): Double? = if (!summary.has(key) || summary.isNull(key)) null else summary.optDouble(key).takeUnless { it.isNaN() }
    val seen = value(F.LAST_SEEN)?.toLong()
    val tiles = listOf(
        Triple(
            "Carburant", value(F.FUEL_PCT)?.let { "${number(it, 0)} %" } ?: "–",
            listOfNotNull(value(F.FUEL_LITERS)?.let { "${number(it, 1)} L" }, value(F.RANGE)?.let { "${number(it, 0)} km" }).joinToString(" · "),
        ),
        Triple("Odomètre", value(F.ODO)?.let { "${number(it, 0)} km" } ?: "–", seen?.let { "relevé ${dayTime(it)}" }.orEmpty()),
        Triple(
            "Eau", value(F.COOLANT)?.let { "${number(it, 0)} °C" } ?: "–",
            listOfNotNull(value(F.OIL)?.let { "huile ${number(it, 0)} °C" }, value(F.OUTSIDE)?.let { "ext. ${number(it, 0)} °C" }).joinToString(" · "),
        ),
        Triple(
            "Dernier trajet", value(F.TRIP_KM)?.let { "${number(it, 1)} km" } ?: "–",
            listOfNotNull(
                value(F.TRIP_START)?.let { start -> value(F.TRIP_END)?.let { minutes((it - start).toLong()) } },
                value(F.TRIP_CONS)?.let { "${number(it, 1)} L/100" },
            ).joinToString(" · "),
        ),
        Triple(
            "Ce mois", "${number(value(F.MONTH_KM) ?: 0.0, 0)} km",
            listOfNotNull(
                value(F.MONTH_TRIPS)?.let { "${it.toInt()} trajet${if (it >= 2) "s" else ""}" },
                value(F.MONTH_COST)?.takeIf { it > 0 }?.let { "${number(it, 2)} €" },
            ).joinToString(" · "),
        ),
        Triple(
            "Dernier plein", value(F.REFUEL_LITERS)?.let { "${number(it, 1)} L" } ?: "–",
            listOfNotNull(value(F.REFUEL_TS)?.let { dayTime(it.toLong()) }, value(F.REFUEL_PRICE)?.let { "${number(it, 3)} €/L" }).joinToString(" · "),
        ),
    )
    for (pair in tiles.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, main, detail) in pair) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(main, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(detail.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Le dessin est préparé une fois par taille et par période ; le curseur ne fait que redessiner. */
@Composable
private fun Chart(kind: ChartKind, view: HistoryView) {
    val scale = LocalDensity.current.density
    var cursor by remember(view) { mutableStateOf<Long?>(null) }
    fun timeAt(x: Float, width: Int): Long {
        val left = ChartPainter.LEFT_DP * scale
        val right = width - 8 * scale
        val ratio = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return view.from + ((view.to - view.from) * ratio).toLong()
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .pointerInput(view) {
                detectTapGestures { cursor = if (cursor == null) timeAt(it.x, size.width) else null }
            }
            .pointerInput(view) {
                detectHorizontalDragGestures(
                    onDragStart = { cursor = timeAt(it.x, size.width) },
                    onDragEnd = { cursor = null },
                    onDragCancel = { cursor = null },
                ) { change, _ -> cursor = timeAt(change.position.x, size.width) }
            }
            .drawWithCache {
                val plot = size.width - (ChartPainter.LEFT_DP + 8) * scale
                val prepared = ChartPainter.prepare(kind, view.series, view.from, view.to, plot, scale)
                val latest = ChartPainter.latest(kind, view.series)
                onDrawBehind {
                    drawIntoCanvas {
                        ChartPainter.draw(it.nativeCanvas, size.width, size.height, kind, prepared, latest, view.from, view.to, scale, cursor)
                    }
                }
            },
    )
}

/** Prix du litre : dernier ticket, sinon le réglage de l'Atoto. */
fun literPrice(summary: JSONObject?): Double? = summary?.let {
    listOf(F.REFUEL_PRICE, F.PRICE_PER_LITER).firstNotNullOfOrNull { key ->
        if (it.has(key) && !it.isNull(key)) it.optDouble(key).takeUnless { v -> v.isNaN() || v <= 0 } else null
    }
}

/** Là où la voiture est garée, ou était au dernier contact ; seulement si l'utilisateur partage la position. */
@Composable
private fun PositionLine(summary: JSONObject) {
    if (!summary.has(F.LAT) || !summary.has(F.LON)) return
    val context = LocalContext.current
    val lat = summary.optDouble(F.LAT)
    val lon = summary.optDouble(F.LON)
    val at = summary.optLong(F.GPS_TS).takeIf { it > 0 }
    val accuracy = summary.optDouble(F.GPS_ACCURACY).takeUnless { it.isNaN() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull("Position", at?.let { "relevée ${dayTime(it)}" }, accuracy?.let { "à ${number(it, 0)} m près" }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val uri = Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode("Citroën C2")})")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // Aucune app de carte : rien à ouvrir.
            }
        }) { Text("Ouvrir la carte") }
    }
}

@Composable
private fun DrivingSection(report: DrivingReport) {
    Text("Conduite", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(
        listOfNotNull(
            "Roulage ${minutes(report.drivingMs)}",
            "ralenti ${minutes(report.idleMs)}",
            report.km.takeIf { it >= 0.1 }?.let { "${number(it, 1)} km" },
        ).joinToString(" · "),
        color = MaterialTheme.colorScheme.onBackground,
    )
    if (report.drivingMs > 0) {
        for (band in report.bands) {
            ShareBar(
                band.label, band.drivingMs.toDouble() / report.drivingMs,
                listOfNotNull("${number(band.km, 1)} km", band.avgCons?.let { "${number(it, 1)} L/100" }).joinToString(" · "),
            )
        }
    }
    report.avgRpm?.let { rpm ->
        Text(
            "Régime moyen en roulant ${number(rpm, 0)} tr/min" +
                (report.highRpmShare?.let { " · au-dessus de ${number(DrivingAnalysis.HIGH_RPM, 0)} : ${number(it * 100, 0)} %" } ?: ""),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
    if (report.gears.isNotEmpty()) {
        Text("Rapports engagés", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        report.gears.forEachIndexed { index, gear ->
            ShareBar(
                "${index + 1}", gear.share,
                "${number(gear.kmhPer1000, 1)} km/h à 1 000 tr/min · régime moyen ${number(gear.avgRpm, 0)} tr/min",
            )
        }
        Note("Reconnus au rapport vitesse / régime, numérotés du plus court vu sur la période : un rapport jamais tenu ne compte pas.")
    }
    report.liters?.let { liters ->
        Text(
            listOfNotNull(
                "Trajets terminés : ${number(liters, 1)} L",
                report.cost?.let(::euros),
                report.co2Kg?.let { "${number(it, 1)} kg de CO₂" },
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Une part du temps, en barre : lisible d'un coup d'œil, sans graphique. */
@Composable
private fun ShareBar(label: String, share: Double, detail: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(96.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share.toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                )
            }
            Text(
                "${number(share * 100, 0)} %",
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListLine(title: String, detail: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground)
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
