package com.geoffrey.cancitroen.link

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Serveur du test de liaison avec l'app compagnon : vérifie que le téléphone
 * atteint l'Atoto pendant une session Android Auto sans fil.
 *
 * Ne sert aucune donnée véhicule : `/ping` (identité et version) et `/blob`
 * (des zéros, pour mesurer le débit). Désactivé par défaut (réglage Dev).
 */
object LinkProbeServer {
    const val PORT = 8765
    private const val TAG = "LinkProbe"
    private const val SOCKET_TIMEOUT_MS = 5_000
    private const val MAX_CONNECTIONS = 4
    private const val MAX_HEADER_LINES = 32

    data class Status(
        val running: Boolean = false,
        val addresses: List<String> = emptyList(),
        val hits: Int = 0,
        val lastHit: String? = null,
        val error: String? = null,
    )

    sealed interface Route {
        data object Ping : Route
        data class Blob(val kb: Int) : Route
        data object NotFound : Route
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val slots = Semaphore(MAX_CONNECTIONS)
    private var server: ServerSocket? = null

    /** Lie le port : à appeler hors du thread principal. */
    @Synchronized
    fun start(version: String) {
        if (server != null) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Port $PORT indisponible", e)
            _status.update { it.copy(running = false, error = e.message) }
            return
        }
        server = socket
        _status.update { it.copy(running = true, error = null, addresses = localAddresses()) }
        Log.i(TAG, "À l'écoute sur le port $PORT : ${_status.value.addresses}")
        thread(isDaemon = true, name = "LinkProbe") { acceptLoop(socket, version) }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        _status.update { it.copy(running = false) }
    }

    /** L'interface du Wi-Fi d'Android Auto n'existe que pendant la session : à relire. */
    fun refreshAddresses() {
        if (_status.value.running) _status.update { it.copy(addresses = localAddresses()) }
    }

    private fun acceptLoop(socket: ServerSocket, version: String) {
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
            thread(isDaemon = true, name = "LinkProbe-client") {
                try {
                    handle(client, version)
                } catch (e: IOException) {
                    Log.d(TAG, "Connexion interrompue : ${e.message}")
                } finally {
                    slots.release()
                }
            }
        }
    }

    private fun handle(client: Socket, version: String) {
        client.use { sock ->
            serve(sock, version)
        }
    }

    private fun serve(sock: Socket, version: String) {
        sock.soTimeout = SOCKET_TIMEOUT_MS
        val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        // En-têtes lus jusqu'au bout : fermer avec des données non lues couperait la réponse.
        var lines = 0
        while (lines++ < MAX_HEADER_LINES && !reader.readLine().isNullOrEmpty()) { /* ignoré */ }

        val from = sock.remoteSocketAddress.toString().removePrefix("/").substringBeforeLast(':')
        val on = sock.localAddress.hostAddress
        val at = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        _status.update {
            it.copy(hits = it.hits + 1, lastHit = "$at · $from → $on", addresses = localAddresses())
        }
        Log.i(TAG, "$requestLine depuis $from sur $on")

        val out = sock.getOutputStream()
        when (val route = routeOf(requestLine)) {
            Route.Ping -> {
                val body = """{"app":"CANCitroen","version":"$version","port":$PORT}"""
                out.write(header("200 OK", "application/json", body.length.toLong()))
                out.write(body.toByteArray())
            }
            is Route.Blob -> {
                out.write(header("200 OK", "application/octet-stream", route.kb * 1024L))
                val chunk = ByteArray(1024)
                repeat(route.kb) { out.write(chunk) }
            }
            Route.NotFound -> out.write(header("404 Not Found", "text/plain", 0))
        }
        out.flush()
    }

    private fun header(status: String, type: String, length: Long) =
        "HTTP/1.0 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)

    internal fun routeOf(requestLine: String): Route {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return Route.NotFound
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        return when (path) {
            "/ping" -> Route.Ping
            "/blob" -> {
                val kb = query.split('&').firstOrNull { it.startsWith("kb=") }
                    ?.removePrefix("kb=")?.toIntOrNull() ?: return Route.NotFound
                Route.Blob(kb.coerceIn(1, 4096))
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
