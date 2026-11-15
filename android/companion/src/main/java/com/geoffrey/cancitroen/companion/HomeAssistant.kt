package com.geoffrey.cancitroen.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Publie l'état de la voiture dans Home Assistant, à travers l'intégration `mobile_app`
 * (la même que son app officielle) : un appareil « Citroën C2 » et ses capteurs, sans
 * aucune configuration côté serveur. Le jeton saisi ne sert qu'à l'inscription et n'est
 * pas gardé ; le webhook reçu en échange ne permet que d'écrire ces capteurs.
 *
 * Bloquant : hors du thread principal. Serveur injoignable (adresse locale, sur la route) :
 * le dernier état attend, et part au prochain essai qui l'atteint.
 */
object HomeAssistant {
    /** [sent] : faux si rien n'est parti, l'état n'ayant pas changé depuis le dernier envoi. */
    class Published(val ok: Boolean, val sent: Boolean, val message: String)

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val GONE = 410
    private const val UNKNOWN_WEBHOOK_LIMIT = 3
    private const val API_MARK = "API running"
    private const val MAX_PENDING_EVENTS = 20

    private fun prefs(context: Context) = context.getSharedPreferences("home_assistant", Context.MODE_PRIVATE)

    fun isConnected(context: Context): Boolean = prefs(context).contains("webhook")
    fun lastResult(context: Context): String = prefs(context).getString("resultat", "").orEmpty()

    /** Heure du dernier envoi accepté par Home Assistant ; 0 si aucun. */
    fun lastSentMs(context: Context): Long = prefs(context).getLong("dernier_envoi", 0)

    /** À chaque interrogation de l'Atoto : le résumé attend ici qu'un serveur soit joignable. */
    fun saveSummary(context: Context, summary: String) {
        prefs(context).edit().putString("resume", summary).apply()
        val parsed = parse(summary) ?: return
        queue(context, TripEvents.fromSummary(parsed, eventState(context), TransferPrefs(context).fuelType))
    }

    /** Fin du suivi : le trajet que l'Atoto n'a pas encore clos est annoncé avec ses derniers chiffres. */
    fun noteSessionEnd(context: Context, endTs: Long) {
        val parsed = lastSummary(context)?.let(::parse) ?: return
        queue(context, TripEvents.fromSessionEnd(parsed, eventState(context), endTs, TransferPrefs(context).fuelType))
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

    private fun eventState(context: Context): TripEvents.State {
        val store = prefs(context)
        return TripEvents.State(store.getLong("evt_trajet", 0), store.getLong("evt_plein", 0), store.getBoolean("evt_pret", false))
    }

    /** Les événements attendent ici un Home Assistant joignable ; les plus anciens partent d'abord. */
    @Synchronized
    private fun queue(context: Context, result: TripEvents.Result) {
        val store = prefs(context)
        val pending = pendingEvents(context)
        for (event in result.events) {
            pending.put(JSONObject().put("type", event.type).put("data", event.data))
            CompanionLog.add(context, "Événement en attente pour Home Assistant : ${event.type}.")
        }
        while (pending.length() > MAX_PENDING_EVENTS) pending.remove(0)
        store.edit()
            .putString("evenements", pending.toString())
            .putLong("evt_trajet", result.state.tripStart)
            .putLong("evt_plein", result.state.refuelTs)
            .putBoolean("evt_pret", result.state.initialized)
            .apply()
    }

    private fun pendingEvents(context: Context): JSONArray = try {
        JSONArray(prefs(context).getString("evenements", "[]"))
    } catch (_: JSONException) {
        JSONArray()
    }

    /** Le dernier résumé reçu de l'Atoto, même sans Home Assistant connecté. */
    fun lastSummary(context: Context): String? = prefs(context).getString("resume", null)

    /** Dernière réponse de l'Atoto ; 0 si jamais. */
    fun lastContactMs(context: Context): Long = prefs(context).getLong("contact", 0)

    /** Ce que le téléphone sait de la liaison : l'Atoto répond-il, et depuis quand l'a-t-il entendu ? */
    fun saveLink(context: Context, linked: Boolean, contactTs: Long?) {
        prefs(context).edit().putBoolean("liee", linked).apply {
            if (contactTs != null) putLong("contact", contactTs)
        }.apply()
    }

    fun address(context: Context): String = prefs(context).getString("adresse", "").orEmpty()
    fun headersText(context: Context): String = prefs(context).getString("entetes", "").orEmpty()

    /** Renvoie la raison du refus, ou null si les en-têtes sont enregistrés. */
    fun saveHeaders(context: Context, text: String): String? = when (val parsed = HaHeaders.parse(text)) {
        is HaHeaders.Invalid -> parsed.reason
        is HaHeaders.Valid -> {
            prefs(context).edit().putString("entetes", text).apply()
            null
        }
    }

    /**
     * Essaie l'accès sans rien modifier. Pas encore connecté : l'adresse saisie, avec le jeton
     * s'il y en a un. Connecté : chaque voie du webhook, car le jeton n'est plus conservé.
     */
    fun test(context: Context, typedUrl: String, token: String): String =
        if (isConnected(context)) testWebhook(context) else testAddress(context, typedUrl, token)

    private fun testAddress(context: Context, typedUrl: String, token: String): String {
        val url = when (val parsed = HaUrl.parse(typedUrl)) {
            is HaUrl.Valid -> parsed.url
            is HaUrl.Invalid -> return parsed.reason
        }
        val bearer = token.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
        val headers = customHeaders(context)
        val started = System.nanoTime()
        val api = try {
            request(context, "GET", "$url/api/", null, bearer, headers)
        } catch (e: IOException) {
            return networkProblem(e)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val reply = api.reply()
        if (bearer == null) {
            return if (HaDiagnostic.isHomeAssistantRefusal(reply)) {
                "Serveur joint en $ms ms, sans blocage de proxy. Saisis le jeton pour le tester aussi."
            } else {
                HaDiagnostic.problem(reply) ?: "Une page répond à la place de Home Assistant : vérifie l'adresse."
            }
        }
        HaDiagnostic.problem(reply)?.let { return it }
        if (API_MARK !in api.text) return "Une page répond à la place de Home Assistant : vérifie l'adresse."
        val config = try {
            JSONObject(request(context, "GET", "$url/api/config", null, bearer, headers).text)
        } catch (e: IOException) {
            return networkProblem(e)
        } catch (_: JSONException) {
            return "Accès OK en $ms ms, jeton accepté, mais configuration illisible."
        }
        val mobileApp = config.optJSONArray("components")?.let { list ->
            (0 until list.length()).any { list.optString(it) == "mobile_app" }
        } == true
        val version = config.optString("version", "?")
        return "OK en $ms ms : Home Assistant $version, jeton accepté." +
            if (mobileApp) " Tu peux connecter." else " Mais mobile_app est absente : ajoute « default_config: » à la configuration."
    }

    private fun testWebhook(context: Context): String {
        val body = JSONObject().put("type", "get_config").toString()
        return webhookTargets(context).joinToString("\n") { target ->
            val host = try {
                URL(target).host
            } catch (_: MalformedURLException) {
                target
            }
            val line = try {
                val answer = request(context, "POST", target, body, null, headersFor(context, target))
                HaDiagnostic.problem(answer.reply()) ?: when {
                    answer.text.isBlank() -> "webhook inconnu : intégration supprimée, ou serveur en plein démarrage."
                    else -> "OK, Home Assistant ${JSONObject(answer.text).optString("version", "?")}."
                }
            } catch (e: IOException) {
                networkProblem(e)
            } catch (_: JSONException) {
                "Une page répond à la place de Home Assistant."
            }
            "$host : $line"
        }
    }

    private fun networkProblem(e: IOException): String = when (e) {
        is UnknownHostException -> "Nom de serveur introuvable : vérifie l'adresse et le réseau du téléphone."
        is SSLException -> "Connexion sécurisée refusée : certificat invalide ou non reconnu (${e.message})."
        is SocketTimeoutException -> "Pas de réponse à temps : serveur éteint, ou réseau qui ne l'atteint pas."
        is ConnectException -> "Connexion refusée : serveur ou port injoignable."
        else -> "Injoignable : ${e.message}"
    }

    fun connect(context: Context, typedUrl: String, token: String): String {
        val url = when (val parsed = HaUrl.parse(typedUrl)) {
            is HaUrl.Valid -> parsed.url
            is HaUrl.Invalid -> return parsed.reason
        }
        if (token.isBlank()) return "Saisis un jeton longue durée (profil Home Assistant → Sécurité)."
        val store = prefs(context)
        val body = HaPayloads.registration(appVersion(context), Build.VERSION.RELEASE).toString()
        val answer = try {
            request(context, "POST", "$url/api/mobile_app/registrations", body, "Bearer ${token.trim()}", customHeaders(context))
        } catch (e: IOException) {
            return networkProblem(e)
        }
        return when (answer.code) {
            200, 201 -> try {
                val registration = JSONObject(answer.text)
                store.edit()
                    .putString("adresse", url)
                    .putString("webhook", registration.getString("webhook_id"))
                    .putString("cloudhook", registration.optString("cloudhook_url").takeUnless { registration.isNull("cloudhook_url") })
                    .putString("distant", registration.optString("remote_ui_url").takeUnless { registration.isNull("remote_ui_url") })
                    .remove("publie")
                    .remove("capteurs_version")
                    .commit()
                publish(context, TransferScheduler.isProjecting(context), force = true).message
            } catch (e: JSONException) {
                "Réponse illisible de Home Assistant : ${e.message}"
            }
            404 -> "Intégration mobile_app absente : ajoute « default_config: » ou « mobile_app: » à la configuration."
            else -> HaDiagnostic.problem(answer.reply()) ?: "Réponse inattendue de Home Assistant : ${answer.code}"
        }
    }

    /** Seulement sur ce téléphone : l'appareil reste dans Home Assistant, à supprimer depuis ses réglages. */
    fun disconnect(context: Context) {
        prefs(context).edit().remove("webhook").remove("cloudhook").remove("distant").remove("publie").remove("resultat").remove("webhook_inconnu")
            .remove("dernier_envoi").remove("dernier_envoi_ok").remove("position_envoyee").commit()
    }

    /**
     * Envoie le dernier état connu. Sans rien de neuf et sans [force], n'envoie rien : le suivi
     * force un envoi de temps en temps, pour que Home Assistant voie la liaison vivre.
     */
    fun publish(context: Context, projecting: Boolean, force: Boolean = false): Published {
        val store = prefs(context)
        if (!store.contains("webhook")) return Published(false, false, "Home Assistant non connecté.")
        val summary = store.getString("resume", null) ?: "{}"
        val linked = store.getBoolean("liee", false)
        val contact = store.getLong("contact", 0).takeIf { it > 0 }
        // Sans l'heure du contact : elle change à chaque interrogation de l'Atoto.
        val fingerprint = "$projecting|$linked|$summary"
        // Un événement ou une position en attente part même sans autre changement.
        val extras = pendingEvents(context).length() > 0 || newLocation(context, summary) != null
        if (!force && !extras && store.getString("publie", null) == fingerprint) return Published(true, false, lastResult(context))
        var ok = false
        val message = try {
            send(context, HaSnapshot(JSONObject(summary), projecting, linked, contact))?.also {
                ok = true
                store.edit().putString("publie", fingerprint).apply()
            } ?: "L'intégration a été supprimée dans Home Assistant : à reconnecter."
        } catch (e: IOException) {
            // Même sans rien de neuf, le prochain essai doit renvoyer : ce message serait sinon périmé.
            store.edit().remove("publie").apply()
            "Envoi impossible, nouvel essai plus tard : ${e.message}"
        } catch (e: JSONException) {
            "Réponse illisible de Home Assistant : ${e.message}"
        }
        // Les échecs, et le retour à la normale : pas chaque envoi réussi.
        if (!ok || !store.getBoolean("dernier_envoi_ok", true)) CompanionLog.add(context, "Home Assistant : $message")
        val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        val result = "$at · $message"
        store.edit().putString("resultat", result).putBoolean("dernier_envoi_ok", ok).apply {
            if (ok) putLong("dernier_envoi", System.currentTimeMillis())
        }.apply()
        return Published(ok, true, result)
    }

    /** Null si Home Assistant ne reconnaît plus le webhook. */
    private fun send(context: Context, snapshot: HaSnapshot): String? {
        val store = prefs(context)
        // Une mise à jour de l'app peut ajouter des capteurs : on les décrit de nouveau.
        val version = appVersion(context)
        if (store.getString("capteurs_version", null) != version) {
            for (sensor in HaPayloads.sensors) {
                val answer = postWebhook(context, HaPayloads.registerSensor(sensor, snapshot).toString())
                if (answer.code == GONE) return forget(context)
                if (answer.code !in 200..299) throw IOException(HaDiagnostic.problem(answer.reply()) ?: "capteur ${sensor.id} : ${answer.code}")
            }
            store.edit().putString("capteurs_version", version).apply()
        }
        val answer = postWebhook(context, HaPayloads.updateStates(snapshot).toString())
        if (answer.code == GONE) return forget(context)
        if (answer.code !in 200..299) throw IOException(HaDiagnostic.problem(answer.reply()) ?: "mise à jour : ${answer.code}")
        // Home Assistant répond 200 sans contenu à un webhook qu'il ne connaît pas : intégration
        // supprimée, ou serveur en plein démarrage. D'où plusieurs essais avant d'abandonner.
        if (answer.text.isBlank()) {
            val unknown = store.getInt("webhook_inconnu", 0) + 1
            if (unknown >= UNKNOWN_WEBHOOK_LIMIT) return forget(context)
            store.edit().putInt("webhook_inconnu", unknown).apply()
            throw IOException("webhook inconnu de Home Assistant")
        }
        store.edit().remove("webhook_inconnu").apply()
        // Capteur retiré à la main dans Home Assistant : on le réinscrit.
        for (sensor in HaPayloads.notRegistered(JSONObject(answer.text))) {
            postWebhook(context, HaPayloads.registerSensor(sensor, snapshot).toString())
        }
        val located = sendLocation(context, snapshot.summary)
        val fired = sendEvents(context)
        return "OK, ${HaPayloads.sensors.size} capteurs à jour" +
            (if (located) ", position" else "") +
            (if (fired > 0) ", $fired événement${if (fired > 1) "s" else ""}" else "") + "."
    }

    /** Les événements en attente, dans l'ordre ; un échec garde le reste pour le prochain envoi. */
    @Synchronized
    private fun sendEvents(context: Context): Int {
        val pending = pendingEvents(context)
        var sent = 0
        while (sent < pending.length()) {
            val event = pending.getJSONObject(sent)
            val answer = postWebhook(context, HaPayloads.fireEvent(event.getString("type"), event.getJSONObject("data")).toString())
            if (answer.code !in 200..299) break
            sent++
        }
        if (sent > 0) {
            val rest = JSONArray()
            for (i in sent until pending.length()) rest.put(pending.get(i))
            prefs(context).edit().putString("evenements", rest.toString()).apply()
        }
        return sent
    }

    /** La position de la voiture, si l'utilisateur l'a choisi et qu'elle a bougé depuis le dernier envoi. */
    private fun sendLocation(context: Context, summary: JSONObject): Boolean {
        val location = newLocation(context, summary.toString()) ?: return false
        val answer = postWebhook(context, location.toString())
        if (answer.code !in 200..299) return false
        prefs(context).edit().putString("position_envoyee", location.getJSONObject("data").getJSONArray("gps").toString()).apply()
        return true
    }

    private fun newLocation(context: Context, summary: String): JSONObject? {
        if (!TransferPrefs(context).shareCarPosition) return null
        val location = parse(summary)?.let(HaPayloads::location) ?: return null
        val gps = location.getJSONObject("data").getJSONArray("gps").toString()
        return location.takeIf { gps != prefs(context).getString("position_envoyee", null) }
    }

    private fun forget(context: Context): String? {
        disconnect(context)
        return null
    }

    /** L'adresse du cloud ou l'accès distant passent sur la route, l'adresse saisie à la maison. */
    private fun webhookTargets(context: Context): List<String> {
        val store = prefs(context)
        val webhook = store.getString("webhook", null) ?: return emptyList()
        return listOfNotNull(
            store.getString("cloudhook", null),
            store.getString("distant", null)?.let { "${it.trimEnd('/')}/api/webhook/$webhook" },
            store.getString("adresse", null)?.let { "$it/api/webhook/$webhook" },
        )
    }

    private fun postWebhook(context: Context, body: String): Answer {
        var failure: IOException? = null
        for (target in webhookTargets(context)) {
            try {
                return request(context, "POST", target, body, null, headersFor(context, target))
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("aucune adresse")
    }

    private fun customHeaders(context: Context): List<Pair<String, String>> =
        (HaHeaders.parse(headersText(context)) as? HaHeaders.Valid)?.headers.orEmpty()

    private fun headersFor(context: Context, target: String): List<Pair<String, String>> =
        if (HaHeaders.sameServer(target, address(context))) customHeaders(context) else emptyList()

    private class Answer(val code: Int, val text: String, val location: String?, val cloudflare: Boolean) {
        fun reply() = HaDiagnostic.Reply(code, text, location, cloudflare)
    }

    private fun request(
        context: Context,
        method: String,
        url: String,
        body: String?,
        authorization: String?,
        headers: List<Pair<String, String>>,
    ): Answer {
        val target = URL(url)
        val network = if (HaUrl.isLocal(target.host)) null else internetNetwork(context)
        val connection = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // Une redirection pourrait emporter le jeton et les en-têtes ailleurs.
            connection.instanceFollowRedirects = false
            // L'identité par défaut de la plateforme est souvent bloquée par les pare-feu applicatifs.
            connection.setRequestProperty("User-Agent", "CANCitroen-Compagnon/${appVersion(context)}")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Answer(
                code,
                stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty(),
                connection.getHeaderField("Location"),
                connection.getHeaderField("CF-Ray") != null ||
                    connection.getHeaderField("Server").equals("cloudflare", ignoreCase = true),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Un réseau qui mène vraiment à Internet. Le Wi-Fi de l'Atoto n'y mène pas, et certains
     * téléphones le prennent quand même comme réseau par défaut : on passe alors par le mobile.
     * Null : aucun réseau validé, on laisse faire le système.
     */
    private fun internetNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun reachesInternet(network: Network) = connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        return connectivity.activeNetwork?.takeIf(::reachesInternet)
            ?: connectivity.allNetworks.firstOrNull(::reachesInternet)
    }

    private fun appVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
