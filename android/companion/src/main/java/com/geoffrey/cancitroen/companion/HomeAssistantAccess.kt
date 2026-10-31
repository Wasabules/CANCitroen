package com.geoffrey.cancitroen.companion

import java.net.URI
import java.net.URISyntaxException

/**
 * En-têtes ajoutés aux requêtes vers Home Assistant, pour passer un proxy d'authentification
 * comme Cloudflare Access (`CF-Access-Client-Id`, `CF-Access-Client-Secret`). Un par ligne.
 */
object HaHeaders {
    private val NAME = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]+$""")
    private val CONTROL = Regex("""[\x00-\x08\x0A-\x1F\x7F]""")
    private const val MAX_HEADERS = 10
    // Gérés par l'app, ou refusés par le client HTTP.
    private val RESERVED = setOf(
        "authorization", "host", "content-length", "content-type", "connection", "transfer-encoding", "user-agent",
    )

    sealed interface Result
    data class Valid(val headers: List<Pair<String, String>>) : Result
    data class Invalid(val reason: String) : Result

    /** Les messages d'erreur citent le nom, jamais la valeur : c'est souvent un secret. */
    fun parse(text: String): Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > MAX_HEADERS) return Invalid("$MAX_HEADERS en-têtes au plus.")
        val headers = lines.mapIndexed { index, line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return Invalid("Ligne ${index + 1} : format attendu « Nom: valeur ».")
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (!NAME.matches(name)) return Invalid("Ligne ${index + 1} : nom d'en-tête invalide.")
            if (name.lowercase() in RESERVED) return Invalid("L'en-tête $name est géré par l'app.")
            if (value.isEmpty()) return Invalid("L'en-tête $name n'a pas de valeur.")
            if (CONTROL.containsMatchIn(value)) return Invalid("La valeur de $name contient un caractère invalide.")
            name to value
        }
        return Valid(headers)
    }

    /**
     * Seulement vers l'adresse saisie : l'accès distant ou le webhook de Nabu Casa, que Home
     * Assistant indique lui-même, n'ont pas à recevoir ces secrets.
     */
    fun sameServer(target: String, address: String): Boolean {
        val a = uri(target) ?: return false
        val b = uri(address) ?: return false
        return a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }

    private fun uri(text: String): URI? = try {
        URI(text).takeIf { it.host != null && it.scheme != null }
    } catch (_: URISyntaxException) {
        null
    }
}

/** Traduit une réponse en explication : qui refuse, et quoi faire. */
object HaDiagnostic {
    class Reply(val code: Int, val text: String, val location: String?, val cloudflare: Boolean)

    /** Corps exact des refus de Home Assistant : il distingue son refus de celui d'un proxy. */
    private const val HA_UNAUTHORIZED = "401: Unauthorized"
    private const val HA_BAD_REQUEST = "400: Bad Request"

    fun isHomeAssistantRefusal(reply: Reply): Boolean = reply.code == 401 && reply.text.trim() == HA_UNAUTHORIZED

    /** Null si la réponse ne révèle aucun problème d'accès. */
    fun problem(reply: Reply): String? = when {
        reply.code in 300..399 -> redirect(reply)
        isHomeAssistantRefusal(reply) -> "Home Assistant refuse le jeton."
        reply.code == 400 && reply.text.trim() == HA_BAD_REQUEST ->
            "Home Assistant refuse le proxy : déclare-le dans http → use_x_forwarded_for et trusted_proxies."
        (reply.code == 401 || reply.code == 403) && reply.cloudflare ->
            "Cloudflare refuse l'accès (${reply.code}) : vérifie les en-têtes, leur expiration et la politique Service Auth de l'application."
        reply.code == 403 -> "Accès refusé (403) : par un proxy, ou adresse bannie par Home Assistant."
        reply.code == 401 -> "Accès refusé (401) par un proxy devant Home Assistant."
        reply.code in 520..530 && reply.cloudflare ->
            "Cloudflare n'atteint pas Home Assistant (${reply.code}) : tunnel arrêté ou serveur éteint."
        reply.code in 500..599 -> "Le serveur répond en erreur (${reply.code})."
        reply.code !in 200..299 -> "Réponse inattendue (${reply.code})."
        else -> null
    }

    private fun redirect(reply: Reply): String {
        val location = reply.location.orEmpty()
        if ("cloudflareaccess.com" in location || "/cdn-cgi/access/" in location) {
            return "Cloudflare Access demande une connexion : ajoute les en-têtes CF-Access-Client-Id et " +
                "CF-Access-Client-Secret, et une politique Service Auth pour ce jeton de service."
        }
        val host = try {
            URI(location).host
        } catch (_: URISyntaxException) {
            null
        }
        return "Le serveur redirige (${reply.code})" + (host?.let { " vers $it : utilise cette adresse." } ?: ".")
    }
}
