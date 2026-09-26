package com.geoffrey.cancitroen.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Base64
import android.util.Log
import com.geoffrey.cancitroen.swc.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Impose CANCitroen comme launcher **via root** sur le ROM Atoto (FYT/SYU,
 * Android 10), qui ramène son propre launcher (`ro.fyt.launcher` =
 * `com.android.launcher8`).
 *
 * Ce que fait le ROM (services.jar décompilé, cf. ~/ATOTORom) :
 * `PackageManagerService.chooseBestActivity` résout HOME d'abord vers
 * l'activité **préférée**, puis — s'il n'y en a pas — vers le package de
 * `persist.lsec.launcher`, à défaut `ro.fyt.launcher`. Or Android invalide la
 * préférence HOME dès qu'un nouveau home apparaît (installation/MAJ d'app) :
 * le ROM retombe alors sur launcher8. `com.syu.us` relance ensuite HOME
 * (intent implicite) → launcher8 revient devant.
 *
 * Périmètre : remplacer le launcher, rien d'autre. Un « concurrent » est une
 * **activité HOME précise** (ex. `com.android.launcher8/.Launcher`), jamais un
 * package entier ; les homes système (FallbackHome des Réglages, assistants de
 * premier démarrage, écran Family Link de GMS, picker) et [PROTECTED_PKGS] n'en
 * sont jamais. Ouvrir Android Auto, les Réglages, Maps… ne déclenche donc rien.
 *
 * Niveaux :
 *
 *  1. **Home par défaut (persistant).** `cmd package set-home-activity` nous
 *     désigne comme home sans dialog utilisateur. Survit au reboot.
 *
 *  2. **Launcher natif FYT.** `persist.lsec.launcher` = nous : quand la
 *     préférence saute, le ROM retombe sur CANCitroen et non sur launcher8
 *     (et `lsecStartAllow` bloque les démarrages en arrière-plan des
 *     `com.android.launcher*` non choisis). Valeur d'origine sauvegardée.
 *
 *  3. **Garde root** (`assets/root/home_guard.sh`) : daemon détaché ([setsid])
 *     qui survit à la mort de l'app.
 *      - Ramène CANCitroen quand une activité HOME concurrente passe au
 *        premier plan (sur évènement logcat, repli par sondage dumpsys).
 *      - Keepalive : relance CanService si le process de l'app a disparu.
 *      - Relancé au boot par un hook `service.d` (Magisk/KernelSU) s'il est
 *        disponible, indépendamment du BootReceiver.
 *      - Se nettoie seul si l'app est désinstallée sans avoir été désarmée.
 *
 *  4. **Mode agressif (opt-in).** `pm disable` de l'**activité** HOME
 *     concurrente (pas du package). Réversible : la liste est gardée côté root
 *     pour `pm enable` au désarmement.
 *
 * Le pilotage (arm/disarm) est déclenché par [com.geoffrey.cancitroen.CanService]
 * qui observe les settings : réconcilié à chaque démarrage du service, sans
 * redémarrer le garde si rien n'a changé.
 */
object LauncherGuard {

    private const val TAG = "LauncherGuard"
    private const val OUR_PKG = "com.geoffrey.cancitroen"
    private val OUR_ACTIVITY = ComponentName(OUR_PKG, "$OUR_PKG.MainActivity")
    private val OUR_SERVICE = ComponentName(OUR_PKG, "$OUR_PKG.CanService")

    // Dossier root (0700). Chemin aussi en dur dans les scripts assets/root/.
    private const val DIR = "/data/local/tmp/cancitroen"
    private const val SCRIPT = "$DIR/home_guard.sh"
    private const val CONF = "$DIR/guard.conf"
    private const val ENABLED = "$DIR/enabled"
    private const val VERSION = "$DIR/version"
    private const val DISABLED_LIST = "$DIR/disabled"
    private const val RUN_DIR = "$DIR/run"
    private const val SERVICE_D = "/data/adb/service.d"
    private const val BOOT_HOOK = "$SERVICE_D/cancitroen_guard.sh"

    /** Launcher natif du ROM FYT (lu par PackageManagerService/ActivityManagerService). */
    private const val FYT_LAUNCHER_PROP = "ro.fyt.launcher"
    private const val LSEC_LAUNCHER_PROP = "persist.lsec.launcher"
    /** Valeur d'origine de [LSEC_LAUNCHER_PROP] (chemin aussi en dur dans home_guard.sh). */
    private const val LSEC_ORIG = "$DIR/lsec_launcher.orig"

    /**
     * Motifs pgrep/pkill. Le `[h]` / `[s]` empêche de matcher le `sh -c` de
     * `su -c`, qui porte le motif dans sa propre ligne de commande (sinon
     * pkill le tue en plein milieu de la commande, et pgrep le compte comme
     * « garde actif »). Ne jamais mettre dans la même commande `su -c` un
     * chemin en clair qui matcherait le motif.
     */
    private const val GUARD_PROC = "$DIR/[h]ome_guard"
    private const val EVENTS_PROC = "logcat.*[s]et_resumed_activity"

    // Garde ≤ 0.1.22 : script généré + liste de packages entiers désactivés.
    private const val LEGACY_SCRIPT = "/data/local/tmp/cancitroen_home_guard.sh"
    private const val LEGACY_FLAG = "/data/local/tmp/cancitroen_home_guard.on"
    private const val LEGACY_DISABLED = "/data/local/tmp/cancitroen_disabled_launchers"
    private const val LEGACY_PROC = "/data/local/tmp/[c]ancitroen_home_guard"

    private const val POLL_INTERVAL_S = 1
    private const val KEEPALIVE_INTERVAL_S = 20

    /**
     * Marqueur côté app « garde armé ». Le désarmement est rejoué à chaque
     * démarrage du service quand la feature est OFF : sans ce marqueur, il
     * appellerait `su` à chaque boot (popup Magisk si l'accès n'a jamais été
     * accordé) alors qu'il n'y a rien à défaire.
     */
    private const val ARMED_MARKER = "launcher_guard_armed"

    /**
     * Jamais traités comme launchers concurrents, même s'ils répondent à HOME.
     * Ceinture et bretelles : la vraie garantie est la comparaison au
     * composant HOME exact, mais ceux-là ne doivent en aucun cas être
     * désactivés par le mode agressif. Les homes « système » listés ici sont
     * ceux relevés dans la ROM A6PF (scan des manifestes, 2026-09).
     */
    private val PROTECTED_PKGS = setOf(
        OUR_PKG,
        "android",                                  // ResolverActivity / SystemUserHomeActivity
        "com.android.settings",                     // FallbackHome / CryptKeeper + Réglages
        "com.android.provision",                    // assistant premier démarrage AOSP
        "com.android.managedprovisioning",          // PostEncryptionActivity
        "com.google.android.setupwizard",
        "com.google.android.apps.restore",          // StubLauncherActivity (setup, prio 1000)
        "com.google.android.gms",                   // écrans Family Link — et Play Services !
        "com.google.android.googlequicksearchbox",  // alias GEL — et appli Google / Assistant
        "com.sprd.powersavemodelauncher",           // home du mode économie d'énergie Unisoc
        "com.google.android.projection.gearhead",   // Android Auto
        "com.google.android.apps.maps",
    )

    /** Composant sûr à écrire dans une commande/config shell (entre quotes simples). */
    private val SAFE_COMPONENT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Sérialise arm/disarm : le collecteur du service et le bouton "Réappliquer"
     *  peuvent tomber en même temps. */
    private val mutex = Mutex()

    data class Status(
        val rootAvailable: Boolean,
        /** Le daemon root tourne (process vérifié, pas seulement un flag). */
        val guardRunning: Boolean,
        /** "events" (instantané) ou "poll" (repli) ; null tant que le garde n'a pas démarré. */
        val detectionMode: String?,
        /** CANCitroen est le home résolu par Android. */
        val isDefaultHome: Boolean,
        /** Package du home par défaut courant (peut être un concurrent). */
        val currentHome: String?,
        /** Launcher du ROM FYT (`ro.fyt.launcher`) ; null hors ROM FYT. */
        val fytLauncher: String?,
        /** CANCitroen est le launcher natif FYT (`persist.lsec.launcher`). */
        val isFytLauncher: Boolean,
        /** Activités HOME concurrentes visées (forme courte `pkg/.Cls`). */
        val competitors: List<String>,
        /** Entrées actuellement désactivées par le mode agressif. */
        val disabledCompetitors: List<String>,
        /** `service.d` présent (Magisk/KernelSU) : relance au boot possible. */
        val bootHookSupported: Boolean,
        val bootHookInstalled: Boolean,
        /** Retours forcés au premier plan depuis le démarrage du garde. */
        val catches: Int,
        val lastCatchAt: Long?,
        val lastCatchFrom: String?,
        /** Relances de CanService par le keepalive. */
        val revives: Int,
        val lastReviveAt: Long?,
        /** Dernière fois que le ROM a relancé son launcher en boucle. */
        val lastConflictAt: Long?,
    )

    private val _status = MutableStateFlow<Status?>(null)
    /** null tant qu'aucun refresh n'a eu lieu (UI affiche "chargement"). */
    val status: StateFlow<Status?> = _status.asStateFlow()

    // ── API fire-and-forget (UI / VM) ───────────────────────────────────

    /** Arme le garde maintenant et le redémarre (bouton "Réappliquer"). */
    fun apply(context: Context, aggressive: Boolean) {
        val app = context.applicationContext
        scope.launch { arm(app, aggressive, forceRestart = true) }
    }

    /** Désarme + réactive les launchers concurrents. */
    fun disable(context: Context) {
        val app = context.applicationContext
        scope.launch { disarm(app) }
    }

    /** Recalcule [status] sans rien modifier (appelé sur ON_RESUME de l'UI). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshStatus(app) }
    }

    // ── Cœur (suspend, appelé aussi par CanService) ─────────────────────

    /**
     * Réconcilie l'état "armé" avec [aggressive]. Idempotent : rejoué à chaque
     * démarrage du service ; le garde n'est redémarré que si son script ou sa
     * config ont changé, s'il ne tourne plus, ou si [forceRestart].
     * Retourne false si pas de root.
     */
    suspend fun arm(
        context: Context,
        aggressive: Boolean,
        forceRestart: Boolean = false,
    ): Boolean = mutex.withLock {
        if (!RootShell.probe(force = true)) {
            Log.w(TAG, "arm ignoré : pas de root")
            refreshStatus(context)
            return false
        }
        setArmedMarker(context, true)
        root("mkdir -p $DIR && chmod 700 $DIR")
        migrateLegacy()

        // 1. Mode agressif, réconcilié AVANT de lister les concurrents : une
        //    activité désactivée n'apparaît plus dans PackageManager.
        //    Les entrées "package entier" (ancien garde) sont toujours
        //    réactivées : on ne désactive plus qu'au niveau de l'activité.
        var disabled = readDisabledList()
        val toEnable = if (aggressive) disabled.filter { '/' !in it } else disabled
        if (toEnable.isNotEmpty()) {
            toEnable.forEach { root("pm enable '$it'") }
            disabled = disabled - toEnable.toSet()
            writeDisabledList(disabled)
        }
        val competitors = (competitorHomes(context) +
            disabled.mapNotNull { ComponentName.unflattenFromString(it) }).distinct()
        Log.i(TAG, "arm (aggressive=$aggressive), concurrents=" +
            competitors.map { it.flattenToShortString() })
        if (aggressive) {
            val newlyDisabled = competitors.map { it.flattenToString() }
                .filter { it !in disabled && SAFE_COMPONENT.matches(it) }
            newlyDisabled.forEach { root("pm disable '$it'") }
            writeDisabledList(disabled + newlyDisabled)
        }

        // 2. Home par défaut = nous (persistant). APRÈS les désactivations :
        //    Android mémorise l'ensemble des homes candidats avec la préférence.
        root("cmd package set-home-activity '${OUR_ACTIVITY.flattenToString()}'")

        // 3. Launcher natif FYT, repli quand la préférence est invalidée. On ne
        //    sauvegarde la valeur d'origine qu'une fois (pas notre propre valeur).
        if (fytLauncher() != null) {
            root("[ -f $LSEC_ORIG ] || getprop $LSEC_LAUNCHER_PROP > $LSEC_ORIG; " +
                "setprop $LSEC_LAUNCHER_PROP $OUR_PKG")
        }

        // 4. Garde root : (ré)installé et (re)démarré seulement si besoin.
        val script = readAsset(context, "root/home_guard.sh")
        val conf = buildConf(competitors)
        val version = sha1(script + conf)
        val upToDate = root("cat $VERSION 2>/dev/null")?.trim() == version
        if (forceRestart || !upToDate || !isGuardRunning()) {
            writeRootFile(SCRIPT, script, "700")
            writeRootFile(CONF, conf, "600")
            stopGuard()
            root("echo $version > $VERSION && touch $ENABLED")
            // Via le shell persistant + setsid : le garde est détaché dans sa
            // propre session et survit à la mort de l'app.
            RootShell.exec("setsid sh $SCRIPT </dev/null >/dev/null 2>&1 &")
            Log.i(TAG, "garde (re)démarré (version ${version.take(8)})")
        } else {
            root("touch $ENABLED")
        }

        // 5. Relance au boot indépendante de l'app, si Magisk/KernelSU.
        if (root("[ -d $SERVICE_D ] && echo 1")?.trim() == "1") {
            writeRootFile(BOOT_HOOK, readAsset(context, "root/boot_hook.sh"), "755")
        }

        delay(500)  // laisse le garde démarrer avant le probe
        refreshStatus(context)
        return true
    }

    /**
     * Désarme : stoppe le garde, retire le hook de boot, réactive ce que le
     * mode agressif avait coupé et rend le home au launcher d'origine. Aucun
     * appel root si le garde n'a jamais été armé (appelé à chaque démarrage du
     * service quand la feature est OFF).
     */
    suspend fun disarm(context: Context): Unit = mutex.withLock {
        if (!armedMarker(context).exists()) return
        if (!RootShell.probe()) {
            refreshStatus(context)
            return
        }
        migrateLegacy()
        val disabled = readDisabledList()
        Log.i(TAG, "disarm (réactive $disabled)")
        stopGuard()
        root("rm -f $BOOT_HOOK $SCRIPT $CONF $VERSION; rm -rf $RUN_DIR")
        disabled.forEach { root("pm enable '$it'") }
        clearDisabledList()
        root("if [ -f $LSEC_ORIG ]; then setprop $LSEC_LAUNCHER_PROP \"\$(cat $LSEC_ORIG)\"; " +
            "rm -f $LSEC_ORIG; fi")
        // Rends la main au launcher d'origine si c'est encore nous le home :
        // celui du ROM FYT en priorité, sinon le premier concurrent.
        if (PermissionsHelper.currentDefaultHomePackage(context) == OUR_PKG) {
            val homes = competitorHomes(context)
            val fyt = fytLauncher()
            (homes.firstOrNull { it.packageName == fyt } ?: homes.firstOrNull())?.let {
                root("cmd package set-home-activity '${it.flattenToString()}'")
            }
        }
        setArmedMarker(context, false)
        refreshStatus(context)
    }

    // ── Statut ──────────────────────────────────────────────────────────

    private fun refreshStatus(context: Context) {
        val rootOk = RootShell.probe()
        val kv = if (rootOk) parseKeyValues(root(STATUS_CMD)) else emptyMap()
        val disabled = kv["disabled"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        val currentHome = PermissionsHelper.currentDefaultHomePackage(context)
        // Les activités désactivées ne remontent plus dans la query PM → on
        // les rajoute pour que l'UI puisse toujours les afficher.
        val competitors = (competitorHomes(context).map { it.flattenToShortString() } +
            disabled.map(::shortName)).distinct()
        val lastCatch = kv["last_catch"]?.split(' ', limit = 2)
        _status.value = Status(
            rootAvailable = rootOk,
            guardRunning = kv["running"] == "1",
            detectionMode = kv["mode"],
            isDefaultHome = currentHome == OUR_PKG,
            currentHome = currentHome,
            fytLauncher = kv["fyt"]?.takeIf { it.isNotEmpty() },
            isFytLauncher = kv["lsec"] == OUR_PKG,
            competitors = competitors,
            disabledCompetitors = disabled.map(::shortName),
            bootHookSupported = kv["hook_supported"] == "1",
            bootHookInstalled = kv["hook"] == "1",
            catches = kv["catches"]?.toIntOrNull() ?: 0,
            lastCatchAt = lastCatch?.getOrNull(0)?.toLongOrNull(),
            lastCatchFrom = lastCatch?.getOrNull(1),
            revives = kv["revives"]?.toIntOrNull() ?: 0,
            lastReviveAt = kv["last_revive"]?.toLongOrNull(),
            lastConflictAt = kv["last_conflict"]?.toLongOrNull(),
        )
    }

    /** Une seule session `su` pour tout l'état ; sortie en lignes `clé=valeur`
     *  (les fichiers de $RUN_DIR sont écrits par le garde). */
    private val STATUS_CMD = listOf(
        "pgrep -f '$GUARD_PROC' >/dev/null && echo running=1",
        "[ -d $SERVICE_D ] && echo hook_supported=1",
        "[ -f $BOOT_HOOK ] && echo hook=1",
        "[ -f $DISABLED_LIST ] && echo disabled=\$(cat $DISABLED_LIST)",
        "echo fyt=\$(getprop $FYT_LAUNCHER_PROP)",
        "echo lsec=\$(getprop $LSEC_LAUNCHER_PROP)",
        "for f in $RUN_DIR/*; do [ -f \"\$f\" ] && echo \"\${f##*/}=\$(cat \"\$f\")\"; done",
        "true",
    ).joinToString("; ")

    private fun parseKeyValues(out: String?): Map<String, String> =
        out.orEmpty().lines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
            }
            .toMap()

    private fun shortName(entry: String): String =
        ComponentName.unflattenFromString(entry)?.flattenToShortString() ?: entry

    // ── Helpers PackageManager ──────────────────────────────────────────

    /**
     * Activités HOME concurrentes, par priorité décroissante. On fusionne la
     * query PM avec le home résolu : sur certaines ROM le launcher SYU
     * n'apparaît pas toujours dans `queryIntentActivities`, mais
     * `resolveActivity(HOME)` le donne. Doit être appelé AVANT de se fixer
     * home (sinon le home résolu est déjà nous).
     */
    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun competitorHomes(context: Context): List<ComponentName> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = pm.queryIntentActivities(home, PackageManager.MATCH_ALL) +
            listOfNotNull(pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY))
        return candidates
            .filter(::isCompetitor)
            .sortedByDescending { it.priority }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .distinct()
    }

    /**
     * Les homes « de secours » déclarent une priorité négative (FallbackHome
     * des Réglages : -1000, affiché pendant le déchiffrement au boot) : ce ne
     * sont pas des launchers, on n'y touche jamais.
     */
    private fun isCompetitor(ri: ResolveInfo): Boolean {
        val ai = ri.activityInfo ?: return false
        return ai.packageName !in PROTECTED_PKGS &&
            ri.priority >= 0 &&
            SAFE_COMPONENT.matches("${ai.packageName}/${ai.name}")
    }

    // ── Garde root ──────────────────────────────────────────────────────

    /** Config sourcée par home_guard.sh. Les composants sont listés sous leurs
     *  deux formes : courte (celle de dumpsys/logcat) et longue. */
    private fun buildConf(competitors: List<ComponentName>): String {
        val forms = competitors
            .flatMap { listOf(it.flattenToShortString(), it.flattenToString()) }
            .filter { SAFE_COMPONENT.matches(it) }
            .distinct()
        return listOf(
            "# Généré par LauncherGuard.kt — ne pas éditer.",
            "OUR_PKG='$OUR_PKG'",
            "OUR_ACTIVITY='${OUR_ACTIVITY.flattenToShortString()}'",
            "OUR_SERVICE='${OUR_SERVICE.flattenToShortString()}'",
            "COMPETITORS='${forms.joinToString(" ")}'",
            "POLL_INTERVAL=$POLL_INTERVAL_S",
            "KEEPALIVE_INTERVAL=$KEEPALIVE_INTERVAL_S",
            "BOOT_HOOK='$BOOT_HOOK'",
        ).joinToString("\n", postfix = "\n")
    }

    /** Package launcher du ROM FYT, ou null si ce n'est pas un ROM FYT. */
    private fun fytLauncher(): String? =
        root("getprop $FYT_LAUNCHER_PROP")?.trim()?.takeIf { it.isNotEmpty() }

    private fun isGuardRunning(): Boolean =
        root("pgrep -f '$GUARD_PROC' >/dev/null && echo 1")?.trim() == "1"

    /** Retire le flag (la boucle s'arrête d'elle-même) et tue le garde, son
     *  keepalive et son logcat. */
    private fun stopGuard() {
        root("rm -f $ENABLED; pkill -f '$GUARD_PROC'; pkill -f '$EVENTS_PROC'; true")
    }

    /**
     * Garde ≤ 0.1.22 : l'arrête, supprime ses fichiers et reprend sa liste de
     * packages désactivés (réactivés ensuite par la réconciliation d'[arm] ou
     * par [disarm]). No-op si absent.
     */
    private fun migrateLegacy() {
        val present = root("ls $LEGACY_SCRIPT $LEGACY_FLAG $LEGACY_DISABLED 2>/dev/null")
        if (present.isNullOrBlank()) return
        // pkill dans sa propre commande : les chemins en clair ci-dessus
        // matcheraient le motif et le shell se tuerait lui-même.
        root("rm -f $LEGACY_FLAG")
        root("pkill -f '$LEGACY_PROC'; true")
        val legacyDisabled = root("cat $LEGACY_DISABLED 2>/dev/null")
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        if (legacyDisabled.isNotEmpty()) {
            root("mkdir -p $DIR && chmod 700 $DIR")
            writeDisabledList((readDisabledList() + legacyDisabled).distinct())
        }
        root("rm -f $LEGACY_SCRIPT $LEGACY_DISABLED")
        Log.i(TAG, "ancien garde migré (désactivés repris : $legacyDisabled)")
    }

    // ── Persistance liste désactivés (fichier root) ─────────────────────

    private fun readDisabledList(): List<String> =
        root("cat $DISABLED_LIST 2>/dev/null")
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun writeDisabledList(entries: List<String>) {
        if (entries.isEmpty()) { clearDisabledList(); return }
        root("printf '%s\\n' ${entries.joinToString(" ") { "'$it'" }} > $DISABLED_LIST")
    }

    private fun clearDisabledList() {
        root("rm -f $DISABLED_LIST")
    }

    // ── Utilitaires ─────────────────────────────────────────────────────

    private fun root(cmd: String): String? = RootShell.execOneShot(cmd)

    /** Écriture atomique (tmp + mv) : un garde en cours d'exécution garde
     *  l'ancien inode du script. Base64 pour éviter tout enfer de quoting. */
    private fun writeRootFile(path: String, content: String, mode: String): Boolean {
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val out = root("echo '$b64' | base64 -d > '$path.tmp' && " +
            "chmod $mode '$path.tmp' && mv -f '$path.tmp' '$path' && echo OK")
        val ok = out?.contains("OK") == true
        if (!ok) Log.e(TAG, "écriture root échouée : $path ($out)")
        return ok
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun armedMarker(context: Context) = File(context.filesDir, ARMED_MARKER)

    private fun setArmedMarker(context: Context, armed: Boolean) {
        val f = armedMarker(context)
        if (armed) f.createNewFile() else f.delete()
    }
}
