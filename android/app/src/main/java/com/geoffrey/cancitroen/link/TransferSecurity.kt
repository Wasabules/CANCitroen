package com.geoffrey.cancitroen.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Sécurité du transfert. Le téléphone épingle le certificat de l'Atoto à l'association :
 * il prouve qu'il connaît le code avec un HMAC sur l'empreinte du certificat qu'il voit.
 * Le code ne passe donc jamais sur le réseau, et une preuve obtenue à travers un
 * intermédiaire, calculée sur le certificat de celui-ci, serait refusée par l'Atoto.
 * Il reçoit en échange un jeton de 256 bits, utilisé ensuite sous TLS épinglé.
 */
internal object TransferSecurity {
    fun proof(code: String, fingerprint: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return hex(mac.doFinal(fingerprint))
    }

    fun newToken(): String = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun sha256Hex(text: String): String = hex(sha256(text.toByteArray()))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sameText(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * L'Atoto est la passerelle de son point d'accès (`.1` sur une adresse privée) : les
     * connexions reçues sur un Wi-Fi qu'il aurait rejoint comme client sont refusées.
     */
    fun isOwnAccessPoint(local: InetAddress): Boolean =
        local.isLoopbackAddress || (local is Inet4Address && local.isSiteLocalAddress && local.address[3] == 1.toByte())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Certificat du transfert : la clé privée reste dans le magasin de clés Android. */
internal class TlsIdentity private constructor(
    val serverSockets: ServerSocketFactory,
    val fingerprint: ByteArray,
) {
    companion object {
        private const val ALIAS = "cancitroen-transfert"
        private const val VALIDITY_MS = 30L * 365L * 24L * 3600_000L

        /** Crée la clé au premier appel : à appeler hors du thread principal. */
        fun load(): TlsIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) generate()
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            return TlsIdentity(
                context.serverSocketFactory,
                TransferSecurity.sha256(store.getCertificate(ALIAS).encoded),
            )
        }

        private fun generate() {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=CANCitroen"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24L * 3600_000L))
                .setCertificateNotAfter(Date(now + VALIDITY_MS))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
