package com.geoffrey.cancitroen.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * Cherche comment joindre l'Atoto pendant une session Android Auto sans fil.
 * Bloquant : à lancer hors du thread principal. Sa seule modification du système
 * est une règle de routage root vers l'Atoto, retirée dès la fin de son essai.
 */
class LinkTest(context: Context, private val log: (String) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var rootRoutes = ""

    fun run(manualIp: String, carState: String) {
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Android Auto : $carState")

        val rootOk = exec("id", ROOT_PROBE_TIMEOUT_MS)?.contains("uid=0") == true
        log("Root : ${if (rootOk) "disponible" else "indisponible"}")

        section("Réseaux visibles par l'app")
        val networks = connectivity.allNetworks.toList()
        networks.forEach { log(describe(it)) }
        if (networks.isEmpty()) log("aucun")
        val wifiNetworks = networks.filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        val rootCandidates = if (rootOk) {
            section("Réseau vu par le root")
            val addr = exec("ip -4 -o addr", COMMAND_TIMEOUT_MS).orEmpty()
            val routes = exec("ip -4 route show table all", COMMAND_TIMEOUT_MS).orEmpty()
            rootRoutes = routes
            val neigh = exec("ip -4 neigh", COMMAND_TIMEOUT_MS).orEmpty()
            log(LinkParsing.wifiLinesOnly(addr + "\n" + routes + "\n" + neigh).ifEmpty { "aucune interface Wi-Fi active" })
            val local = LinkParsing.localAddresses(addr)
            LinkParsing.fromRoutes(routes, local) + LinkParsing.fromNeighbours(neigh, local)
        } else {
            emptyList()
        }

        val candidates = buildCandidates(manualIp, wifiNetworks, rootCandidates)
        section("Adresses à essayer")
        if (candidates.isEmpty()) {
            log("aucune : saisir l'adresse affichée dans CANCitroen (Réglages → Développeur → App compagnon)")
            verdict(null)
            return
        }
        candidates.forEach { log("${it.ip} (${it.source}${it.iface?.let { i -> ", $i" }.orEmpty()})") }

        var working: String? = null
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            section("Essais vers ${candidate.ip}")
            working = tryCandidate(candidate, wifiNetworks, rootOk)
            if (working != null) break
        }
        verdict(working)
    }

    private fun buildCandidates(
        manualIp: String,
        wifiNetworks: List<Network>,
        rootCandidates: List<Candidate>,
    ): List<Candidate> = buildList {
        val manual = manualIp.trim()
        if (manual.isNotEmpty()) {
            if (LinkParsing.isIpv4(manual)) {
                add(Candidate(manual, rootCandidates.firstOrNull()?.iface ?: firstIface(wifiNetworks), "saisie"))
            } else {
                log("Adresse saisie ignorée : $manual")
            }
        }
        for (network in wifiNetworks) {
            val props = connectivity.getLinkProperties(network) ?: continue
            for (route in props.routes) {
                val gateway = route.gateway?.hostAddress ?: continue
                if (LinkParsing.isIpv4(gateway) && gateway != "0.0.0.0") {
                    add(Candidate(gateway, props.interfaceName, "passerelle"))
                }
            }
        }
        addAll(rootCandidates)
    }.distinctBy { it.ip }

    private fun firstIface(wifiNetworks: List<Network>): String? =
        wifiNetworks.firstNotNullOfOrNull { connectivity.getLinkProperties(it)?.interfaceName }

    /** Renvoie la première voie qui répond, avec son débit ; essaie toutes les autres pour le rapport. */
    private fun tryCandidate(candidate: Candidate, wifiNetworks: List<Network>, rootOk: Boolean): String? {
        val ip = candidate.ip
        val working = mutableListOf<String>()

        fun attempt(name: String, factory: SocketFactory) {
            val ping = httpPing(factory, ip)
            log("$name : ${ping.detail}")
            if (ping.isAtoto) {
                val speed = httpSpeed(factory, ip)
                log("  débit : $speed")
                working += "$name, $speed"
            }
        }

        attempt("App, réseau par défaut", SocketFactory.getDefault())
        wifiNetworks.forEach { attempt("App, liée au Wi-Fi ${connectivity.getLinkProperties(it)?.interfaceName}", it.socketFactory) }

        if (rootOk) {
            val answer = exec("$PING_REQUEST | toybox nc -w $NC_TIMEOUT_S $ip $PORT", COMMAND_TIMEOUT_MS).orEmpty()
            val isAtoto = ATOTO_MARK in answer
            log("Root, nc : ${if (isAtoto) "répond" else "pas de réponse"}")
            if (isAtoto) working += "root (nc), ${rootSpeed(ip)}"

            val iface = candidate.iface
            val table = iface?.let { LinkParsing.routingTable(rootRoutes, it) }
            if (working.isEmpty() && table != null) {
                val rule = "to $ip/32 lookup $table pref $RULE_PREF"
                val added = exec("ip rule add $rule 2>&1; echo fin=$?", COMMAND_TIMEOUT_MS).orEmpty()
                log("Règle temporaire : ${added.trim()}")
                try {
                    if ("fin=0" in added) attempt("App, avec règle root", SocketFactory.getDefault())
                } finally {
                    exec("ip rule del $rule", COMMAND_TIMEOUT_MS)
                    log("Règle temporaire retirée")
                }
            }
            if (working.isEmpty() && iface != null) {
                val reply = exec("ping -c 1 -W 2 -I $iface $ip >/dev/null 2>&1; echo fin=$?", COMMAND_TIMEOUT_MS).orEmpty()
                log("Ping root via $iface : ${if ("fin=0" in reply) "répond (port fermé ou serveur arrêté ?)" else "pas de réponse"}")
            }
        }
        return working.firstOrNull()
    }

    private fun verdict(working: String?) {
        section("Résultat")
        log(
            if (working != null) {
                "OK. Atoto joignable : $working"
            } else {
                "ÉCHEC. Atoto injoignable. Vérifier que le test est activé dans CANCitroen, puis réessayer avec son adresse."
            },
        )
    }

    private fun section(title: String) = log("\n── $title ──")

    private fun describe(network: Network): String {
        val caps = connectivity.getNetworkCapabilities(network)
        val props = connectivity.getLinkProperties(network)
        val kinds = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) add("sans Internet")
        }.joinToString(", ")
        val addresses = props?.linkAddresses.orEmpty().map { it.address.hostAddress }
            .filter { LinkParsing.isIpv4(it.orEmpty()) }.joinToString(", ")
        return "${props?.interfaceName} : $kinds · $addresses"
    }

    private class Ping(val isAtoto: Boolean, val detail: String)

    private fun httpPing(factory: SocketFactory, ip: String): Ping = try {
        val started = System.nanoTime()
        val answer = openRequest(factory, ip, "/ping").use { socket ->
            readUpTo(socket.getInputStream(), MAX_PING_BYTES).toString(Charsets.ISO_8859_1)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ATOTO_MARK in answer) {
            Ping(true, "répond en $ms ms ${answer.substringAfter("\r\n\r\n", "")}")
        } else {
            Ping(false, "un autre serveur répond")
        }
    } catch (e: IOException) {
        Ping(false, "échec (${e.message})")
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() < limit) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun httpSpeed(factory: SocketFactory, ip: String): String = try {
        val started = System.nanoTime()
        val bytes = openRequest(factory, ip, "/blob?kb=$SPEED_TEST_KB").use { socket ->
            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
        formatSpeed(bytes, System.nanoTime() - started)
    } catch (e: IOException) {
        "interrompu (${e.message})"
    }

    private fun rootSpeed(ip: String): String {
        val started = System.nanoTime()
        val request = "printf 'GET /blob?kb=$SPEED_TEST_KB HTTP/1.0\\r\\n\\r\\n'"
        val bytes = exec("$request | toybox nc -w $NC_TIMEOUT_S $ip $PORT | wc -c", SPEED_TIMEOUT_MS)?.trim()?.toLongOrNull()
            ?: return "débit inconnu"
        return formatSpeed(bytes, System.nanoTime() - started)
    }

    private fun formatSpeed(bytes: Long, elapsedNs: Long): String {
        val seconds = elapsedNs / 1e9
        val mbits = bytes * 8 / 1e6 / seconds
        return "%d Ko en %.2f s, %.1f Mbit/s".format(bytes / 1024, seconds, mbits)
    }

    private fun openRequest(factory: SocketFactory, ip: String, path: String) =
        factory.createSocket().apply {
            soTimeout = IO_TIMEOUT_MS
            connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            getOutputStream().write("GET $path HTTP/1.0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }

    /** `su -c` capturé ; null si `su` est absent, refusé trop longtemps ou bloqué. */
    private fun exec(command: String, timeoutMs: Long): String? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        var output = ""
        val reader = thread(isDaemon = true) {
            output = try {
                process.inputStream.bufferedReader().readText()
            } catch (_: IOException) {
                ""
            }
        }
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            reader.join(READER_JOIN_MS)
            output
        } else {
            process.destroyForcibly()
            null
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val PORT = 8765
        const val ATOTO_MARK = "\"app\":\"CANCitroen\""
        const val PING_REQUEST = "printf 'GET /ping HTTP/1.0\\r\\n\\r\\n'"
        const val MAX_CANDIDATES = 6
        const val MAX_PING_BYTES = 4096
        const val SPEED_TEST_KB = 1024
        const val RULE_PREF = 9000
        const val CONNECT_TIMEOUT_MS = 2_000
        const val IO_TIMEOUT_MS = 5_000
        const val NC_TIMEOUT_S = 3
        const val COMMAND_TIMEOUT_MS = 8_000L
        const val SPEED_TIMEOUT_MS = 30_000L
        // Laisse le temps de répondre à la demande d'autorisation de Magisk.
        const val ROOT_PROBE_TIMEOUT_MS = 30_000L
        const val READER_JOIN_MS = 500L
    }
}
