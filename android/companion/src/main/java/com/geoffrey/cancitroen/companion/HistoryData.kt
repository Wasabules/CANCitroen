package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.shared.TelemetryFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Une grandeur suivie, avec ses colonnes dans les CSV reçus (échantillons à 5 s, agrégats). */
enum class Metric(val label: String, val unit: String, val sampleColumn: String, val aggregateColumn: String, val decimals: Int) {
    SPEED("Vitesse", "km/h", TelemetryFormat.Samples.SPEED, TelemetryFormat.Aggregates.SPEED, 0),
    RPM("Régime", "tr/min", TelemetryFormat.Samples.RPM, TelemetryFormat.Aggregates.RPM, 0),
    FUEL("Jauge", "%", TelemetryFormat.Samples.FUEL_PCT, TelemetryFormat.Aggregates.FUEL_PCT, 0),
    COOLANT("Eau", "°C", TelemetryFormat.Samples.COOLANT, TelemetryFormat.Aggregates.COOLANT, 0),
    OIL("Huile", "°C", TelemetryFormat.Samples.OIL, TelemetryFormat.Aggregates.OIL, 0),
    OUTSIDE("Extérieur", "°C", TelemetryFormat.Samples.OUTSIDE, TelemetryFormat.Aggregates.OUTSIDE, 0),
    CONSUMPTION("Conso", "L/100", TelemetryFormat.Samples.CONS, TelemetryFormat.Aggregates.CONS, 1),
}

/** Un graphique : une ou plusieurs courbes de même unité. [fixedMin]/[fixedMax] : échelle imposée. */
enum class ChartKind(val title: String, val metrics: List<Metric>, val fixedMin: Double? = null, val fixedMax: Double? = null) {
    FUEL("Carburant", listOf(Metric.FUEL), 0.0, 100.0),
    TEMPERATURES("Températures", listOf(Metric.COOLANT, Metric.OIL, Metric.OUTSIDE)),
    SPEED("Vitesse", listOf(Metric.SPEED), 0.0),
    RPM("Régime", listOf(Metric.RPM), 0.0),
    CONSUMPTION("Consommation", listOf(Metric.CONSUMPTION), 0.0),
}

/** Une courbe : instants (ms) croissants et valeurs ; NaN marque une coupure (voiture arrêtée). */
class Series(val ts: LongArray, val values: DoubleArray) {
    val size get() = ts.size

    fun lastValue(): Double? = (size - 1 downTo 0).firstOrNull { !values[it].isNaN() }?.let { values[it] }

    /** Le point mesuré le plus proche de [at], à moins de [tolerance] ms ; null sinon. */
    fun valueNear(at: Long, tolerance: Long): Double? {
        var best = -1
        for (i in 0 until size) {
            if (values[i].isNaN()) continue
            if (best < 0 || kotlin.math.abs(ts[i] - at) < kotlin.math.abs(ts[best] - at)) best = i
        }
        return best.takeIf { it >= 0 && kotlin.math.abs(ts[it] - at) <= tolerance }?.let { values[it] }
    }

    /**
     * Ramené à [buckets] tranches régulières de [from, to] : la moyenne de chacune, NaN si elle est
     * vide. Une longue période reste lisible et légère à dessiner ; un trou reste un trou.
     */
    fun downsampled(from: Long, to: Long, buckets: Int): Series {
        if (to <= from || buckets <= 0) return Series(LongArray(0), DoubleArray(0))
        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        val width = (to - from).toDouble() / buckets
        for (i in 0 until size) {
            if (values[i].isNaN() || ts[i] < from || ts[i] > to) continue
            val bucket = ((ts[i] - from) / width).toInt().coerceIn(0, buckets - 1)
            sums[bucket] += values[i]
            counts[bucket]++
        }
        return Series(
            LongArray(buckets) { from + ((it + 0.5) * width).toLong() },
            DoubleArray(buckets) { if (counts[it] == 0) Double.NaN else sums[it] / counts[it] },
        )
    }
}

/** Accumule des points sans boîtes `Long`/`Double` : une journée fait des milliers de lignes. */
class SeriesBuilder {
    private var ts = LongArray(1024)
    private var values = DoubleArray(1024)
    private var size = 0

    fun add(at: Long, value: Double) {
        if (size == ts.size) {
            ts = ts.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        ts[size] = at
        values[size] = value
        size++
    }

    /** Triée par instant : les fichiers des jours et les agrégats arrivent dans le désordre. */
    fun build(): Series {
        val order = (0 until size).sortedBy { ts[it] }
        return Series(LongArray(size) { ts[order[it]] }, DoubleArray(size) { values[order[it]] })
    }
}

/** Le détail à 5 s d'une période, colonne par colonne ; NaN pour une valeur absente. */
class Detail(val ts: LongArray, val speed: DoubleArray, val rpm: DoubleArray, val cons: DoubleArray) {
    val size get() = ts.size
}

/** Accumule le détail ligne à ligne, puis le trie : les fichiers des jours arrivent dans le désordre. */
class DetailBuilder {
    private val ts = SeriesBuilder()
    private val speed = SeriesBuilder()
    private val rpm = SeriesBuilder()
    private val cons = SeriesBuilder()

    fun add(at: Long, speedKmh: Double?, rpmValue: Double?, consL100: Double?) {
        ts.add(at, 0.0)
        speed.add(at, speedKmh ?: Double.NaN)
        rpm.add(at, rpmValue ?: Double.NaN)
        cons.add(at, consL100 ?: Double.NaN)
    }

    fun build(): Detail {
        val s = speed.build()
        return Detail(s.ts, s.values, rpm.build().values, cons.build().values)
    }
}

class TripRow(
    val startTs: Long,
    val endTs: Long?,
    val km: Double?,
    val avgSpeed: Double?,
    val maxSpeed: Double?,
    val cons: Double?,
    val fuelStart: Int?,
    val fuelEnd: Int?,
)

class RefuelRow(val ts: Long, val liters: Double?, val cost: Double?, val price: Double?, val origin: String)

/** Lecture des CSV de l'Atoto (`;`, virgule décimale), sans Android : testée en JVM. */
object HistoryCsv {
    fun number(text: String?): Double? = text?.trim()?.takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()

    /**
     * Ajoute aux [into] les valeurs de [csv] comprises dans [from, to]. [aggregates] : colonnes des
     * agrégats, dont un point n'est gardé que si le détail ([detail], trié) ne couvre pas sa tranche.
     * [times] reçoit l'instant de chaque ligne retenue, quelles que soient ses valeurs ; [rows],
     * vitesse, régime et conso de chaque ligne, pour l'analyse de conduite.
     */
    fun collect(
        csv: String,
        from: Long,
        to: Long,
        into: Map<Metric, SeriesBuilder>,
        aggregates: Boolean = false,
        detail: LongArray? = null,
        times: SeriesBuilder? = null,
        rows: DetailBuilder? = null,
    ) {
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) return
        val header = lines.next().split(';')
        val tsColumn = header.indexOf(TelemetryFormat.Samples.TS).takeIf { it >= 0 } ?: return
        val durationColumn = header.indexOf(TelemetryFormat.Aggregates.DURATION)
        val columns = into.keys.associateWith { header.indexOf(if (aggregates) it.aggregateColumn else it.sampleColumn) }
        while (lines.hasNext()) {
            val fields = lines.next().split(';')
            val at = fields.getOrNull(tsColumn)?.toLongOrNull() ?: continue
            if (at < from || at > to) continue
            if (aggregates && detail != null) {
                val length = (fields.getOrNull(durationColumn)?.toLongOrNull() ?: 60) * 1000
                if (covers(detail, at, at + length)) continue
            }
            times?.add(at, 0.0)
            rows?.add(
                at,
                number(fields.getOrNull(columns[Metric.SPEED] ?: -1)),
                number(fields.getOrNull(columns[Metric.RPM] ?: -1)),
                number(fields.getOrNull(columns[Metric.CONSUMPTION] ?: -1)),
            )
            for ((metric, column) in columns) {
                if (column < 0) continue
                val value = number(fields.getOrNull(column)) ?: continue
                into.getValue(metric).add(at, value)
            }
        }
    }

    /** Vrai si un instant de [sorted] tombe dans [start, end[. */
    fun covers(sorted: LongArray, start: Long, end: Long): Boolean {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < start) low = mid + 1 else high = mid
        }
        return low < sorted.size && sorted[low] < end
    }

    fun trips(csv: String): List<TripRow> {
        val t = TelemetryFormat.Trips
        return rows(csv).mapNotNull { row ->
            TripRow(
                startTs = date(row[t.START]) ?: return@mapNotNull null,
                endTs = date(row[t.END]),
                km = number(row[t.DISTANCE]),
                avgSpeed = number(row[t.SPEED_AVG]),
                maxSpeed = number(row[t.SPEED_MAX]),
                cons = number(row[t.CONS]),
                fuelStart = number(row[t.FUEL_START])?.toInt(),
                fuelEnd = number(row[t.FUEL_END])?.toInt(),
            )
        }.sortedBy { it.startTs }
    }

    fun refuels(csv: String): List<RefuelRow> {
        val r = TelemetryFormat.Refuels
        return rows(csv).mapNotNull { row ->
            RefuelRow(
                ts = date(row[r.DATE]) ?: return@mapNotNull null,
                liters = number(row[r.LITERS]),
                cost = number(row[r.COST]),
                price = number(row[r.PRICE]),
                origin = row[r.ORIGIN].orEmpty(),
            )
        }.sortedBy { it.ts }
    }

    /** Chaque ligne, ses valeurs rangées par nom de colonne : l'ordre des colonnes peut changer. */
    private fun rows(csv: String): List<Map<String, String>> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split(';') ?: return emptyList()
        return lines.drop(1).map { line ->
            val fields = line.split(';')
            header.indices.associate { header[it] to fields.getOrElse(it) { "" } }
        }
    }

    private fun date(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat(TelemetryFormat.MINUTE_PATTERN, Locale.FRANCE).parse(text.trim())?.time
        } catch (_: ParseException) {
            null
        }
    }
}

/** Combien de temps le détail à 5 s reste sur le téléphone. */
enum class Retention(val label: String, val days: Int?) {
    DAYS_30("30 j", 30),
    DAYS_90("90 j", 90),
    YEAR("1 an", 365),
    ALL("Tout", null),
    ;

    /** Premier jour gardé, au format des noms de fichier ; null si tout est gardé. */
    fun firstKeptDay(now: Long): String? {
        val days = days ?: return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.time)
    }

    /** Début de la période gardée, en ms ; 0 si tout est gardé. */
    fun startMs(now: Long): Long = firstKeptDay(now)?.let {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it)?.time
    } ?: 0L

    companion object {
        /** Les fichiers de jours antérieurs à [firstKeptDay], y compris les copies « (1) » d'une réinstallation. */
        fun expired(names: List<String>, firstKeptDay: String): List<String> = names.filter { name ->
            val day = DAY.find(name)?.value ?: return@filter false
            day < firstKeptDay
        }

        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
