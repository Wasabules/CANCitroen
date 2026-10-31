package com.geoffrey.cancitroen.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPInputStream
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Une façon de joindre l'Atoto : le réseau par défaut est souvent le mobile, pas son Wi-Fi. */
class AtotoRoute(val factory: SocketFactory, val ip: String, val label: String)

/** Parle à l'Atoto en TLS, chaque requête sur sa connexion. Bloquant. */
class AtotoClient(private val route: AtotoRoute, private val pin: ByteArray?, private val token: String?) {

    /** Renvoie le jeton et l'empreinte à épingler ; null si l'Atoto refuse le code. */
    fun pair(code: String): Pair<String, ByteArray>? {
        val trust = PinnedTrustManager(null)
        return open(trust).use { socket ->
            val proof = TransferProtocol.pairingProof(code, trust.seen ?: throw IOException("certificat non vu"))
            send(socket, "POST", "/telemetry/pair?proof=$proof")
            val answer = TransferProtocol.parseAnswer(socket.inputStream)
            if (answer.status == 200) answer.body.toString(Charsets.US_ASCII) to trust.seen!! else null
        }
    }

    fun request(method: String, path: String, ifNoneMatch: String? = null): HttpAnswer =
        open(PinnedTrustManager(pin)).use { socket ->
            send(socket, method, path, ifNoneMatch)
            TransferProtocol.parseAnswer(socket.inputStream)
        }

    fun csv(answer: HttpAnswer): String =
        GZIPInputStream(answer.body.inputStream()).readBytes().toString(Charsets.UTF_8)

    /** La poignée de main se termine ici : rien n'est envoyé à un certificat inconnu. */
    private fun open(trust: PinnedTrustManager): SSLSocket {
        val raw = route.factory.createSocket().apply {
            soTimeout = IO_TIMEOUT_MS
            connect(InetSocketAddress(route.ip, TLS_PORT), CONNECT_TIMEOUT_MS)
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val socket = context.socketFactory.createSocket(raw, route.ip, TLS_PORT, true) as SSLSocket
        try {
            socket.startHandshake()
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    private fun send(socket: SSLSocket, method: String, path: String, ifNoneMatch: String? = null) {
        val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
        val version = ifNoneMatch?.let { "If-None-Match: $it\r\n" }.orEmpty()
        socket.outputStream.write("$method $path HTTP/1.0\r\n$auth$version\r\n".toByteArray(Charsets.ISO_8859_1))
        socket.outputStream.flush()
    }

    companion object {
        const val TLS_PORT = 8766
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val IO_TIMEOUT_MS = 20_000

        /** Adresse saisie d'abord, puis la passerelle de chaque Wi-Fi, celui-ci d'abord lié. */
        fun routes(context: Context, manualIp: String): List<AtotoRoute> {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val wifi = connectivity.allNetworks.filter {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val gateways = wifi.flatMap { network ->
                connectivity.getLinkProperties(network)?.routes.orEmpty()
                    .mapNotNull { it.gateway?.hostAddress }
                    .filter { LinkParsing.isIpv4(it) && it != "0.0.0.0" }
                    .map { AtotoRoute(network.socketFactory, it, "Wi-Fi $it") }
            }
            val manual = manualIp.trim().takeIf { LinkParsing.isIpv4(it) }?.let { ip ->
                wifi.map { AtotoRoute(it.socketFactory, ip, "Wi-Fi $ip") } +
                    AtotoRoute(SocketFactory.getDefault(), ip, "réseau par défaut $ip")
            }.orEmpty()
            return manual + gateways
        }
    }
}
