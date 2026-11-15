package com.geoffrey.cancitroen.companion

import android.content.Context
import com.geoffrey.cancitroen.shared.TelemetryFormat
import org.json.JSONException
import org.json.JSONObject

/** Ce que l'écran « Voiture » affiche pour une période : les courbes, les trajets et les pleins. */
class HistoryView(
    val from: Long,
    val to: Long,
    val series: Map<Metric, Series>,
    val trips: List<TripRow>,
    val refuels: List<RefuelRow>,
    /** Seulement le détail à 5 s : l'analyse de conduite n'a pas de sens sur des moyennes. */
    val detail: Detail,
)

/**
 * Relit les fichiers reçus de l'Atoto : le détail à 5 s là où le téléphone l'a, les agrégats
 * ailleurs (avant le premier transfert, ou retiré par la conservation). Bloquant : hors du
 * thread principal.
 */
object VehicleHistory {
    private const val AGGREGATES = TelemetryFormat.AGGREGATES_FILE
    private const val TRIPS = TelemetryFormat.TRIPS_FILE
    private const val REFUELS = TelemetryFormat.REFUELS_FILE
    private const val DAY_MS = 24L * 3_600_000L

    fun load(context: Context, from: Long, to: Long): HistoryView {
        val files = TelemetryFiles(context)
        val start = maxOf(from, TransferPrefs(context).retention.startMs(System.currentTimeMillis()))
        val builders = Metric.entries.associateWith { SeriesBuilder() }
        val times = SeriesBuilder()
        val rows = DetailBuilder()
        val firstDay = HistoryDays.of(start - DAY_MS)
        val lastDay = HistoryDays.of(to + DAY_MS)
        for (name in files.sampleFiles()) {
            val day = HistoryDays.inName(name) ?: continue
            if (day < firstDay || day > lastDay) continue
            files.read(name)?.let { HistoryCsv.collect(it, start, to, builders, times = times, rows = rows) }
        }
        val detail = times.build().ts
        files.read(AGGREGATES)?.let { HistoryCsv.collect(it, start, to, builders, aggregates = true, detail = detail) }
        val trips = files.read(TRIPS)?.let(HistoryCsv::trips).orEmpty()
            .filter { (it.endTs ?: it.startTs) >= start && it.startTs <= to }
        val refuels = files.read(REFUELS)?.let(HistoryCsv::refuels).orEmpty().filter { it.ts in start..to }
        val series = builders.mapValues { it.value.build() }
        // « Tout » : l'axe commence aux premières données, pas en 1970.
        val begin = if (start > 0) start else {
            (series.values.mapNotNull { it.ts.firstOrNull() } + trips.map { it.startTs }).minOrNull() ?: (to - DAY_MS)
        }
        return HistoryView(begin, to, series, trips, refuels, rows.build())
    }

    /** Le dernier résumé reçu de l'Atoto (état actuel, dernier trajet, mois) ; null si aucun. */
    fun summary(context: Context): JSONObject? = HomeAssistant.lastSummary(context)?.let {
        try {
            JSONObject(it)
        } catch (_: JSONException) {
            null
        }
    }
}

/** Les jours des noms de fichier, comparables en texte. */
object HistoryDays {
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

    fun of(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(ms))

    fun inName(name: String): String? = DAY.find(name)?.value
}
