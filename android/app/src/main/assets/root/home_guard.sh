#!/system/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# CANCitroen — garde launcher (daemon root)
#
# Installé, configuré (guard.conf) et démarré par LauncherGuard.kt ; relancé au
# boot par le hook Magisk service.d quand il est disponible. Réécrit à chaque
# armement : ne pas éditer sur l'appareil.
#
# Périmètre volontairement étroit :
#  1. Remplacement du launcher : quand l'un des composants HOME concurrents
#     listés dans guard.conf (ex. launcher8, celui du ROM FYT) passe au premier
#     plan, on ramène CANCitroen. Comparaison au composant EXACT : Android
#     Auto, Réglages, Maps ou toute autre activité — y compris une autre
#     activité du package du launcher — ne déclenchent jamais rien.
#  2. Keepalive : si le process de l'app a disparu (kill mémoire, crash…) et
#     que l'unité est éveillée, on relance CanService. Jamais pendant la veille
#     (contact coupé) : son wakelock empêcherait l'unité de s'endormir.
#
# Détection du premier plan :
#  - "events" : flux logcat des reprises d'activité (am_set_resumed_activity
#    jusqu'à Android 10, wm_set_resumed_activity ensuite) → réaction
#    immédiate, aucun sondage ;
#  - "poll"   : repli si le ROM n'émet pas ces évènements (dumpsys périodique).
# Chaque déclenchement est confirmé par dumpsys avant d'agir : un évènement
# peut être périmé (l'utilisateur a déjà ouvert autre chose entre-temps).
#
# Journal : logcat -s CANCitroenGuard        État lu par l'app : $DIR/run/
# ─────────────────────────────────────────────────────────────────────────────

DIR=/data/local/tmp/cancitroen
RUN=$DIR/run
ENABLED=$DIR/enabled
TAG=CANCitroenGuard

# Définit OUR_PKG OUR_ACTIVITY OUR_SERVICE COMPETITORS POLL_INTERVAL
# KEEPALIVE_INTERVAL BOOT_HOOK.
. "$DIR/guard.conf" || exit 1

EV_TAGS="am_set_resumed_activity wm_set_resumed_activity"

guard_enabled() { [ -f "$ENABLED" ]; }

note() { log -t "$TAG" "$*"; }
warn() { log -p w -t "$TAG" "$*"; }

# Un fichier par valeur dans $RUN, lus par LauncherGuard.refreshStatus().
put()  { echo "$2" > "$RUN/$1"; }
bump() { n=$(cat "$RUN/$1" 2>/dev/null); put "$1" $(( ${n:-0} + 1 )); }

# Ligne dumpsys de l'activité au premier plan (formats Android 10 → 16).
current_top() {
  dumpsys activity activities 2>/dev/null |
    grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity:'
}

# Vrai si la ligne $1 cite l'un des composants concurrents, délimité des deux
# côtés : ".Launcher" ne doit pas matcher ".LauncherSettings".
MATCHED=
is_competitor() {
  for c in $COMPETITORS; do
    case " $1 " in
      *[!A-Za-z0-9_.]"$c"[!A-Za-z0-9_.]*) MATCHED=$c; return 0 ;;
    esac
  done
  return 1
}

# Anti ping-pong : si le ROM relance son launcher en boucle, on ne se bat pas
# indéfiniment (au plus 5 rattrapages par fenêtre de 20 s, puis pause 30 s).
WIN_START=0
WIN_COUNT=0
on_competitor() {
  # Laisse la transition se terminer, puis confirme sur l'état réel.
  sleep 0.3
  is_competitor "$(current_top)" || return 0

  now=$(date +%s)
  if [ $((now - WIN_START)) -ge 20 ]; then WIN_START=$now; WIN_COUNT=0; fi
  WIN_COUNT=$((WIN_COUNT + 1))
  if [ "$WIN_COUNT" -gt 5 ]; then
    warn "conflit persistant avec $MATCHED — pause 30 s (essayer le mode agressif)"
    put last_conflict "$now"
    sleep 30
    WIN_START=$(date +%s); WIN_COUNT=1
    is_competitor "$(current_top)" || return 0
    now=$(date +%s)
  fi

  note "$MATCHED au premier plan → retour à CANCitroen"
  # Intent HOME explicite : lancé par root, Android le traite comme un vrai
  # retour à l'accueil et réutilise l'instance home existante.
  am start -a android.intent.action.MAIN -c android.intent.category.HOME \
    -n "$OUR_ACTIVITY" >/dev/null 2>&1
  bump catches
  put last_catch "$now $MATCHED"
}

events_supported() {
  logcat -b events -d -s $EV_TAGS 2>/dev/null | grep -q set_resumed_activity
}

# Bloque tant que logcat tourne. -T 1 : ne rejoue pas l'historique (au plus
# une ligne ancienne, de toute façon confirmée par on_competitor).
watch_events() {
  logcat -b events -v brief -T 1 -s $EV_TAGS 2>/dev/null |
  while guard_enabled && read -r line; do
    is_competitor "$line" && on_competitor
  done
}

# Repli par sondage ; rend la main toutes les ~60 s pour retenter "events".
watch_poll() {
  i=0
  while guard_enabled && [ "$i" -lt 60 ]; do
    is_competitor "$(current_top)" && on_competitor
    sleep "$POLL_INTERVAL"
    i=$((i + POLL_INTERVAL))
  done
}

package_installed() {
  cmd package path "$1" 2>/dev/null | grep -q '^package:'
}

# L'app a été désinstallée sans désarmer : on remet le système en état (le
# launcher d'origine réactivé, pas de hook de boot orphelin) et on s'arrête.
check_installed() {
  package_installed "$OUR_PKG" && return 0
  # Faux négatif possible pendant un redémarrage de system_server : on exige
  # que le service package réponde, et deux échecs à 5 s d'écart.
  package_installed android || return 0
  sleep 5
  package_installed "$OUR_PKG" && return 0

  warn "$OUR_PKG désinstallée → nettoyage et arrêt du garde"
  if [ -f "$DIR/disabled" ]; then
    for c in $(cat "$DIR/disabled"); do pm enable "$c" >/dev/null 2>&1; done
  fi
  # Launcher natif FYT rendu au ROM (valeur sauvegardée par LauncherGuard.kt).
  if [ -f "$DIR/lsec_launcher.orig" ]; then
    setprop persist.lsec.launcher "$(cat "$DIR/lsec_launcher.orig")"
  fi
  rm -f "$BOOT_HOOK"
  rm -rf "$DIR"
  pkill -f 'logcat.*[s]et_resumed_activity'
  pkill -f "$DIR/[h]ome_guard"   # nous-mêmes : process principal + keepalive
  exit 0
}

# Contact coupé, le ROM tue les apps puis met l'unité en veille. CanService
# tient un wakelock : le relancer à ce moment-là empêcherait la veille et
# viderait la batterie de la voiture. On ne relance donc qu'unité éveillée
# (au réveil, le ROM relance de toute façon HOME, donc CANCitroen).
device_awake() {
  dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'
}

keepalive_loop() {
  backoff=0
  while sleep "$KEEPALIVE_INTERVAL" && guard_enabled; do
    check_installed
    if pidof "$OUR_PKG" >/dev/null 2>&1; then backoff=0; continue; fi
    device_awake || continue
    note "process $OUR_PKG absent → relance de CanService"
    am start-foreground-service -n "$OUR_SERVICE" >/dev/null 2>&1
    bump revives
    put last_revive "$(date +%s)"
    # Relance qui ne prend pas (app qui plante au démarrage…) : tentatives
    # espacées de plus en plus, jusqu'à 5 min, plutôt que toutes les 20 s.
    [ "$backoff" -gt 0 ] && sleep "$backoff"
    if [ "$backoff" -eq 0 ]; then backoff=$KEEPALIVE_INTERVAL; else backoff=$((backoff * 2)); fi
    [ "$backoff" -gt 300 ] && backoff=300
  done
}

# ── Main ─────────────────────────────────────────────────────────────────────

guard_enabled || exit 0
# Lancé par le hook de boot avant la fin du démarrage : on attend Android.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
check_installed

rm -rf "$RUN"
mkdir -p "$RUN"
put since "$(date +%s)"
note "démarrage (pid $$), concurrents : ${COMPETITORS:-aucun}"

keepalive_loop &

# Le launcher d'origine a pu passer devant avant notre démarrage.
is_competitor "$(current_top)" && on_competitor

fails=0
while guard_enabled; do
  if [ "$fails" -lt 3 ] && events_supported; then
    put mode events
    t0=$(date +%s)
    watch_events
    # logcat qui meurt aussitôt, à répétition → bascule en sondage.
    if [ $(($(date +%s) - t0)) -lt 5 ]; then fails=$((fails + 1)); else fails=0; fi
  else
    put mode poll
    watch_poll
  fi
  guard_enabled && sleep 1
done
note "arrêt"
