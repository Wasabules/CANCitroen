package com.geoffrey.cancitroen.companion

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.X509TrustManager

/** Réponse de l'Atoto : HTTP/1.0, la fin de connexion marque la fin du corps. */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** Logique du transfert, sans dépendance à Android. */
object TransferProtocol {
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024 * 1024
    private val CODE_NOISE = Regex("[^A-Za-z0-9]")
    private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun parseAnswer(raw: InputStream): HttpAnswer {
        val input = BufferedInputStream(raw)
        val head = ByteArrayOutputStream()
        while (!head.endsWithBlankLine()) {
            val byte = input.read()
            if (byte < 0) throw IOException("réponse tronquée")
            if (head.size() >= MAX_HEADER_BYTES) throw IOException("en-têtes trop longs")
            head.write(byte)
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).trim().lines()
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("réponse illisible")
        val headers = lines.drop(1).associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim()
        }
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            body.write(buffer, 0, read)
            if (body.size() > MAX_BODY_BYTES) throw IOException("réponse trop grande")
        }
        // Une connexion coupée en plein envoi ne doit pas passer pour une réponse complète.
        val expected = headers["content-length"]?.toLongOrNull()
        if (expected != null && expected != body.size().toLong()) throw IOException("réponse tronquée")
        return HttpAnswer(status, headers, body.toByteArray())
    }

    private fun ByteArrayOutputStream.endsWithBlankLine(): Boolean {
        val size = size()
        if (size < 4) return false
        val bytes = toByteArray()
        return bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() &&
            bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()
    }

    /** Le code tel qu'affiché (`ABCD-2345`) ou saisi sans soin. */
    fun normalizeCode(typed: String): String = CODE_NOISE.replace(typed, "").uppercase()

    /** Prouve la connaissance du code sans l'envoyer, pour le certificat vu par ce téléphone. */
    fun pairingProof(code: String, fingerprint: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return mac.doFinal(fingerprint).joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Numéro après lequel reprendre. L'Atoto fait foi quand le téléphone a perdu le fil ;
     * un numéro local au-delà du sien veut dire que sa base a été recréée.
     */
    fun resumeAfter(local: Long, ack: Long, last: Long): Long =
        if (local > last) ack else maxOf(local, ack)

    /** Répartit un lot par jour (2e colonne : `yyyy-MM-dd HH:mm:ss`), en gardant l'ordre. */
    fun splitByDay(csv: String): Map<String, List<String>> {
        val days = LinkedHashMap<String, MutableList<String>>()
        csv.lineSequence().drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val day = line.split(';').getOrNull(1).orEmpty().take(10)
            if (!DAY.matches(day)) throw IOException("ligne illisible")
            days.getOrPut(day) { mutableListOf() }.add(line)
        }
        return days
    }

    /**
     * Les lignes complètes d'un fichier d'échantillons, sans l'en-tête. Une dernière ligne
     * sans fin de ligne est une écriture coupée : même d'allure correcte, sa dernière valeur
     * peut être tronquée, elle ne compte donc pas.
     */
    fun validLines(text: String): List<String> {
        val all = text.split('\n')
        val header = all.firstOrNull().orEmpty()
        val columns = header.split(';').size
        return all.drop(1).dropLast(1).filter { line ->
            val parts = line.split(';')
            parts.size == columns && parts[0].toLongOrNull() != null && DAY.matches(parts.getOrNull(1).orEmpty().take(10))
        }
    }

    private fun idOf(line: String): Long = line.substringBefore(';').toLong()

    /** Ce qu'il faut ajouter au fichier [existing] : ni doublon, ni ligne collée à une écriture coupée. */
    fun textToAppend(existing: String?, header: String, lines: List<String>): String {
        val present = existing?.let { text -> validLines(text).map(::idOf).toHashSet() }.orEmpty()
        val fresh = lines.filter { idOf(it) !in present }
        return buildString {
            when {
                existing.isNullOrEmpty() -> append(header).append('\n')
                !existing.endsWith('\n') -> append('\n')
            }
            fresh.forEach { append(it).append('\n') }
        }
    }

    /** Le fichier dans l'ordre, sans doublon ni ligne abîmée ; null s'il l'est déjà. */
    fun repairedDay(text: String): String? {
        val header = text.substringBefore('\n')
        val lines = validLines(text).distinctBy(::idOf).sortedBy(::idOf)
        val clean = buildString {
            append(header).append('\n')
            lines.forEach { append(it).append('\n') }
        }
        return clean.takeIf { it != text }
    }

    /** `12-500,520-900` → plages. */
    fun parseRanges(text: String): List<LongRange> =
        text.split(',').filter { it.isNotEmpty() }.map { range ->
            val start = range.substringBefore('-').toLongOrNull() ?: throw IOException("plage illisible")
            val end = range.substringAfter('-').toLongOrNull() ?: throw IOException("plage illisible")
            start..end
        }

    /** Ce que l'Atoto a encore, jusqu'à [upTo], et que le téléphone n'a pas. [present] trié. */
    fun missingRanges(held: List<LongRange>, present: LongArray, upTo: Long): List<LongRange> {
        val missing = mutableListOf<LongRange>()
        var next = 0
        for (range in held) {
            val last = minOf(range.last, upTo)
            var id = range.first
            while (next < present.size && present[next] < id) next++
            while (id <= last) {
                if (next < present.size && present[next] == id) {
                    id++
                    next++
                    continue
                }
                val stop = if (next < present.size) minOf(last, present[next] - 1) else last
                missing += id..stop
                id = stop + 1
            }
        }
        return missing
    }

    class Bucket(val startTs: Long, val durationMs: Long, val count: Int)
    class Gap(val startTs: Long, val endTs: Long, val expected: Int, val present: Int)

    fun parseBuckets(csv: String): List<Bucket> =
        csv.lineSequence().drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split(';')
            val start = parts.getOrNull(1)?.toLongOrNull()
            val seconds = parts.getOrNull(2)?.toLongOrNull()
            val count = parts.getOrNull(3)?.toIntOrNull()
            if (start != null && seconds != null && count != null) Bucket(start, seconds * 1000, count) else null
        }.toList()

    /**
     * Périodes résumées sur l'Atoto dont le téléphone n'a pas tout le détail, depuis le premier
     * échantillon reçu : ce qui précède n'a jamais été à transférer. [sampleTimes] trié.
     */
    fun detailGaps(buckets: List<Bucket>, sampleTimes: LongArray, firstTs: Long): List<Gap> {
        val gaps = mutableListOf<Gap>()
        for (bucket in buckets.sortedBy { it.startTs }) {
            if (bucket.startTs < firstTs) continue
            val end = bucket.startTs + bucket.durationMs
            val present = lowerBound(sampleTimes, end) - lowerBound(sampleTimes, bucket.startTs)
            if (present >= bucket.count) continue
            val previous = gaps.lastOrNull()
            if (previous != null && previous.endTs == bucket.startTs) {
                gaps[gaps.lastIndex] = Gap(previous.startTs, end, previous.expected + bucket.count, previous.present + present)
            } else {
                gaps += Gap(bucket.startTs, end, bucket.count, present)
            }
        }
        return gaps
    }

    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sorted[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }
}

/**
 * Reconnaît l'Atoto à l'empreinte de son certificat auto-signé. Sans [pin], à
 * l'association : retient ce qu'il voit, que la preuve lie ensuite au code.
 */
class PinnedTrustManager(private val pin: ByteArray?) : X509TrustManager {
    var seen: ByteArray? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("aucun certificat")
        val fingerprint = TransferProtocol.sha256(leaf.encoded)
        if (pin != null && !MessageDigest.isEqual(pin, fingerprint)) throw CertificateException("certificat inconnu")
        seen = fingerprint
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("aucun client attendu")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
