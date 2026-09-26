package com.geoffrey.cancitroen.swc

/**
 * Boutons physiques du comodo Citroën C2 (CAN ID 0x21F, byte0).
 *
 * `match(raw)` retourne true si l'octet brut représente un appui sur ce bouton
 * exclusivement. Important : les combos sont distincts (MUTE = VOL+ + VOL−).
 */
enum class SwcButton(val displayName: String, val match: (Int) -> Boolean) {
    VOL_UP   ("Volume +",       { (it and 0x0C) == 0x08 }),
    VOL_DOWN ("Volume −",       { (it and 0x0C) == 0x04 }),
    MUTE     ("Mute (VOL+ et VOL− simultanés)", { (it and 0x0C) == 0x0C }),
    NEXT     ("Suivant ▶",      { (it and 0x80) != 0 }),
    PREV     ("◀ Précédent",    { (it and 0x40) != 0 }),
    SOURCE   ("Source / Mode",  { (it and 0x02) != 0 }),
}

/** Reconnaît quel bouton a été pressé sur transition 0 → raw. */
fun detectButton(raw: Int): SwcButton? =
    SwcButton.values().firstOrNull { it.match(raw) }

/**
 * Action abstraite que peut exécuter un bouton. NONE désactive.
 *
 * Ne pas confondre avec [SwcEndpoint] : l'action est l'intent (« je veux changer
 * de piste »), l'endpoint est le canal Android utilisé pour le faire.
 */
enum class SwcAction(val label: String) {
    NONE              ("— Désactivé —"),
    // ── Volume ──
    VOLUME_UP         ("Volume +"),
    VOLUME_DOWN       ("Volume −"),
    VOLUME_MUTE       ("Couper le son (toggle)"),
    // ── Média ──
    MEDIA_PLAY_PAUSE  ("Play / Pause"),
    MEDIA_PLAY        ("Play (force lecture)"),
    MEDIA_PAUSE       ("Pause (force pause)"),
    MEDIA_NEXT        ("Piste suivante"),
    MEDIA_PREV        ("Piste précédente"),
    MEDIA_STOP        ("Stop"),
    MEDIA_REWIND      ("Rembobiner ⏪"),
    MEDIA_FAST_FORWARD("Avance rapide ⏩"),
    HEADSET_HOOK      ("Hook casque (= play/pause hardware)"),
    // ── Voix + téléphonie ──
    VOICE_ASSIST      ("Assistant vocal (Google)"),
    CALL_ANSWER       ("📞 Décrocher l'appel"),
    CALL_END          ("📵 Raccrocher"),
    // ── Navigation système (Atoto) ──
    NAV_HOME          ("🏠 Home (retour launcher)"),
    NAV_BACK          ("← Back"),
    NAV_MENU          ("☰ Menu"),
    DPAD_UP           ("▲ Haut"),
    DPAD_DOWN         ("▼ Bas"),
    DPAD_LEFT         ("◀ Gauche"),
    DPAD_RIGHT        ("▶ Droite"),
    DPAD_CENTER       ("● OK"),
}

/**
 * Canal de diffusion. Chacun a une portée et une fiabilité différentes ;
 * le but du mapping est qu'**un seul** soit déclenché par appui pour éviter
 * les doubles déclenchements (typiquement Android Auto qui reçoit la même
 * commande deux fois et saute deux pistes).
 *
 * Inventaire découvert via :
 *  - SDK Android (AudioManager, MediaSessionManager)
 *  - ROM Atoto A6PF (`com.syu.music`, `com.syu.bt`, `com.syu.radio`)
 */
enum class SwcEndpoint(val label: String, val description: String) {
    AUTO(
        "🎯 Automatique (recommandé)",
        "L'app choisit le canal le plus universel selon l'action."
    ),
    COMBO_UNIVERSAL(
        "💪 Combo universel (fanout)",
        "Tente plusieurs canaux à la fois (Audio + MediaSession + SYU). Plus fiable mais risque de double-déclenchement sur certaines apps."
    ),
    AUDIO_ADJUST(
        "🔊 AudioManager.adjustVolume",
        "Volume context-aware : choisit le stream actif (Android Auto inclus)."
    ),
    AUDIO_ADJUST_SUGGESTED(
        "🎚 adjustSuggestedStreamVolume",
        "Équivalent strict des boutons hardware (rocker). Le plus universel pour le volume."
    ),
    AUDIO_STREAM_MUSIC(
        "🎵 Stream MUSIC",
        "Force adjustStreamVolume(STREAM_MUSIC). Volume uniquement."
    ),
    DISPATCH_KEY(
        "📡 dispatchMediaKeyEvent",
        "Universel : Spotify, Android Auto, lecteur Atoto via AudioManager."
    ),
    MEDIA_SESSION(
        "🎼 MediaSession active",
        "Cible la session média en cours via getActiveSessions (Android Auto OK). Nécessite « Accès aux notifications »."
    ),
    MEDIA_SESSION_VOLUME(
        "🎼+🔊 Volume via MediaSession",
        "Volume sur la session média active (le seul moyen pour Android Auto). Nécessite « Accès aux notifications »."
    ),
    MEDIA_BROADCAST(
        "📻 Intent ACTION_MEDIA_BUTTON",
        "Broadcast système ordered. Nécessaire pour certaines apps anciennes."
    ),
    SYU_MUSIC(
        "🎶 Atoto · Musique (com.syu.music)",
        "Service propriétaire firmware Atoto. Ne touche pas Android Auto."
    ),
    SYU_BT(
        "📞 Atoto · Bluetooth (com.syu.bt)",
        "Service du player BT-A2DP firmware Atoto."
    ),
    SYU_RADIO(
        "📡 Atoto · Radio (com.syu.radio)",
        "Service du tuner radio firmware Atoto."
    ),
    ROOT_SHELL(
        "🔓 Root shell (input keyevent)",
        "Injecte la touche via `su -c input keyevent N`. Bypasse Android Auto et toute app qui consomme la touche. ~200 ms de latence — déconseillé pour vol±/-/répétitions rapides. Requiert ROOT (Magisk OK)."
    ),
}

/** Action × Endpoint pour un bouton donné. */
data class SwcMappingEntry(
    val action: SwcAction,
    val endpoint: SwcEndpoint,
)

/** Mapping complet : un [SwcMappingEntry] par bouton physique. */
data class SwcMapping(
    val entries: Map<SwcButton, SwcMappingEntry> = defaultMapping(),
) {
    fun forButton(b: SwcButton): SwcMappingEntry =
        entries[b] ?: SwcMappingEntry(SwcAction.NONE, SwcEndpoint.AUTO)
}

/**
 * Mapping par défaut : utilise COMBO_UNIVERSAL pour garantir que ça marche
 * d'emblée sur tous les setups (AA, lecteur Atoto, BT, Spotify…). L'utilisateur
 * affine ensuite vers un endpoint unique si l'app cible double-déclenche.
 *
 *  - VOL+/VOL−/MUTE/NEXT/PREV → COMBO_UNIVERSAL
 *  - SOURCE                   → désactivé (à mapper par l'utilisateur)
 */
fun defaultMapping(): Map<SwcButton, SwcMappingEntry> = mapOf(
    SwcButton.VOL_UP   to SwcMappingEntry(SwcAction.VOLUME_UP,        SwcEndpoint.COMBO_UNIVERSAL),
    SwcButton.VOL_DOWN to SwcMappingEntry(SwcAction.VOLUME_DOWN,      SwcEndpoint.COMBO_UNIVERSAL),
    SwcButton.MUTE     to SwcMappingEntry(SwcAction.MEDIA_PLAY_PAUSE, SwcEndpoint.COMBO_UNIVERSAL),
    SwcButton.NEXT     to SwcMappingEntry(SwcAction.MEDIA_NEXT,       SwcEndpoint.COMBO_UNIVERSAL),
    SwcButton.PREV     to SwcMappingEntry(SwcAction.MEDIA_PREV,       SwcEndpoint.COMBO_UNIVERSAL),
    SwcButton.SOURCE   to SwcMappingEntry(SwcAction.NONE,             SwcEndpoint.AUTO),
)

/**
 * Endpoints raisonnablement utilisables selon l'action — sert à filtrer le menu
 * dans la UI. Par exemple, les endpoints volume n'ont pas de sens pour NEXT.
 */
fun allowedEndpoints(action: SwcAction): List<SwcEndpoint> = when (action) {
    SwcAction.NONE -> listOf(SwcEndpoint.AUTO)

    SwcAction.VOLUME_UP, SwcAction.VOLUME_DOWN, SwcAction.VOLUME_MUTE -> listOf(
        SwcEndpoint.AUTO,
        SwcEndpoint.COMBO_UNIVERSAL,
        SwcEndpoint.AUDIO_ADJUST,
        SwcEndpoint.AUDIO_ADJUST_SUGGESTED,
        SwcEndpoint.AUDIO_STREAM_MUSIC,
        SwcEndpoint.DISPATCH_KEY,
        SwcEndpoint.MEDIA_SESSION_VOLUME,
        SwcEndpoint.ROOT_SHELL,
    )

    SwcAction.MEDIA_PLAY_PAUSE,
    SwcAction.MEDIA_PLAY,
    SwcAction.MEDIA_PAUSE,
    SwcAction.MEDIA_NEXT,
    SwcAction.MEDIA_PREV,
    SwcAction.MEDIA_STOP,
    SwcAction.MEDIA_REWIND,
    SwcAction.MEDIA_FAST_FORWARD,
    SwcAction.HEADSET_HOOK -> listOf(
        SwcEndpoint.AUTO,
        SwcEndpoint.COMBO_UNIVERSAL,
        SwcEndpoint.DISPATCH_KEY,
        SwcEndpoint.MEDIA_SESSION,
        SwcEndpoint.MEDIA_BROADCAST,
        SwcEndpoint.SYU_MUSIC,
        SwcEndpoint.SYU_BT,
        SwcEndpoint.SYU_RADIO,
        SwcEndpoint.ROOT_SHELL,
    )

    SwcAction.VOICE_ASSIST -> listOf(
        SwcEndpoint.AUTO,
        SwcEndpoint.DISPATCH_KEY,
        SwcEndpoint.ROOT_SHELL,
    )

    // Téléphonie : sur la plupart des ROMs Atoto, les API Android sont peu
    // fiables — ROOT shell est la voie quasi-obligatoire (cf. notes A6PF).
    SwcAction.CALL_ANSWER, SwcAction.CALL_END -> listOf(
        SwcEndpoint.AUTO,
        SwcEndpoint.ROOT_SHELL,
        SwcEndpoint.DISPATCH_KEY,
    )

    // Touches système / navigation : ROOT shell est le seul canal réellement
    // universel (dispatchMediaKeyEvent n'accepte pas HOME/BACK/MENU/DPAD).
    SwcAction.NAV_HOME,
    SwcAction.NAV_BACK,
    SwcAction.NAV_MENU,
    SwcAction.DPAD_UP,
    SwcAction.DPAD_DOWN,
    SwcAction.DPAD_LEFT,
    SwcAction.DPAD_RIGHT,
    SwcAction.DPAD_CENTER -> listOf(
        SwcEndpoint.AUTO,
        SwcEndpoint.ROOT_SHELL,
    )
}
