package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un capteur publié dans Home Assistant : sa description et comment lire sa valeur dans le résumé. */
class HaSensor(
    val id: String,
    val name: String,
    val icon: String,
    val unit: String? = null,
    val deviceClass: String? = null,
    val stateClass: String? = null,
    val binary: Boolean = false,
    val diagnostic: Boolean = false,
    val attributes: (JSONObject) -> Map<String, Any?> = { emptyMap() },
    val state: (HaSnapshot) -> Any?,
)

/**
 * Le résumé de l'Atoto, et ce que seul le téléphone sait : Android Auto est-il connecté,
 * l'Atoto répond-il ([linked]) et quand a-t-il répondu pour la dernière fois ([contactTs]) ?
 */
class HaSnapshot(
    val summary: JSONObject,
    val projecting: Boolean,
    val linked: Boolean = false,
    val contactTs: Long? = null,
) {
    fun number(key: String): Double? = if (summary.isNull(key)) null else summary.optDouble(key).takeUnless { it.isNaN() }
    fun rounded(key: String, decimals: Int): Double? = number(key)?.let { HaPayloads.round(it, decimals) }
    fun whole(key: String): Long? = number(key)?.toLong()
    fun time(key: String): String? = whole(key)?.let { HaPayloads.isoTime(it) }
}

/** Les messages de l'API `mobile_app`, sans dépendance à Android. */
object HaPayloads {
    const val UNIQUE_PREFIX = "cancitroen_"

    private fun fuel(decimals: Int = 2) = { key: String -> { s: HaSnapshot -> s.rounded(key, decimals) } }

    val sensors: List<HaSensor> = listOf(
        HaSensor("jauge", "Jauge", "mdi:gas-station", "%", stateClass = "measurement") { it.whole(F.FUEL_PCT) },
        HaSensor("carburant", "Carburant", "mdi:fuel", "L", "volume_storage", "measurement", state = fuel(1)(F.FUEL_LITERS)),
        HaSensor("autonomie", "Autonomie", "mdi:map-marker-distance", "km", "distance", "measurement") { it.whole(F.RANGE) },
        HaSensor("odometre", "Odomètre", "mdi:counter", "km", "distance", "total_increasing") { it.rounded(F.ODO, 1) },
        HaSensor("temperature_eau", "Température eau", "mdi:coolant-temperature", "°C", "temperature", "measurement") { it.whole(F.COOLANT) },
        HaSensor("temperature_huile", "Température huile", "mdi:oil-temperature", "°C", "temperature", "measurement") { it.whole(F.OIL) },
        HaSensor("temperature_exterieure", "Température extérieure", "mdi:thermometer", "°C", "temperature", "measurement") { it.whole(F.OUTSIDE) },
        HaSensor("niveau_huile", "Niveau huile", "mdi:oil-level", "%", stateClass = "measurement") { it.whole(F.OIL_LEVEL) },
        HaSensor("dernier_releve", "Dernier relevé", "mdi:clock-check-outline", deviceClass = "timestamp", diagnostic = true) { it.time(F.LAST_SEEN) },
        HaSensor(
            "dernier_trajet", "Dernier trajet", "mdi:map-marker-path", "km", "distance", "measurement",
            attributes = ::tripAttributes,
        ) { it.rounded(F.TRIP_KM, 1) },
        HaSensor("conso_dernier_trajet", "Conso dernier trajet", "mdi:speedometer", "L/100km", stateClass = "measurement") { it.rounded(F.TRIP_CONS, 1) },
        HaSensor(
            "dernier_plein", "Dernier plein", "mdi:gas-station-outline", deviceClass = "timestamp",
            attributes = ::refuelAttributes,
        ) { it.time(F.REFUEL_TS) },
        HaSensor("prix_litre", "Prix du litre", "mdi:currency-eur", "EUR/L", stateClass = "measurement") { it.rounded(F.REFUEL_PRICE, 3) },
        HaSensor("distance_mois", "Distance du mois", "mdi:road-variant", "km", "distance", "total_increasing") { it.rounded(F.MONTH_KM, 1) },
        HaSensor("trajets_mois", "Trajets du mois", "mdi:car-multiple", stateClass = "total_increasing") { it.whole(F.MONTH_TRIPS) },
        HaSensor("conso_mois", "Conso du mois", "mdi:speedometer-medium", "L/100km", stateClass = "measurement") { it.rounded(F.MONTH_CONS, 1) },
        HaSensor("carburant_mois", "Carburant du mois", "mdi:fuel", "L", "volume", "total_increasing") { it.rounded(F.MONTH_LITERS, 1) },
        HaSensor("depense_mois", "Dépense du mois", "mdi:cash-multiple", "EUR", "monetary", "total") { it.rounded(F.MONTH_COST, 2) },
        HaSensor("android_auto", "Android Auto", "mdi:car-connected", deviceClass = "connectivity", binary = true) { it.projecting },
        // Hors de portée de l'Atoto, on ne sait plus rien du trajet : il n'est plus « en cours ».
        HaSensor("trajet_en_cours", "Trajet en cours", "mdi:car-traction-control", deviceClass = "moving", binary = true) {
            it.linked && it.summary.optBoolean(F.TRIP_ACTIVE)
        },
        HaSensor("voiture_connectee", "Voiture connectée", "mdi:car-wireless", deviceClass = "connectivity", binary = true) { it.linked },
        HaSensor("dernier_contact", "Dernier contact", "mdi:access-point-network", deviceClass = "timestamp", diagnostic = true) {
            it.contactTs?.let(HaPayloads::isoTime)
        },
    )

    /**
     * Identifiant fixe : la voiture reste le même appareil pour Home Assistant, qui remplace
     * l'inscription précédente après une réinstallation. Sinon ses capteurs deviendraient
     * `..._2` et le tableau de bord ne les trouverait plus.
     */
    fun registration(appVersion: String, osVersion: String): JSONObject = JSONObject().apply {
        put("device_id", "cancitroen-c2")
        put("app_id", "com.geoffrey.cancitroen.companion")
        put("app_name", "CANCitroen")
        put("app_version", appVersion)
        put("device_name", "Citroën C2")
        put("manufacturer", "Citroën")
        put("model", "C2")
        put("os_name", "Android")
        put("os_version", osVersion)
        put("supports_encryption", false)
    }

    fun registerSensor(sensor: HaSensor, snapshot: HaSnapshot): JSONObject = JSONObject().apply {
        put("type", "register_sensor")
        put("data", stateOf(sensor, snapshot).apply {
            put("name", sensor.name)
            sensor.unit?.let { put("unit_of_measurement", it) }
            sensor.deviceClass?.let { put("device_class", it) }
            sensor.stateClass?.let { put("state_class", it) }
            if (sensor.diagnostic) put("entity_category", "diagnostic")
        })
    }

    fun updateStates(snapshot: HaSnapshot, only: List<HaSensor> = sensors): JSONObject = JSONObject().apply {
        put("type", "update_sensor_states")
        put("data", JSONArray().apply { only.forEach { put(stateOf(it, snapshot)) } })
    }

    /** Les capteurs que Home Assistant ne connaît pas, d'après sa réponse à une mise à jour. */
    fun notRegistered(answer: JSONObject): List<HaSensor> = sensors.filter { sensor ->
        answer.optJSONObject(UNIQUE_PREFIX + sensor.id)?.optJSONObject("error")?.optString("code") == "not_registered"
    }

    private fun stateOf(sensor: HaSensor, snapshot: HaSnapshot) = JSONObject().apply {
        put("unique_id", UNIQUE_PREFIX + sensor.id)
        put("type", if (sensor.binary) "binary_sensor" else "sensor")
        put("icon", sensor.icon)
        put("state", sensor.state(snapshot) ?: JSONObject.NULL)
        val attributes = sensor.attributes(snapshot.summary).filterValues { it != null }
        if (attributes.isNotEmpty()) put("attributes", JSONObject(attributes))
    }

    private fun tripAttributes(summary: JSONObject): Map<String, Any?> {
        val snapshot = HaSnapshot(summary, false)
        val start = snapshot.whole(F.TRIP_START)
        val end = snapshot.whole(F.TRIP_END)
        return mapOf(
            "depart" to snapshot.time(F.TRIP_START),
            "arrivee" to snapshot.time(F.TRIP_END),
            "duree_min" to if (start != null && end != null) (end - start) / 60_000 else null,
            "vitesse_moyenne_kmh" to snapshot.rounded(F.TRIP_AVG_SPEED, 0),
            "vitesse_max_kmh" to snapshot.rounded(F.TRIP_MAX_SPEED, 0),
        )
    }

    private fun refuelAttributes(summary: JSONObject): Map<String, Any?> {
        val snapshot = HaSnapshot(summary, false)
        return mapOf(
            "litres" to snapshot.rounded(F.REFUEL_LITERS, 2),
            "montant_eur" to snapshot.rounded(F.REFUEL_COST, 2),
            "prix_litre_eur" to snapshot.rounded(F.REFUEL_PRICE, 3),
            "estimation" to if (summary.has(F.REFUEL_TS)) summary.optBoolean(F.REFUEL_ESTIMATED) else null,
        )
    }

    /** Un événement sur le bus de Home Assistant, pour les automatisations. */
    fun fireEvent(type: String, data: JSONObject): JSONObject = JSONObject().apply {
        put("type", "fire_event")
        put("data", JSONObject().put("event_type", type).put("event_data", data))
    }

    /**
     * La position de la voiture pour `device_tracker.citroen_c2`, si le résumé en porte une.
     * La précision est exigée par Home Assistant : 10 m par défaut, celle d'un GPS dégagé.
     */
    fun location(summary: JSONObject): JSONObject? {
        if (!summary.has(F.LAT) || !summary.has(F.LON)) return null
        val lat = summary.optDouble(F.LAT)
        val lon = summary.optDouble(F.LON)
        if (lat.isNaN() || lon.isNaN()) return null
        return JSONObject().apply {
            put("type", "update_location")
            put("data", JSONObject().apply {
                put("gps", JSONArray().put(round(lat, 6)).put(round(lon, 6)))
                put("gps_accuracy", summary.optDouble(F.GPS_ACCURACY).takeUnless { it.isNaN() }?.toInt() ?: 10)
                summary.optDouble(F.GPS_BEARING).takeUnless { it.isNaN() }?.let { put("course", it.toInt()) }
            })
        }
    }

    fun round(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return Math.round(value * factor) / factor
    }

    fun isoTime(ts: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date(ts))
}

/** Lecture de l'adresse saisie. Refuse d'envoyer le jeton en clair ailleurs que sur le réseau local. */
object HaUrl {
    private val URL = Regex("""^(https?)://([^/:\s?#]+)(:\d{1,5})?/?$""", RegexOption.IGNORE_CASE)

    sealed interface Result
    data class Valid(val url: String) : Result
    data class Invalid(val reason: String) : Result

    fun parse(typed: String): Result {
        val text = typed.trim().let { if ("://" in it) it else "https://$it" }
        val match = URL.matchEntire(text)
            ?: return Invalid("Adresse attendue : https://ma-maison.example ou http://192.168.1.10:8123")
        val scheme = match.groupValues[1].lowercase()
        val host = match.groupValues[2].lowercase()
        if (scheme == "http" && !isLocal(host)) {
            return Invalid("Le jeton passerait en clair sur Internet : utilise une adresse https.")
        }
        return Valid("$scheme://$host${match.groupValues[3]}")
    }

    fun isLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
