package com.geoffrey.cancitroen.companion

import android.content.Context
import com.geoffrey.cancitroen.shared.LinkProtocol
import com.geoffrey.cancitroen.shared.TelemetryFormat
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** Réglages et avancement du transfert, privés à l'app. */
class TransferPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("transfert", Context.MODE_PRIVATE)

    var manualIp: String
        get() = prefs.getString("adresse", "").orEmpty()
        set(value) = prefs.edit().putString("adresse", value).apply()
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit().putBoolean("auto", value).apply()
    var lastAttemptMs: Long
        get() = prefs.getLong("dernier_essai", 0)
        set(value) = prefs.edit().putLong("dernier_essai", value).apply()
    var lastResult: String
        get() = prefs.getString("dernier_resultat", "").orEmpty()
        set(value) = prefs.edit().putString("dernier_resultat", value).apply()
    var lastCheck: String
        get() = prefs.getString("derniere_verification", "").orEmpty()
        set(value) = prefs.edit().putString("derniere_verification", value).apply()
    var lastSampleId: Long
        get() = prefs.getLong("dernier_echantillon", 0)
        // commit : l'envoi de la confirmation à l'Atoto suit et l'autorise à agréger.
        set(value) { prefs.edit().putLong("dernier_echantillon", value).commit() }

    /** Date du premier échantillon reçu ; 0 si aucun. */
    var firstSampleTs: Long
        get() = prefs.getLong("premier_echantillon", 0)
        set(value) = prefs.edit().putLong("premier_echantillon", value).apply()

    /** Adresse Bluetooth de la voiture, apprise au premier trajet suivi ; vide tant qu'inconnue. */
    var carBluetooth: String
        get() = prefs.getString("bluetooth_voiture", "").orEmpty()
        set(value) = prefs.edit().putString("bluetooth_voiture", value).apply()

    /** Dernier appareil Bluetooth connecté, et quand : la voiture se reconnaît après coup. */
    var lastBluetooth: String
        get() = prefs.getString("dernier_bluetooth", "").orEmpty()
        set(value) = prefs.edit().putString("dernier_bluetooth", value).apply()
    var lastBluetoothMs: Long
        get() = prefs.getLong("dernier_bluetooth_ms", 0)
        set(value) = prefs.edit().putLong("dernier_bluetooth_ms", value).apply()

    /** Carburant de la voiture, pour le CO₂ des trajets. */
    var fuelType: FuelType
        get() = FuelType.entries.firstOrNull { it.name == prefs.getString("carburant", null) } ?: FuelType.PETROL
        set(value) = prefs.edit().putString("carburant", value.name).apply()

    /** La position GPS de l'Atoto vient avec le résumé : carte dans l'app et Home Assistant. Choix explicite. */
    var shareCarPosition: Boolean
        get() = prefs.getBoolean("position_voiture", false)
        set(value) = prefs.edit().putBoolean("position_voiture", value).apply()

    /** Durée de conservation du détail sur le téléphone ; tout par défaut, comme avant le réglage. */
    var retention: Retention
        get() = Retention.entries.firstOrNull { it.name == prefs.getString("conservation", null) } ?: Retention.ALL
        set(value) = prefs.edit().putString("conservation", value.name).apply()

    fun snapshotVersion(name: String): String? = prefs.getString("version_$name", null)
    fun saveSnapshotVersion(name: String, version: String?) = prefs.edit().putString("version_$name", version).apply()

    val token: String? get() = prefs.getString("jeton", null)
    val pin: ByteArray? get() = prefs.getString("empreinte", null)?.let { hex -> ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    val paired: Boolean get() = token != null && pin != null

    fun savePairing(token: String, pin: ByteArray) {
        // Un autre Atoto ou une base neuve : on repart de ce qu'il dira.
        prefs.edit()
            .putString("jeton", token)
            .putString("empreinte", pin.joinToString("") { "%02x".format(it) })
            .putLong("dernier_echantillon", 0)
            .commit()
    }

    fun forgetPairing() {
        prefs.edit().remove("jeton").remove("empreinte").commit()
    }
}

/** Associe le téléphone et récupère ce qui manque. Bloquant : hors du thread principal. */
object TelemetrySync {
    /** [ok] : le transfert est allé au bout. */
    class Outcome(val reachedAtoto: Boolean, val message: String, val ok: Boolean = false)

    sealed interface Poll {
        class Answered(val summary: String, val route: AtotoRoute) : Poll
        /** Aucune voie ne mène à l'Atoto associé : hors de la voiture, ou Wi-Fi pas encore monté. */
        data object Absent : Poll
        /** L'Atoto répond mais refuse : inutile d'insister pendant cette session. */
        class Refused(val reason: String) : Poll
    }

    /**
     * Une interrogation légère de l'Atoto, pour le suivi : son résumé, sans rien écrire.
     * [preferred], la voie qui a répondu la dernière fois, est essayée d'abord.
     */
    fun poll(context: Context, preferred: AtotoRoute?): Poll {
        val prefs = TransferPrefs(context)
        val token = prefs.token
        val pin = prefs.pin
        if (token == null || pin == null) return Poll.Refused("téléphone non associé")
        val others = AtotoClient.routes(context, prefs.manualIp).filter { it.label != preferred?.label }
        for (route in listOfNotNull(preferred) + others) {
            val answer = try {
                AtotoClient(route, pin, token).request("GET", summaryPath(context))
            } catch (_: IOException) {
                // Certificat inconnu compris : un autre appareil, rien ne lui a été envoyé.
                continue
            }
            return when (answer.status) {
                200 -> Poll.Answered(answer.body.toString(Charsets.UTF_8), route)
                401 -> Poll.Refused("l'Atoto ne reconnaît plus ce téléphone, à associer de nouveau")
                403 -> Poll.Refused("transfert désactivé dans CANCitroen sur l'Atoto")
                else -> Poll.Refused("réponse ${answer.status} de l'Atoto")
            }
        }
        return Poll.Absent
    }

    private const val BATCH = LinkProtocol.MAX_BATCH
    private val running = AtomicBoolean(false)

    fun pair(context: Context, typedCode: String): String {
        val code = TransferProtocol.normalizeCode(typedCode)
        if (code.isEmpty()) return "Saisis le code affiché par l'Atoto."
        val prefs = TransferPrefs(context)
        var failure = "Atoto introuvable : Android Auto est-il connecté ?"
        for (route in AtotoClient.routes(context, prefs.manualIp)) {
            try {
                val paired = AtotoClient(route, null, null).pair(code)
                    ?: return "Code refusé. Vérifie le code, et qu'aucun téléphone n'est déjà associé."
                prefs.savePairing(paired.first, paired.second)
                return "Téléphone associé."
            } catch (e: IOException) {
                failure = "Pas de réponse (${route.label}) : ${e.message}"
            }
        }
        return failure
    }

    /**
     * [stopped] interrompt entre deux lots : ce qui est déjà écrit reste valable. Sans
     * [snapshots], seulement les échantillons neufs : léger, pour le suivi toutes les 30 s ;
     * trajets, pleins et agrégats, que l'Atoto recalcule à chaque demande, attendent le tour complet.
     */
    fun run(context: Context, snapshots: Boolean = true, stopped: () -> Boolean = { false }): Outcome = guarded(context) { prefs ->
        withAtoto(context, prefs) { client, status ->
            val files = TelemetryFiles(context)
            val received = fetch(context, client, status, files, prefs, stopped, forceSnapshots = false, snapshots = snapshots)
            if (snapshots) purge(context, files, prefs)
            if (stopped()) {
                Outcome(true, "${samples(received)}, transfert à reprendre.")
            } else {
                Outcome(true, "OK, ${samples(received)} reçu${if (received > 1) "s" else ""}.", ok = true)
            }
        }
    }

    /** Le résumé, avec la position de la voiture seulement si l'utilisateur l'a choisi. */
    fun summaryPath(context: Context): String =
        if (TransferPrefs(context).shareCarPosition) "${LinkProtocol.SUMMARY}?${LinkProtocol.WITH_POSITION}=1" else LinkProtocol.SUMMARY

    /**
     * Transfert normal, puis contrôle complet des fichiers : réclame à l'Atoto ce qui manque et
     * qu'il a encore, répare les fichiers et liste dans `manques.csv` ce dont le détail est perdu.
     * Sur demande seulement : un fichier déplacé sur le PC ressemble à un fichier perdu.
     */
    fun verify(context: Context): Outcome = guarded(context) { prefs ->
        withAtoto(context, prefs) { client, status ->
            val files = TelemetryFiles(context)
            val received = fetch(context, client, status, files, prefs, { false }, forceSnapshots = true)
            val recovered = recover(client, files, prefs)
            purge(context, files, prefs)
            val gaps = reportGaps(files, prefs)
            Outcome(true, verdict(received, recovered, gaps), ok = true)
        }
    }

    private class Recovered(val count: Int, val days: Set<String>)

    private fun recover(client: AtotoClient, files: TelemetryFiles, prefs: TransferPrefs): Recovered {
        val answer = client.request("GET", LinkProtocol.INVENTORY)
        if (answer.status != 200) throw IOException("inventaire : ${answer.status}")
        val held = TransferProtocol.parseRanges(answer.body.toString(Charsets.UTF_8))
        val present = LongArrayBuilder()
        for (name in files.sampleFiles()) {
            val text = files.read(name) ?: continue
            TransferProtocol.validLines(text).forEach { present.add(it.substringBefore(';').toLong()) }
        }
        var count = 0
        val days = sortedSetOf<String>()
        for (range in TransferProtocol.missingRanges(held, present.sorted(), prefs.lastSampleId)) {
            var after = range.first - 1
            while (after < range.last) {
                val batch = client.request("GET", "${LinkProtocol.SAMPLES}?after=$after&upto=${range.last}&limit=$BATCH")
                if (batch.status != 200) throw IOException("échantillons : ${batch.status}")
                val rows = batch.headers[COUNT]?.toIntOrNull() ?: throw IOException("lot illisible")
                if (rows == 0) break
                days += files.appendSamples(client.csv(batch))
                count += rows
                after = batch.headers[LAST_ID]?.toLongOrNull() ?: throw IOException("lot illisible")
            }
        }
        return Recovered(count, days)
    }

    /** Remet les fichiers en ordre, puis compare leur contenu à ce que l'Atoto a résumé. */
    private fun reportGaps(files: TelemetryFiles, prefs: TransferPrefs): List<TransferProtocol.Gap> {
        val times = LongArrayBuilder()
        for (name in files.sampleFiles()) {
            val text = files.read(name) ?: continue
            TransferProtocol.repairedDay(text)?.let { files.replace(name, it) }
            TransferProtocol.validLines(text).distinctBy { it.substringBefore(';') }.forEach { line ->
                line.split(';').getOrNull(2)?.toLongOrNull()?.let(times::add)
            }
        }
        val sorted = times.sorted()
        if (prefs.firstSampleTs == 0L) sorted.firstOrNull()?.let { prefs.firstSampleTs = it }
        // Ce que la conservation a retiré n'est pas un manque.
        val first = prefs.firstSampleTs.takeIf { it > 0 }?.let { maxOf(it, prefs.retention.startMs(System.currentTimeMillis())) }
            ?: Long.MAX_VALUE
        val buckets = TransferProtocol.parseBuckets(files.read(AGGREGATES).orEmpty())
        val gaps = TransferProtocol.detailGaps(buckets, sorted, first)
        if (gaps.isEmpty()) {
            files.delete(GAPS)
        } else {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            files.replace(GAPS, buildString {
                append("debut;fin;echantillons_attendus;echantillons_presents\n")
                for (gap in gaps) {
                    append(format.format(Date(gap.startTs))).append(';').append(format.format(Date(gap.endTs)))
                    append(';').append(gap.expected).append(';').append(gap.present).append('\n')
                }
            })
        }
        return gaps
    }

    /** Retire le détail plus ancien que la durée de conservation. Renvoie le nombre de jours retirés. */
    fun purge(context: Context, files: TelemetryFiles = TelemetryFiles(context), prefs: TransferPrefs = TransferPrefs(context)): Int {
        val firstKept = prefs.retention.firstKeptDay(System.currentTimeMillis()) ?: return 0
        val expired = Retention.expired(files.sampleFiles(), firstKept)
        expired.forEach(files::delete)
        if (expired.isNotEmpty()) CompanionLog.add(context, "Conservation : ${expired.size} jour(s) de détail retiré(s) du téléphone.")
        return expired.size
    }

    private fun verdict(received: Int, recovered: Recovered, gaps: List<TransferProtocol.Gap>): String = buildString {
        if (recovered.count == 0 && gaps.isEmpty()) {
            append("Vérifié : rien ne manque.")
        } else {
            if (recovered.count > 0) {
                append("Récupéré : ${samples(recovered.count)} (${recovered.days.joinToString(", ")}). ")
            } else {
                append("Rien à récupérer. ")
            }
            if (gaps.isNotEmpty()) {
                val periods = if (gaps.size > 1) "${gaps.size} périodes" else "1 période"
                append("Détail perdu sur $periods : voir manques.csv, le résumé reste dans agregats.csv.")
            } else {
                append("Plus rien ne manque.")
            }
        }
        if (received > 0) append(" Nouveau : ${samples(received)}.")
    }

    private fun samples(count: Int) = if (count > 1) "$count échantillons" else "$count échantillon"

    private fun guarded(context: Context, block: (TransferPrefs) -> Outcome): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome(true, "Transfert déjà en cours.", ok = true)
        val prefs = TransferPrefs(context)
        val outcome = try {
            block(prefs)
        } catch (e: RuntimeException) {
            // Stockage indisponible, par exemple : à afficher, pas à faire planter l'app.
            Outcome(false, "Erreur : ${e.message}")
        } finally {
            running.set(false)
        }
        val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        prefs.lastResult = "$at · ${outcome.message}"
        return outcome
    }

    private fun withAtoto(
        context: Context,
        prefs: TransferPrefs,
        block: (AtotoClient, JSONObject) -> Outcome,
    ): Outcome {
        val token = prefs.token
        val pin = prefs.pin
        if (token == null || pin == null) return Outcome(false, "Téléphone non associé.")
        var failure = "Atoto introuvable."
        for (route in AtotoClient.routes(context, prefs.manualIp)) {
            val client = AtotoClient(route, pin, token)
            val answer = try {
                client.request("GET", LinkProtocol.STATUS)
            } catch (e: SSLException) {
                // Rien n'a été envoyé : la poignée de main s'arrête avant le jeton.
                return Outcome(false, "Un appareil répond (${route.label}), mais ce n'est pas l'Atoto associé.")
            } catch (e: IOException) {
                failure = "Atoto introuvable (${route.label}) : ${e.message}"
                continue
            }
            when (answer.status) {
                200 -> Unit
                401 -> return Outcome(true, "L'Atoto ne reconnaît plus ce téléphone : à associer de nouveau.")
                else -> return Outcome(true, "Transfert fermé par l'Atoto (${answer.status}).")
            }
            return try {
                block(client, JSONObject(answer.body.toString(Charsets.UTF_8)))
            } catch (e: IOException) {
                Outcome(true, "Interrompu : ${e.message}")
            } catch (e: JSONException) {
                Outcome(true, "Réponse illisible : ${e.message}")
            }
        }
        return Outcome(false, failure)
    }

    private fun fetch(
        context: Context,
        client: AtotoClient,
        status: JSONObject,
        files: TelemetryFiles,
        prefs: TransferPrefs,
        stopped: () -> Boolean,
        forceSnapshots: Boolean,
        snapshots: Boolean = true,
    ): Int {
        var confirmed = status.getLong("ackId")
        var after = TransferProtocol.resumeAfter(prefs.lastSampleId, confirmed, status.getLong("lastId"))
        var received = 0
        while (!stopped()) {
            val answer = client.request("GET", "${LinkProtocol.SAMPLES}?after=$after&limit=$BATCH")
            if (answer.status != 200) throw IOException("échantillons : ${answer.status}")
            val count = answer.headers[COUNT]?.toIntOrNull() ?: throw IOException("lot illisible")
            if (count == 0) break
            val csv = client.csv(answer)
            files.appendSamples(csv)
            noteFirstSample(prefs, csv)
            after = answer.headers[LAST_ID]?.toLongOrNull() ?: throw IOException("lot illisible")
            prefs.lastSampleId = after
            received += count
            // Après l'écriture : l'Atoto peut alors agréger ces échantillons.
            client.request("POST", "${LinkProtocol.ACK}?id=$after")
            confirmed = after
            if (count < BATCH) break
        }
        if (stopped()) return received
        // Confirmation perdue la fois précédente : rien de neuf, mais l'Atoto doit le savoir.
        if (confirmed < after) client.request("POST", "${LinkProtocol.ACK}?id=$after")
        if (!snapshots) return received
        for ((path, name) in SNAPSHOTS) {
            // Fichier absent : on ne peut plus se fier à la version retenue.
            val version = prefs.snapshotVersion(name).takeUnless { forceSnapshots || !files.exists(name) }
            val answer = client.request("GET", path, version)
            if (answer.status == 304) continue
            if (answer.status != 200) throw IOException("$name : ${answer.status}")
            files.replace(name, client.csv(answer))
            prefs.saveSnapshotVersion(name, answer.headers["etag"])
        }
        val summary = client.request("GET", summaryPath(context))
        if (summary.status == 200) HomeAssistant.saveSummary(context, summary.body.toString(Charsets.UTF_8))
        return received
    }

    /** Le plus ancien du lot, pas le premier : l'horloge de l'autoradio peut être réglée en route. */
    private fun noteFirstSample(prefs: TransferPrefs, csv: String) {
        val oldest = csv.lineSequence().drop(1).mapNotNull { it.split(';').getOrNull(2)?.toLongOrNull() }.minOrNull() ?: return
        if (prefs.firstSampleTs == 0L || oldest < prefs.firstSampleTs) prefs.firstSampleTs = oldest
    }

    /** Évite des centaines de milliers d'objets `Long` sur une longue histoire. */
    private class LongArrayBuilder {
        private var items = LongArray(4096)
        private var size = 0

        fun add(value: Long) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = value
        }

        fun sorted(): LongArray = items.copyOf(size).also { it.sort() }
    }

    private const val AGGREGATES = TelemetryFormat.AGGREGATES_FILE
    private const val GAPS = TelemetryFormat.GAPS_FILE
    // En-têtes lus en minuscules (TransferProtocol.parseAnswer).
    private val COUNT = LinkProtocol.HEADER_COUNT.lowercase()
    private val LAST_ID = LinkProtocol.HEADER_LAST_ID.lowercase()

    // Un trajet ou un ticket peut changer après coup : remplacés entiers, s'ils ont changé.
    private val SNAPSHOTS = listOf(
        LinkProtocol.TRIPS to TelemetryFormat.TRIPS_FILE,
        LinkProtocol.REFUELS to TelemetryFormat.REFUELS_FILE,
        LinkProtocol.AGGREGATES to AGGREGATES,
    )
}
