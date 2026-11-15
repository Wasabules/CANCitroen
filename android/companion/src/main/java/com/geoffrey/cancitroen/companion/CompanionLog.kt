package com.geoffrey.cancitroen.companion

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal du compagnon, privé à l'app, à relire ou partager depuis l'écran : c'est ce qui
 * dit, après un trajet, ce qui s'est passé sans le téléphone sous les yeux. Jamais de jeton,
 * d'en-tête ni d'adresse complète de webhook dedans.
 */
object CompanionLog {
    private const val FILE = "journal.txt"
    private const val MAX_LINES = 400
    private const val KEPT_LINES = 300

    @Synchronized
    fun add(context: Context, message: String) {
        val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        val file = File(context.filesDir, FILE)
        try {
            file.appendText("$at  ${message.replace('\n', ' ')}\n")
            if (file.length() > MAX_LINES * 120L) {
                val lines = file.readLines()
                if (lines.size > MAX_LINES) file.writeText(lines.takeLast(KEPT_LINES).joinToString("\n", postfix = "\n"))
            }
        } catch (_: IOException) {
            // Stockage plein : le journal n'est qu'une aide, il ne doit rien arrêter.
        }
    }

    /** Les [count] dernières lignes, la plus récente en dernier. */
    @Synchronized
    fun tail(context: Context, count: Int): List<String> = try {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readLines()?.takeLast(count).orEmpty()
    } catch (_: IOException) {
        emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
