package com.geoffrey.cancitroen.history

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
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
    private fun num(v: Number?, decimals: Int = 2) = v?.let { "%.${decimals}f".format(Locale.FRANCE, it.toDouble()) }.orEmpty()

    fun refuelsCsv(refuels: List<RefuelEvent>): String = buildString {
        append("date;jauge_avant;jauge_apres;litres;montant_eur;prix_litre_eur;odometre_km;origine\n")
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
        append("debut;fin;distance_km;vitesse_moy_kmh;vitesse_max_kmh;conso_moy_l100;jauge_debut;jauge_fin\n")
        for (t in trips) {
            append(listOf(
                date(t.startTs), date(t.endTs), num(t.distanceKm, 1), num(t.avgSpeedKmh, 0), num(t.maxSpeedKmh, 0),
                num(t.avgConsL100, 1), t.startFuelPct ?: "", t.endFuelPct ?: "",
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
