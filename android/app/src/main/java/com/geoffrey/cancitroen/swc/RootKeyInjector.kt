package com.geoffrey.cancitroen.swc

import android.util.Log
import android.view.KeyEvent

/**
 * Injecte un [KeyEvent] Android avec un minimum de latence, via root.
 *
 *  - **Fast path (~5-15 ms)** : `sendevent` direct sur `/dev/input/eventN`.
 *    Le kernel input subsystem reçoit (EV_KEY down, EV_SYN, EV_KEY up,
 *    EV_SYN) et propage immédiatement. Évite le démarrage de la JVM Dalvik
 *    que fait le binaire `input` (~150 ms).
 *
 *  - **Fallback (~150 ms)** : `input keyevent N` via shell persistant. Utilisé
 *    quand aucun device input du kernel n'expose le keycode demandé — ce qui
 *    est très courant sur ROM Atoto où seuls volume/play-pause sont câblés
 *    aux devices virtuels (next/prev/home/back/dpad passent obligatoirement
 *    par le framework Android).
 *
 * Toutes les commandes passent par [RootShell] (shell `su` partagé entre
 * appels), donc on évite aussi le coût de spawn de `su` à chaque touche.
 *
 * ### Pourquoi du "par keycode" et pas un device global ?
 *
 * Le kernel input filtre **silencieusement** un sendevent si le code émis
 * n'est pas dans le bitmap `B: KEY=` du device cible. Sur A6PF :
 *
 *     event2 "gpio-keys"             → KEY_POWER seulement
 *     event4 "sprdphone Headset Kbd" → VOLUMEUP, VOLUMEDOWN, MEDIA(=play/pause)
 *
 * Donc on doit :
 *  1. Parser le bitmap KEY de chaque device au probe.
 *  2. Pour chaque keycode Android, chercher le **premier** device qui supporte
 *     un Linux code candidat (parfois plusieurs valent : ex. PLAY_PAUSE peut
 *     marcher comme KEY_PLAYPAUSE=164 OU KEY_MEDIA=226 selon le firmware).
 *  3. À l'inject : si on a un (device, linuxCode) → fast path, sinon
 *     fallback `input keyevent`.
 */
object RootKeyInjector {

    private const val TAG = "RootKeyInjector"

    /**
     * Pour chaque KeyEvent Android, la liste des Linux input codes à essayer
     * (ordonnée du plus pertinent au moins pertinent). Le probe choisira le
     * premier qui est exposé par un device input du kernel.
     *
     * Source des codes : include/uapi/linux/input-event-codes.h.
     */
    private val CANDIDATES: Map<Int, List<Int>> = mapOf(
        KeyEvent.KEYCODE_VOLUME_UP          to listOf(115),       // KEY_VOLUMEUP
        KeyEvent.KEYCODE_VOLUME_DOWN        to listOf(114),       // KEY_VOLUMEDOWN
        KeyEvent.KEYCODE_VOLUME_MUTE        to listOf(113),       // KEY_MUTE
        // PLAY_PAUSE : KEY_PLAYPAUSE en standard. Sur ROM Atoto/Sprd c'est
        // souvent KEY_MEDIA (226) qui est câblé sur le device "Headset
        // Keyboard" (héritage Linux ALSA jack detection).
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE   to listOf(164, 226),  // PLAYPAUSE, MEDIA
        KeyEvent.KEYCODE_MEDIA_PLAY         to listOf(200, 164),  // PLAYCD, PLAYPAUSE
        KeyEvent.KEYCODE_MEDIA_PAUSE        to listOf(201, 164),  // PAUSECD, PLAYPAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT         to listOf(163),       // KEY_NEXTSONG
        KeyEvent.KEYCODE_MEDIA_PREVIOUS     to listOf(165),       // KEY_PREVIOUSSONG
        KeyEvent.KEYCODE_MEDIA_STOP         to listOf(166),       // KEY_STOPCD
        KeyEvent.KEYCODE_MEDIA_REWIND       to listOf(168),       // KEY_REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to listOf(208),       // KEY_FASTFORWARD
        // HEADSETHOOK Android = KEY_MEDIA (226) sur la plupart des KCM Android.
        KeyEvent.KEYCODE_HEADSETHOOK        to listOf(226, 164),
        KeyEvent.KEYCODE_VOICE_ASSIST       to listOf(582),       // KEY_VOICECOMMAND
        KeyEvent.KEYCODE_CALL               to listOf(169),       // KEY_PHONE
        KeyEvent.KEYCODE_ENDCALL            to listOf(107),       // KEY_END
        KeyEvent.KEYCODE_HOME               to listOf(172),       // KEY_HOMEPAGE
        KeyEvent.KEYCODE_BACK               to listOf(158),       // KEY_BACK
        KeyEvent.KEYCODE_MENU               to listOf(139),       // KEY_MENU
        KeyEvent.KEYCODE_DPAD_UP            to listOf(103),       // KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN          to listOf(108),       // KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT          to listOf(105),       // KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT         to listOf(106),       // KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER        to listOf(28),        // KEY_ENTER
    )

    private data class FastTarget(val device: String, val linuxCode: Int)

    @Volatile private var probed = false
    @Volatile private var keycodeTarget: Map<Int, FastTarget> = emptyMap()

    /** Lance l'injection. Retourne immédiatement. */
    fun inject(androidKeycode: Int) {
        ensureProbed()
        val target = keycodeTarget[androidKeycode]
        if (target != null) {
            // Fast path. 4 events séparés par ';' (continue même si l'un
            // échoue — évite qu'une erreur transitoire bloque l'UP).
            //   EV_KEY (type=1) code=linux value=1  → down
            //   EV_SYN (type=0) code=0     value=0  → flush
            //   EV_KEY (type=1) code=linux value=0  → up
            //   EV_SYN (type=0) code=0     value=0  → flush
            val dev = target.device
            val lc = target.linuxCode
            RootShell.exec(
                "sendevent $dev 1 $lc 1;" +
                "sendevent $dev 0 0 0;" +
                "sendevent $dev 1 $lc 0;" +
                "sendevent $dev 0 0 0"
            )
        } else {
            // Fallback — via shell persistant donc on économise au moins le
            // fork/auth `su`. Reste ~150 ms (démarrage JVM `input`).
            RootShell.exec("input keyevent $androidKeycode")
        }
    }

    // ── Probe ──────────────────────────────────────────────────────────

    /** Sonde anticipée (hors chemin critique), pour que le premier appui soit instantané. */
    fun warmUp() = ensureProbed()

    /**
     * Parse `/proc/bus/input/devices` une fois, construit la map
     * keycode→FastTarget. Si on n'a pas pu lire le fichier (pas de root,
     * timeout su) → la map reste vide, tout part en fallback.
     */
    @Synchronized
    private fun ensureProbed() {
        if (probed) return
        probed = true
        val raw = RootShell.execOneShot("cat /proc/bus/input/devices") ?: run {
            Log.w(TAG, "probe: /proc/bus/input/devices unreadable — fallback only")
            return
        }
        val devices = parseDevices(raw)
        if (devices.isEmpty()) {
            Log.w(TAG, "probe: no input devices parsed — fallback only")
            return
        }
        val resolved = mutableMapOf<Int, FastTarget>()
        for ((android, candidates) in CANDIDATES) {
            for (lc in candidates) {
                val dev = devices.firstOrNull { lc in it.keys } ?: continue
                resolved[android] = FastTarget(dev.path, lc)
                break
            }
        }
        keycodeTarget = resolved
        val accel = resolved.size
        val total = CANDIDATES.size
        Log.i(TAG, "probe: $accel/$total keys accelerated via sendevent")
        for ((android, target) in resolved) {
            Log.i(TAG, "  android=$android → ${target.device} linux=${target.linuxCode}")
        }
    }

    private data class InputDevice(val name: String, val path: String, val keys: Set<Int>)

    /**
     * Parse le format `/proc/bus/input/devices` :
     *
     *     I: Bus=…
     *     N: Name="gpio-keys"
     *     H: Handlers=… event2 …
     *     B: KEY=10000000000000 0
     *
     * Les blocs sont séparés par une ligne vide. On extrait Name, path
     * `/dev/input/eventN`, et le set de keycodes exposés (bitmap KEY).
     */
    private fun parseDevices(raw: String): List<InputDevice> {
        val devices = mutableListOf<InputDevice>()
        var name = ""
        var event: String? = null
        var keys: Set<Int> = emptySet()
        fun flush() {
            if (event != null) devices += InputDevice(name, "/dev/input/$event", keys)
            name = ""; event = null; keys = emptySet()
        }
        for (line in raw.lineSequence()) {
            when {
                line.isBlank() -> flush()
                line.startsWith("N: Name=") ->
                    name = line.substringAfter("Name=").trim().trim('"')
                line.startsWith("H: Handlers=") -> {
                    event = line.substringAfter("Handlers=").split(' ')
                        .firstOrNull { it.startsWith("event") }
                }
                line.startsWith("B: KEY=") ->
                    keys = parseKeyBitmap(line.substringAfter("KEY=").trim())
            }
        }
        flush()
        return devices
    }

    /**
     * Bitmap d'une ligne `B: KEY=…` : liste de mots hex 64-bit séparés par
     * espaces, **rightmost = bits les plus bas** (convention kernel).
     *
     * Exemple : "18 400000000 0 c000000000000 0"
     *   word[0] = 0                  → bits 0-63
     *   word[1] = 0xc000000000000    → bits 64-127, bits 50,51 set
     *                                  → keycodes 114, 115 (VOL DOWN, VOL UP)
     *   word[3] = 0x400000000        → bits 192-255, bit 34 set
     *                                  → keycode 226 (KEY_MEDIA)
     *
     * Les nombres font potentiellement plus de 16 chiffres hex (jusqu'à 64
     * bits = 16 hex), donc on parse en ULong.
     */
    private fun parseKeyBitmap(rawHex: String): Set<Int> {
        if (rawHex.isEmpty()) return emptySet()
        val words = rawHex.split(' ').filter { it.isNotEmpty() }.reversed()
        val out = mutableSetOf<Int>()
        for ((idx, hex) in words.withIndex()) {
            var v = hex.toULongOrNull(16) ?: continue
            var bit = 0
            while (v != 0uL) {
                if (v and 1uL != 0uL) out += idx * 64 + bit
                v = v shr 1
                bit++
            }
        }
        return out
    }
}
