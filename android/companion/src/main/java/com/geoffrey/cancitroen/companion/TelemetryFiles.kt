package com.geoffrey.cancitroen.companion

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.geoffrey.cancitroen.shared.TelemetryFormat
import java.io.IOException

/** Les fichiers de `Documents/CANCitroen`, à récupérer sur PC sans passer par le cloud. */
class TelemetryFiles(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val folder = "${Environment.DIRECTORY_DOCUMENTS}/CANCitroen/"

    /** Un fichier par jour, sans doublon, même si le lot arrive une seconde fois. Renvoie les jours touchés. */
    fun appendSamples(csv: String): Set<String> {
        val header = csv.lineSequence().first()
        val days = TransferProtocol.splitByDay(csv)
        for ((day, lines) in days) {
            val name = sampleFile(day)
            val existing = find(name)
            val uri = existing ?: create(name)
            val text = TransferProtocol.textToAppend(existing?.let(::read), header, lines)
            val out = resolver.openOutputStream(uri, "wa") ?: throw IOException("écriture impossible : $name")
            out.use { it.write(text.toByteArray()) }
        }
        return days.keys
    }

    /** Le nouveau contenu est écrit à part : une coupure en route laisse l'ancien intact. */
    fun replace(name: String, csv: String) {
        val uri = create(name, pending = true)
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("écriture impossible : $name")
            out.use { it.write(csv.toByteArray()) }
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        delete(name)
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun exists(name: String): Boolean = find(name) != null

    fun delete(name: String) {
        find(name)?.let { resolver.delete(it, null, null) }
    }

    fun read(name: String): String? = find(name)?.let(::read)

    /** Les fichiers d'échantillons que l'app peut voir : après une réinstallation, Android lui cache les anciens. */
    fun sampleFiles(): List<String> {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val names = mutableListOf<String>()
        resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), selection, arrayOf(folder, "$SAMPLE_PREFIX%"), null)?.use {
            while (it.moveToNext()) names += it.getString(0)
        }
        return names.sorted()
    }

    private fun read(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("lecture impossible")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun find(name: String): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, arrayOf(folder, name), null)?.use {
            if (it.moveToFirst()) Uri.withAppendedPath(collection, it.getLong(0).toString()) else null
        }
    }

    private fun create(name: String, pending: Boolean = false): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: throw IOException("Documents inaccessible")
    }

    companion object {
        private const val SAMPLE_PREFIX = TelemetryFormat.SAMPLE_FILE_PREFIX
        private fun sampleFile(day: String) = TelemetryFormat.sampleFile(day)
    }
}
