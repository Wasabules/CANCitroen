package com.geoffrey.cancitroen.swc

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shell `su` persistant. Spawn une seule fois pour la vie du process app, on
 * lui pipe les commandes en stdin au fil de l'eau.
 *
 * Économie par appel comparé à `Runtime.exec("su -c \"...\"")` :
 *  - fork process              ~5  ms
 *  - su → Magisk daemon auth   ~30 ms (caché après 1er appel mais quand même)
 *  - démarrage shell           ~10 ms
 *  Total skippé : ~50 ms/appel.
 *
 * Reste à payer pour chaque commande : le coût de la commande elle-même.
 * Pour `input keyevent` c'est ~150 ms (démarrage JVM Dalvik) ; pour
 * `sendevent` c'est ~5 ms (juste un write syscall). D'où l'intérêt
 * supplémentaire de [RootKeyInjector].
 *
 * Fire-and-forget : pas de capture stdout côté API publique (drainée en
 * arrière-plan pour pas bloquer sur pipe full). Pour récupérer un résultat
 * — par exemple le probe `/proc/bus/input/devices` — utiliser [execOneShot]
 * qui spawn un process distinct, attend la fin, et capture stdout.
 */
object RootShell {

    private const val TAG = "RootShell"
    private val lock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null

    /** True si une session `su` est ouverte et fonctionnelle. */
    val isAvailable: Boolean get() = process?.isAlive == true

    @Volatile private var rootProbed = false
    @Volatile private var rootOk = false

    /**
     * Vérifie qu'on obtient bien un shell uid=0. Résultat caché (le root ne
     * change pas au cours de la vie du process, sauf si l'utilisateur vient
     * d'accorder Magisk → passer [force]=true après une action utilisateur).
     */
    fun probe(force: Boolean = false): Boolean {
        if (rootProbed && !force) return rootOk
        val out = execOneShot("id", 3_000L)
        rootOk = out?.contains("uid=0") == true
        rootProbed = true
        Log.i(TAG, "root probe → ${if (rootOk) "OK (uid=0)" else "indisponible"}")
        return rootOk
    }

    /**
     * Envoie [cmd] sur stdin du shell persistant (line-terminated).
     * Ne bloque pas sur l'exécution — la commande tourne en parallèle.
     *
     * Si la session est morte (Magisk redémarré, app suspendue longtemps), on
     * essaye de la respawn une fois. Échec silencieux sinon (log Warning).
     */
    fun exec(cmd: String) {
        synchronized(lock) {
            if (!ensureLocked()) return
            try {
                val s = stdin!!
                s.write((cmd + "\n").toByteArray())
                s.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "exec failed, marking dead: ${e.message}")
                // Force respawn au prochain appel
                process = null
                stdin = null
            }
        }
    }

    /**
     * Spawn `su -c <cmd>` (process distinct, indépendant de la session
     * persistante), attend la fin, retourne stdout (stderr fusionné).
     *
     * Bloquant. Réservé à l'init / probe rare. `null` si timeout ou pas de su.
     */
    fun execOneShot(cmd: String, timeoutMs: Long = 2000L): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                Log.w(TAG, "execOneShot timeout: $cmd")
                return null
            }
            p.inputStream.bufferedReader().readText()
        } catch (e: Throwable) {
            Log.w(TAG, "execOneShot failed ($cmd): ${e.message}")
            null
        }
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** Suppose le verrou détenu. Reconnecte si nécessaire. */
    private fun ensureLocked(): Boolean {
        process?.takeIf { it.isAlive }?.let { return true }
        return try {
            val p = Runtime.getRuntime().exec("su")
            // Drain stdout + stderr en arrière-plan, sinon un pipe plein (64 KB
            // par défaut sur Linux) bloque les writes futurs sur stdin. En
            // pratique nos commandes n'écrivent rien sur stdout, mais on est
            // sûrs de pas se faire piéger.
            Thread({ drain(p.inputStream, false) }, "RootShell-stdout")
                .apply { isDaemon = true }.start()
            Thread({ drain(p.errorStream, true)  }, "RootShell-stderr")
                .apply { isDaemon = true }.start()
            process = p
            stdin = p.outputStream
            Log.i(TAG, "su persistent shell started")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "su unavailable: ${e.message}")
            process = null
            stdin = null
            false
        }
    }

    private fun drain(stream: InputStream, asError: Boolean) {
        try {
            stream.bufferedReader().forEachLine { line ->
                if (asError && line.isNotBlank()) Log.w(TAG, "[su stderr] $line")
            }
        } catch (_: Throwable) {
            // Pipe fermé = process mort, on sort silencieusement.
        }
    }
}
