package com.geoffrey.cancitroen.history

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import com.geoffrey.cancitroen.shared.TelemetryFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export vers Téléchargements/CANCitroen : survit à une désinstallation.
 * CSV au format d'un tableur français (`;` et virgule décimale) + copie de la base.
 */
object DataExport {
    const val FOLDER = "CANCitroen"
    private const val CSV_MIME = "text/csv"
    private const val DB_MIME = "application/octet-stream"

    private fun date(ts: Long?) = ts?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(Date(it)) }.orEmpty()
    private fun dateSeconds(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date(ts))
    private fun num(v: Number?, decimals: Int = 2) = v?.let { "%.${decimals}f".format(Locale.FRANCE, it.toDouble()) }.orEmpty()

    fun refuelsCsv(refuels: List<RefuelEvent>): String = buildString {
        append(TelemetryFormat.Refuels.HEADER).append('\n')
        for (r in refuels) {
            val origin = when {
                r.manual -> "manuel"
                r.userEntered -> "ticket"
                else -> "estimation"
            }
            append(listOf(
                date(r.ts), r.fuelPctBefore, r.fuelPctAfter, num(r.litersAdded), num(r.totalCost),
                num(r.pricePerLiter, 3), num(r.odoKm, 1), origin,
            ).joinToString(";")).append('\n')
        }
    }

    fun tripsCsv(trips: List<Trip>): String = buildString {
        append(TelemetryFormat.Trips.HEADER).append('\n')
        for (t in trips) {
            append(listOf(
                date(t.startTs), date(t.endTs), num(t.distanceKm, 1), num(t.avgSpeedKmh, 0), num(t.maxSpeedKmh, 0),
                num(t.avgConsL100, 1), t.startFuelPct ?: "", t.endFuelPct ?: "",
            ).joinToString(";")).append('\n')
        }
    }

    /** Le numéro identifie la ligne : il permet de dédoublonner après un transfert interrompu. */
    fun samplesCsv(samples: List<VehicleSample>): String = buildString {
        append(TelemetryFormat.Samples.HEADER).append('\n')
        for (s in samples) {
            append(listOf(
                s.id, dateSeconds(s.ts), s.ts, s.tripId ?: "", num(s.speedKmh, 1), num(s.rpm, 0), s.fuelPct ?: "",
                num(s.fuelLiters), s.rangeKm ?: "", s.tCoolant ?: "", s.tOil ?: "", s.tExt ?: "",
                s.oilLevelPct ?: "", num(s.odoKm, 1), num(s.fuelInstL100, 1),
            ).joinToString(";")).append('\n')
        }
    }

    fun aggregatesCsv(aggregates: List<AggregatedSample>): String = buildString {
        append(TelemetryFormat.Aggregates.HEADER).append('\n')
        for (a in aggregates) {
            append(listOf(
                dateSeconds(a.ts), a.ts, a.bucketMs / 1000, a.count, num(a.speedAvg, 1), num(a.speedMax, 1), num(a.rpmAvg, 0),
                num(a.rpmMax, 0), a.fuelPct ?: "", num(a.fuelLiters), a.tCoolantAvg ?: "", a.tCoolantMax ?: "",
                a.tOilAvg ?: "", a.tOilMax ?: "", a.tExtAvg ?: "", a.tExtMax ?: "", a.oilLevelPct ?: "",
                num(a.fuelInstAvg, 1), num(a.odoKm, 1),
            ).joinToString(";")).append('\n')
        }
    }

    /** Renvoie le nombre de fichiers écrits. */
    suspend fun exportAll(context: Context): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val db = HistoryDatabase.get(context)
        val dao = db.historyDao()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date())
        resolver.saveDownload("pleins-$stamp.csv", CSV_MIME) { it.write(refuelsCsv(dao.allRefuelsOnce()).toByteArray()) }
        resolver.saveDownload("trajets-$stamp.csv", CSV_MIME) { it.write(tripsCsv(dao.allTripsOnce()).toByteArray()) }

        // Le WAL intégré au fichier, puis la transaction tient les écritures à
        // l'écart le temps de la copie (au pire quelques secondes sans échantillon).
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        db.withTransaction {
            resolver.saveDownload("historique-$stamp.db", DB_MIME) { out ->
                context.getDatabasePath("history.db").inputStream().use { it.copyTo(out) }
            }
        }
        3
    }

    private suspend fun ContentResolver.saveDownload(name: String, mime: String, write: suspend (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Téléchargements inaccessible")
        try {
            val out = openOutputStream(uri) ?: error("Écriture impossible : $name")
            out.use { write(it) }
            update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            delete(uri, null, null)
            throw e
        }
    }
}
