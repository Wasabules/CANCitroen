package com.geoffrey.cancitroen.shared

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Le protocole entre l'Atoto (serveur, `link/LinkServer`) et le compagnon (client) : HTTP/1.0,
 * une connexion par requête, jeton en `Authorization: Bearer` sur le port chiffré.
 * Un seul endroit, pour qu'une évolution d'un côté ne casse pas l'autre en silence.
 */
object LinkProtocol {
    /** Test de liaison, en clair : aucune donnée véhicule. */
    const val PROBE_PORT = 8765

    /** Transfert, en TLS épinglé. */
    const val TLS_PORT = 8766

    /** Lignes d'échantillons au plus par lot. */
    const val MAX_BATCH = 5_000

    const val PING = "/ping"
    const val BLOB = "/blob"
    const val STATUS = "/telemetry/status"
    const val SUMMARY = "/telemetry/summary"
    const val INVENTORY = "/telemetry/inventory"
    const val SAMPLES = "/telemetry/samples"
    const val AGGREGATES = "/telemetry/aggregates"
    const val TRIPS = "/telemetry/trips"
    const val REFUELS = "/telemetry/refuels"
    const val ACK = "/telemetry/ack"
    const val PAIR = "/telemetry/pair"

    /** Paramètre de [SUMMARY] : ajoute la position GPS de l'Atoto, seulement si le téléphone la demande. */
    const val WITH_POSITION = "position"

    /** Nombre de lignes d'un lot d'échantillons, et numéro de la dernière. */
    const val HEADER_COUNT = "X-Count"
    const val HEADER_LAST_ID = "X-Last-Id"

    /**
     * Prouve la connaissance du code d'association sans l'envoyer : HMAC-SHA256 du certificat
     * vu par le téléphone. Une preuve calculée à travers un intermédiaire porte sur le
     * certificat de celui-ci, et l'Atoto la refuse.
     */
    fun pairingProof(code: String, fingerprint: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return mac.doFinal(fingerprint).joinToString("") { "%02x".format(it) }
    }
}
