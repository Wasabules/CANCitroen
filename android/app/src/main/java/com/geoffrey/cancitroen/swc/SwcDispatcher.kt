package com.geoffrey.cancitroen.swc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Exécute une [SwcAction] via un [SwcEndpoint] précis.
 *
 * Endpoint AUTO     → choisit le canal "single" le plus universel selon l'action.
 * Endpoint COMBO_UNIVERSAL → fanout multi-canaux, accepte le risque de double-
 * déclenchement en échange de la fiabilité (recommandé pour AA + lecteur Atoto).
 */
class SwcDispatcher(private val context: Context) {

    companion object { private const val TAG = "SwcDispatcher" }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val sessionManager by lazy {
        try { context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager }
        catch (_: Throwable) { null }
    }

    fun dispatch(action: SwcAction, endpoint: SwcEndpoint) {
        if (action == SwcAction.NONE) return
        val resolved = if (endpoint == SwcEndpoint.AUTO) autoEndpoint(action) else endpoint
        Log.i(TAG, "dispatch action=$action endpoint=$resolved")
        when (action) {
            SwcAction.VOLUME_UP   -> volume(+1, resolved)
            SwcAction.VOLUME_DOWN -> volume(-1, resolved)
            SwcAction.VOLUME_MUTE -> volumeMute(resolved)
            SwcAction.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, resolved)
            SwcAction.MEDIA_PLAY       -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, resolved)
            SwcAction.MEDIA_PAUSE      -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, resolved)
            SwcAction.MEDIA_NEXT       -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, resolved)
            SwcAction.MEDIA_PREV       -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, resolved)
            SwcAction.MEDIA_STOP       -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP, resolved)
            SwcAction.MEDIA_REWIND       -> mediaKey(KeyEvent.KEYCODE_MEDIA_REWIND, resolved)
            SwcAction.MEDIA_FAST_FORWARD -> mediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, resolved)
            SwcAction.HEADSET_HOOK       -> mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, resolved)
            SwcAction.VOICE_ASSIST     -> mediaKey(KeyEvent.KEYCODE_VOICE_ASSIST, resolved)
            // Téléphonie & navigation : pas de canal "media" — on passe direct par
            // l'endpoint résolu. AUTO redirige vers ROOT_SHELL pour ces actions.
            SwcAction.CALL_ANSWER -> systemKey(KeyEvent.KEYCODE_CALL, resolved)
            SwcAction.CALL_END    -> systemKey(KeyEvent.KEYCODE_ENDCALL, resolved)
            SwcAction.NAV_HOME    -> systemKey(KeyEvent.KEYCODE_HOME, resolved)
            SwcAction.NAV_BACK    -> systemKey(KeyEvent.KEYCODE_BACK, resolved)
            SwcAction.NAV_MENU    -> systemKey(KeyEvent.KEYCODE_MENU, resolved)
            SwcAction.DPAD_UP     -> systemKey(KeyEvent.KEYCODE_DPAD_UP, resolved)
            SwcAction.DPAD_DOWN   -> systemKey(KeyEvent.KEYCODE_DPAD_DOWN, resolved)
            SwcAction.DPAD_LEFT   -> systemKey(KeyEvent.KEYCODE_DPAD_LEFT, resolved)
            SwcAction.DPAD_RIGHT  -> systemKey(KeyEvent.KEYCODE_DPAD_RIGHT, resolved)
            SwcAction.DPAD_CENTER -> systemKey(KeyEvent.KEYCODE_DPAD_CENTER, resolved)
            SwcAction.NONE -> Unit
        }
    }

    private fun autoEndpoint(action: SwcAction): SwcEndpoint = when (action) {
        SwcAction.VOLUME_UP, SwcAction.VOLUME_DOWN, SwcAction.VOLUME_MUTE -> SwcEndpoint.AUDIO_ADJUST
        SwcAction.MEDIA_PLAY_PAUSE,
        SwcAction.MEDIA_PLAY,
        SwcAction.MEDIA_PAUSE,
        SwcAction.MEDIA_NEXT,
        SwcAction.MEDIA_PREV,
        SwcAction.MEDIA_STOP,
        SwcAction.MEDIA_REWIND,
        SwcAction.MEDIA_FAST_FORWARD,
        SwcAction.HEADSET_HOOK,
        SwcAction.VOICE_ASSIST -> SwcEndpoint.DISPATCH_KEY
        // Toujours ROOT par défaut — c'est le seul canal qui marche pour ces
        // actions sur l'A6PF.
        SwcAction.CALL_ANSWER, SwcAction.CALL_END,
        SwcAction.NAV_HOME, SwcAction.NAV_BACK, SwcAction.NAV_MENU,
        SwcAction.DPAD_UP, SwcAction.DPAD_DOWN, SwcAction.DPAD_LEFT,
        SwcAction.DPAD_RIGHT, SwcAction.DPAD_CENTER -> SwcEndpoint.ROOT_SHELL
        SwcAction.NONE -> SwcEndpoint.AUTO
    }

    /**
     * Touche "système" (HOME/BACK/MENU/DPAD/CALL/ENDCALL) qui n'a aucun sens
     * via `dispatchMediaKeyEvent` (filtré aux touches média). On force ROOT
     * sauf si l'utilisateur a choisi DISPATCH_KEY (peu probable de marcher pour
     * CALL/ENDCALL mais on respecte le choix utilisateur).
     */
    private fun systemKey(keycode: Int, endpoint: SwcEndpoint) {
        when (endpoint) {
            SwcEndpoint.DISPATCH_KEY -> dispatchKeyOnly(keycode)
            else                     -> rootShellKeyEvent(keycode)
        }
    }

    // ── Volume ──────────────────────────────────────────────────────────

    private fun volume(direction: Int, endpoint: SwcEndpoint) {
        val adjust = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        try {
            when (endpoint) {
                SwcEndpoint.AUDIO_ADJUST ->
                    audioManager.adjustVolume(adjust, AudioManager.FLAG_SHOW_UI)
                SwcEndpoint.AUDIO_ADJUST_SUGGESTED ->
                    audioManager.adjustSuggestedStreamVolume(
                        adjust, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                    )
                SwcEndpoint.AUDIO_STREAM_MUSIC ->
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI,
                    )
                SwcEndpoint.DISPATCH_KEY -> dispatchKeyOnly(
                    if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                )
                SwcEndpoint.MEDIA_SESSION_VOLUME -> adjustActiveSessionVolume(direction)
                SwcEndpoint.COMBO_UNIVERSAL -> volumeCombo(direction)
                SwcEndpoint.ROOT_SHELL -> rootShellKeyEvent(
                    if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                )
                else -> {
                    Log.w(TAG, "Endpoint $endpoint pas valide pour volume — fallback adjustVolume")
                    audioManager.adjustVolume(adjust, AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "volume failed", e) }
    }

    /** Combo volume : 4 canaux. Volume = idempotent (chaque event = 1 cran), donc
     *  fanout safe : un appui = un cran même si plusieurs canaux répondent. */
    private fun volumeCombo(direction: Int) {
        val adjust = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // 1. Suggested stream (équivalent rocker hardware)
        try {
            audioManager.adjustSuggestedStreamVolume(
                adjust, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
            )
        } catch (e: Exception) { Log.w(TAG, "combo: adjustSuggested failed", e) }
        // 2. MediaSession active : seul moyen pour AA (qui a sa propre VolumeProvider)
        adjustActiveSessionVolume(direction)
        // Note : on ne fait PAS adjustVolume + adjustStreamVolume en plus, car
        //  ça ferait monter le volume de 2-3 crans d'un coup.
    }

    private fun adjustActiveSessionVolume(direction: Int) {
        val mgr = sessionManager ?: return
        try {
            val sessions = mgr.getActiveSessions(ComponentName(context, javaClass))
            if (sessions.isEmpty()) {
                Log.w(TAG, "Volume MediaSession : 0 session active (perm Notification ?)")
                return
            }
            val flag = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            for (controller in sessions) {
                try {
                    controller.adjustVolume(flag, AudioManager.FLAG_SHOW_UI)
                    Log.i(TAG, "Volume session ${controller.packageName} : ${if (direction > 0) "+" else "-"}")
                } catch (e: Exception) {
                    Log.w(TAG, "Session volume ${controller.packageName} failed", e)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions refusé pour volume (perm Notification non accordée)")
        }
    }

    private fun volumeMute(endpoint: SwcEndpoint) {
        try {
            when (endpoint) {
                SwcEndpoint.DISPATCH_KEY -> dispatchKeyOnly(KeyEvent.KEYCODE_VOLUME_MUTE)
                SwcEndpoint.COMBO_UNIVERSAL -> {
                    audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    dispatchKeyOnly(KeyEvent.KEYCODE_VOLUME_MUTE)
                }
                SwcEndpoint.ROOT_SHELL -> rootShellKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE)
                else -> audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            }
        } catch (e: Exception) { Log.e(TAG, "mute failed", e) }
    }

    // ── Media keys ──────────────────────────────────────────────────────

    private fun mediaKey(keycode: Int, endpoint: SwcEndpoint) {
        when (endpoint) {
            SwcEndpoint.DISPATCH_KEY    -> dispatchKeyOnly(keycode)
            SwcEndpoint.MEDIA_SESSION   -> dispatchToActiveSessionsOnly(keycode)
            SwcEndpoint.MEDIA_BROADCAST -> broadcastMediaButtonOnly(keycode)
            SwcEndpoint.SYU_MUSIC -> startSyuService(syuActionForMusic(keycode), "com.syu.music")
            SwcEndpoint.SYU_BT    -> startSyuService(syuActionForBt(keycode),    "com.syu.bt")
            SwcEndpoint.SYU_RADIO -> startSyuService(syuActionForRadio(keycode), "com.syu.radio")
            SwcEndpoint.COMBO_UNIVERSAL -> mediaKeyCombo(keycode)
            SwcEndpoint.ROOT_SHELL      -> rootShellKeyEvent(keycode)
            else -> {
                Log.w(TAG, "Endpoint $endpoint pas valide pour media — fallback dispatchKey")
                dispatchKeyOnly(keycode)
            }
        }
    }

    /**
     * Combo media : 2 canaux non-redondants pour atteindre AA + Atoto.
     *
     * - dispatchMediaKeyEvent → atteint la session prioritaire (AA, Spotify)
     * - SYU services → réveille le lecteur Atoto natif s'il est actif
     *
     * On évite délibérément MEDIA_BROADCAST (redondant avec dispatchKey) et
     * MEDIA_SESSION (déjà ciblé par dispatchMediaKeyEvent indirectement) pour
     * minimiser les doublons.
     */
    private fun mediaKeyCombo(keycode: Int) {
        // 1. dispatchMediaKeyEvent : universel (AA, Spotify, BT)
        dispatchKeyOnly(keycode)
        // 2. MediaSession explicite : double sécurité pour AA si l'implicit dispatch
        //    ne touche pas la session
        dispatchToActiveSessionsOnly(keycode)
        // 3. SYU : pour le lecteur natif Atoto si actif
        startSyuService(syuActionForMusic(keycode), "com.syu.music")
        startSyuService(syuActionForBt(keycode), "com.syu.bt")
    }

    private fun dispatchKeyOnly(keycode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0)
        val up   = KeyEvent(now, now, KeyEvent.ACTION_UP,   keycode, 0)
        try {
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (e: Exception) { Log.e(TAG, "dispatchKey failed", e) }
    }

    private fun dispatchToActiveSessionsOnly(keycode: Int) {
        val mgr = sessionManager ?: return
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0)
        val up   = KeyEvent(now, now, KeyEvent.ACTION_UP,   keycode, 0)
        try {
            val sessions = mgr.getActiveSessions(ComponentName(context, javaClass))
            if (sessions.isNullOrEmpty()) {
                Log.w(TAG, "Aucune session active visible — perm Notification absente ?")
                return
            }
            for (controller in sessions) {
                try {
                    controller.dispatchMediaButtonEvent(down)
                    controller.dispatchMediaButtonEvent(up)
                    Log.i(TAG, "Session ${controller.packageName} : keycode=$keycode dispatché")
                } catch (e: Exception) {
                    Log.w(TAG, "Session ${controller.packageName} dispatch failed", e)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions refusé (perm Notification non accordée)")
        }
    }

    private fun broadcastMediaButtonOnly(keycode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, down)
        }
        try { context.sendOrderedBroadcast(intent, null) }
        catch (e: Exception) { Log.e(TAG, "broadcast failed", e) }
    }

    // ── SYU services ────────────────────────────────────────────────────

    private fun syuActionForMusic(keycode: Int): String? = when (keycode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "com.syu.music.playpause"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "com.syu.music.next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "com.syu.music.prev"
        else -> null
    }

    private fun syuActionForBt(keycode: Int): String? = when (keycode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "com.syu.bt.byav.widgetPlayPause"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "com.syu.bt.byav.widgetNext"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "com.syu.bt.byav.widgetPrev"
        else -> null
    }

    private fun syuActionForRadio(keycode: Int): String? = when (keycode) {
        KeyEvent.KEYCODE_MEDIA_NEXT     -> "com.syu.radio.nextservice"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "com.syu.radio.prevservice"
        else -> null  // pas de play/pause sur radio
    }

    private fun startSyuService(action: String?, pkg: String) {
        if (action == null) return
        try {
            context.startService(Intent(action).setPackage(pkg))
        } catch (e: Throwable) {
            Log.w(TAG, "SYU service $action @ $pkg failed: ${e.message}")
        }
    }

    // ── Root injection ─────────────────────────────────────────────────
    //
    // Délégué à [RootKeyInjector] qui combine :
    //  - shell `su` persistant (skip fork + auth Magisk par appel)
    //  - `sendevent` direct sur /dev/input/eventN quand on a un mapping
    //    Android→Linux pour la touche (fast path ~5-15 ms)
    //  - fallback `input keyevent` via le même shell pour les touches sans
    //    mapping (lent ~150 ms mais respawn `su` épargné).
    //
    // Tout est non-bloquant côté caller — sendevent c'est juste un write
    // syscall, le kernel propage en arrière-plan.

    private fun rootShellKeyEvent(keycode: Int) {
        RootKeyInjector.inject(keycode)
    }
}
