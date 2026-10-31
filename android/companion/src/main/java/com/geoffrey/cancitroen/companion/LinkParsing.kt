package com.geoffrey.cancitroen.companion

/** Adresse où l'Atoto peut se trouver ; [iface] sert à la règle de routage temporaire. */
data class Candidate(val ip: String, val iface: String?, val source: String)

/** Lecture des sorties de `ip`, sans dépendance à Android. */
object LinkParsing {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val IFACE_NAME = Regex("""^[A-Za-z0-9_.-]{1,32}$""")
    private val WIFI_IFACE = Regex("""^(wlan|swlan|p2p|ap|softap|wifi).*""")
    private val VIA_ROUTE = Regex("""via (\S+) dev (\S+)""")
    private val NEIGHBOUR = Regex("""^(\S+) dev (\S+)""")
    private val LOCAL_ADDRESS = Regex("""inet (\S+?)/""")
    private val HARDWARE_ADDRESS = Regex("""lladdr \S+\s*""")
    private val DEFAULT_ROUTE_TABLE = Regex("""^default via \S+ dev (\S+)(?: \S+ \S+)*? table (\S+)""")
    private val ANY_ROUTE_TABLE = Regex("""dev (\S+)(?: \S+ \S+)*? table (\S+)""")
    private val DEAD_STATE = Regex("""\b(FAILED|INCOMPLETE)\b""")

    /** Valeurs insérées dans une commande `su` : rien d'autre qu'une IPv4 ou un nom d'interface. */
    fun isIpv4(value: String): Boolean =
        IPV4.matchEntire(value)?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true

    fun isIfaceName(value: String): Boolean = IFACE_NAME.matches(value)

    fun isWifiIface(name: String): Boolean = WIFI_IFACE.matches(name)

    fun localAddresses(ipAddrOutput: String): Set<String> =
        LOCAL_ADDRESS.findAll(ipAddrOutput).map { it.groupValues[1] }.toSet()

    fun fromRoutes(output: String, local: Set<String>): List<Candidate> =
        output.lineSequence().mapNotNull { line ->
            val (ip, iface) = VIA_ROUTE.find(line)?.destructured ?: return@mapNotNull null
            candidate(ip, iface, "route", local)
        }.toList()

    fun fromNeighbours(output: String, local: Set<String>): List<Candidate> =
        output.lineSequence().mapNotNull { line ->
            if (DEAD_STATE.containsMatchIn(line)) return@mapNotNull null
            val (ip, iface) = NEIGHBOUR.find(line)?.destructured ?: return@mapNotNull null
            candidate(ip, iface, "voisin", local)
        }.toList()

    private fun candidate(ip: String, iface: String, source: String, local: Set<String>): Candidate? =
        if (isIpv4(ip) && ip != "0.0.0.0" && ip !in local && isWifiIface(iface) && isIfaceName(iface)) {
            Candidate(ip, iface, source)
        } else {
            null
        }

    /**
     * Table de routage d'Android pour [iface], son nom (`wlan0`) ou son numéro
     * (`1017`) selon le système. Celle de la route par défaut d'abord.
     */
    fun routingTable(routes: String, iface: String): String? {
        val lines = routes.lineSequence().map { it.trim() }
        fun tableIn(regex: Regex) = lines.mapNotNull { regex.find(it) }
            .firstOrNull { it.groupValues[1] == iface }?.groupValues?.get(2)
        return (tableIn(DEFAULT_ROUTE_TABLE) ?: tableIn(ANY_ROUTE_TABLE))?.takeIf { isIfaceName(it) }
    }

    /** Pour le rapport : seulement le Wi-Fi, et sans adresse matérielle (rapport à partager). */
    fun wifiLinesOnly(output: String): String =
        output.lineSequence()
            .filter { line -> line.split(' ', ':', '\t').any { it.isNotEmpty() && isWifiIface(it) } }
            .map { HARDWARE_ADDRESS.replace(it, "") }
            .joinToString("\n")
}
