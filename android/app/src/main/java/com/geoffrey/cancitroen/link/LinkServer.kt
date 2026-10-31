package com.geoffrey.cancitroen.link

import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.zip.GZIPOutputStream
import javax.net.ServerSocketFactory
import kotlin.concurrent.thread

/**
 * Serveur de l'app compagnon sur le Wi-Fi d'Android Auto, avec deux ports indépendants,
 * chacun ouvert seulement si son réglage est activé (désactivés par défaut) :
 *
 *  - [PORT], en clair, test de liaison : `/ping` et `/blob`, aucune donnée véhicule ;
 *  - [TLS_PORT], chiffré, transfert : `/telemetry/...`, seulement sur le point d'accès
 *    de l'Atoto et pour le téléphone associé (voir [TelemetrySource]).
 */
object LinkServer {
    const val PORT = 8765
    const val TLS_PORT = 8766
    private const val TAG = "LinkProbe"
    private const val SOCKET_TIMEOUT_MS = 5_000
    private const val MAX_CONNECTIONS = 4
    private const val MAX_HEADER_LINES = 32
    private const val MAX_BATCH = 5_000
    // Un essai de code raté bloque sa connexion : au plus 4 essais par seconde.
    private const val AUTH_FAILURE_DELAY_MS = 1_000L
    // Environ 1 Mo/s : la projection d'Android Auto passe par le même Wi-Fi.
    private const val THROTTLE_CHUNK = 16 * 1024
    private const val THROTTLE_PAUSE_MS = 16L

    data class Status(
        val running: Boolean = false,
        val addresses: List<String> = emptyList(),
        val hits: Int = 0,
        val lastHit: String? = null,
        val lastTransfer: String? = null,
        val error: String? = null,
    )

    sealed interface Route {
        data object Ping : Route
        data class Blob(val kb: Int) : Route
        data object TelemetryStatus : Route
        data object TelemetrySummary : Route
        data object TelemetryInventory : Route
        data class TelemetrySamples(val afterId: Long, val limit: Int, val upToId: Long = Long.MAX_VALUE) : Route
        data class TelemetryTable(val table: TelemetrySource.Table) : Route
        data class TelemetryAck(val id: Long) : Route
        data class TelemetryPair(val proof: String) : Route
        data object NotFound : Route
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val slots = Semaphore(MAX_CONNECTIONS)
    private var plainServer: ServerSocket? = null
    private var secureServer: ServerSocket? = null
    @Volatile private var telemetry: TelemetrySource? = null

    /** Ouvre et ferme les ports : à appeler hors du thread principal. */
    @Synchronized
    fun configure(version: String, probe: Boolean, telemetry: TelemetrySource?, tls: ServerSocketFactory?) {
        this.telemetry = telemetry
        _status.update { it.copy(error = null) }
        plainServer = reconcile(plainServer, probe, ServerSocketFactory.getDefault(), PORT, version, secure = false)
        secureServer = reconcile(secureServer, telemetry != null && tls != null, tls, TLS_PORT, version, secure = true)
        val running = plainServer != null || secureServer != null
        _status.update { it.copy(running = running, addresses = if (running) localAddresses() else emptyList()) }
    }

    @Synchronized
    fun stop() = configure("", probe = false, telemetry = null, tls = null)

    fun reportError(message: String?) = _status.update { it.copy(error = message) }

    private fun reconcile(
        current: ServerSocket?,
        wanted: Boolean,
        factory: ServerSocketFactory?,
        port: Int,
        version: String,
        secure: Boolean,
    ): ServerSocket? {
        if (!wanted || factory == null) {
            try { current?.close() } catch (_: IOException) {}
            return null
        }
        if (current != null) return current
        val socket = try {
            factory.createServerSocket(port)
        } catch (e: IOException) {
            Log.w(TAG, "Port $port indisponible", e)
            _status.update { it.copy(error = e.message) }
            return null
        }
        Log.i(TAG, "À l'écoute sur le port $port")
        thread(isDaemon = true, name = "LinkServer-$port") { acceptLoop(socket, version, secure) }
        return socket
    }

    /** L'interface du Wi-Fi d'Android Auto n'existe que pendant la session : à relire. */
    fun refreshAddresses() {
        if (_status.value.running) _status.update { it.copy(addresses = localAddresses()) }
    }

    private fun acceptLoop(socket: ServerSocket, version: String, secure: Boolean) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            if (!slots.tryAcquire()) {
                try { client.close() } catch (_: IOException) {}
                continue
            }
            thread(isDaemon = true, name = "LinkServer-client") {
                try {
                    client.use { serve(it, version, secure) }
                } catch (e: IOException) {
                    Log.d(TAG, "Connexion interrompue : ${e.message}")
                } finally {
                    slots.release()
                }
            }
        }
    }

    private fun serve(sock: Socket, version: String, secure: Boolean) {
        sock.soTimeout = SOCKET_TIMEOUT_MS
        val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        // En-têtes lus jusqu'au bout : fermer avec des données non lues couperait la réponse.
        var bearer = ""
        var ifNoneMatch = ""
        var lines = 0
        while (lines++ < MAX_HEADER_LINES) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                bearer = line.substringAfter(':').trim().removePrefix("Bearer ").trim()
            } else if (line.startsWith("If-None-Match:", ignoreCase = true)) {
                ifNoneMatch = line.substringAfter(':').trim()
            }
        }

        val from = sock.remoteSocketAddress.toString().removePrefix("/").substringBeforeLast(':')
        val on = sock.localAddress.hostAddress
        val at = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        _status.update {
            it.copy(hits = it.hits + 1, lastHit = "$at · $from → $on", addresses = localAddresses())
        }
        // Sans les paramètres : la preuve d'association n'a rien à faire dans les journaux.
        Log.i(TAG, "${requestLine.substringBefore('?')} depuis $from sur $on")

        val out = sock.getOutputStream()
        val route = routeOf(requestLine)
        when {
            route == Route.NotFound -> out.write(header("404 Not Found", "text/plain", 0))
            secure -> serveTelemetry(route, bearer, ifNoneMatch, sock, out)
            route == Route.Ping -> {
                val body = """{"app":"CANCitroen","version":"$version","port":$PORT}"""
                out.write(header("200 OK", "application/json", body.length.toLong()))
                out.write(body.toByteArray())
            }
            route is Route.Blob -> {
                out.write(header("200 OK", "application/octet-stream", route.kb * 1024L))
                val chunk = ByteArray(1024)
                repeat(route.kb) { out.write(chunk) }
            }
            // La télémétrie ne passe jamais par le port en clair.
            else -> out.write(header("404 Not Found", "text/plain", 0))
        }
        out.flush()
    }

    private fun serveTelemetry(route: Route, bearer: String, ifNoneMatch: String, sock: Socket, out: OutputStream) {
        val source = telemetry
        if (source == null || !TransferSecurity.isOwnAccessPoint(sock.localAddress)) {
            out.write(header("403 Forbidden", "text/plain", 0))
            return
        }
        if (route is Route.TelemetryPair) {
            val token = runBlocking { source.pair(route.proof) }
            if (token == null) {
                Thread.sleep(AUTH_FAILURE_DELAY_MS)
                out.write(header("401 Unauthorized", "text/plain", 0))
            } else {
                out.write(header("200 OK", "text/plain", token.length.toLong()))
                out.write(token.toByteArray())
            }
            return
        }
        if (!runBlocking { source.isAuthorized(bearer) }) {
            Thread.sleep(AUTH_FAILURE_DELAY_MS)
            out.write(header("401 Unauthorized", "text/plain", 0))
            return
        }
        // Le CAN et l'interface passent avant : le transfert prend ce qui reste.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        when (route) {
            Route.TelemetryStatus -> {
                val status = runBlocking { source.status() }
                val body = """{"lastId":${status.lastId},"ackId":${status.ackId},"pending":${status.pending}}"""
                out.write(header("200 OK", "application/json", body.length.toLong()))
                out.write(body.toByteArray())
            }
            Route.TelemetrySummary -> {
                val body = runBlocking { source.summary() }.toByteArray()
                out.write(header("200 OK", "application/json", body.size.toLong()))
                out.write(body)
            }
            is Route.TelemetrySamples -> {
                val batch = runBlocking { source.samples(route.afterId, route.upToId, route.limit) }
                val extra = "X-Count: ${batch.count}\r\nX-Last-Id: ${batch.lastId}\r\n"
                sendCsv(batch.csv, extra, out)
            }
            Route.TelemetryInventory -> {
                val body = runBlocking { source.inventory() }.toByteArray()
                out.write(header("200 OK", "text/plain", body.size.toLong()))
                out.write(body)
            }
            // Le téléphone garde la version de chaque fichier : inchangé, rien ne repart.
            is Route.TelemetryTable -> {
                val csv = runBlocking { source.table(route.table) }
                val version = TransferSecurity.sha256Hex(csv)
                if (version == ifNoneMatch) {
                    out.write(header("304 Not Modified", "text/plain", 0))
                } else {
                    sendCsv(csv, "ETag: $version\r\n", out)
                }
            }
            is Route.TelemetryAck -> {
                val sent = runBlocking { source.ack(route.id) }
                if (sent > 0) {
                    val at = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
                    _status.update { it.copy(lastTransfer = "$at · $sent échantillons") }
                }
                out.write(header("200 OK", "text/plain", 0))
            }
            else -> out.write(header("404 Not Found", "text/plain", 0))
        }
    }

    private fun sendCsv(csv: String, extraHeaders: String, out: OutputStream) {
        val zipped = gzip(csv)
        out.write(header("200 OK", "application/gzip", zipped.size.toLong(), extraHeaders))
        var offset = 0
        while (offset < zipped.size) {
            val length = minOf(THROTTLE_CHUNK, zipped.size - offset)
            out.write(zipped, offset, length)
            offset += length
            Thread.sleep(THROTTLE_PAUSE_MS)
        }
    }

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }

    private fun header(status: String, type: String, length: Long, extra: String = "") =
        "HTTP/1.0 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)

    internal fun routeOf(requestLine: String): Route {
        val parts = requestLine.split(' ')
        if (parts.size < 2) return Route.NotFound
        val path = parts[1].substringBefore('?')
        val params = parts[1].substringAfter('?', "").split('&').associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        return when (parts[0]) {
            "GET" -> when (path) {
                "/ping" -> Route.Ping
                "/blob" -> params["kb"]?.toIntOrNull()?.let { Route.Blob(it.coerceIn(1, 4096)) } ?: Route.NotFound
                "/telemetry/status" -> Route.TelemetryStatus
                "/telemetry/summary" -> Route.TelemetrySummary
                "/telemetry/inventory" -> Route.TelemetryInventory
                "/telemetry/samples" -> {
                    val after = params["after"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return Route.NotFound
                    val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_BATCH) ?: MAX_BATCH
                    Route.TelemetrySamples(after, limit, params["upto"]?.toLongOrNull() ?: Long.MAX_VALUE)
                }
                "/telemetry/aggregates" -> Route.TelemetryTable(TelemetrySource.Table.AGGREGATES)
                "/telemetry/trips" -> Route.TelemetryTable(TelemetrySource.Table.TRIPS)
                "/telemetry/refuels" -> Route.TelemetryTable(TelemetrySource.Table.REFUELS)
                else -> Route.NotFound
            }
            "POST" -> when (path) {
                "/telemetry/ack" -> params["id"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { Route.TelemetryAck(it) } ?: Route.NotFound
                "/telemetry/pair" -> params["proof"]?.let { Route.TelemetryPair(it) } ?: Route.NotFound
                else -> Route.NotFound
            }
            else -> Route.NotFound
        }
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { "${nic.name} : ${it.hostAddress}" }
            }
    } catch (_: IOException) {
        emptyList()
    }
}
