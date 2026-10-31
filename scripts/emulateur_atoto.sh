#!/usr/bin/env bash
# Lance l'émulateur de l'Atoto avec une marge audio suffisante pour limiter les trous
# (voir docs/app/developpement.md).
# Usage : scripts/emulateur_atoto.sh [nom_avd] [options de l'émulateur...]
set -euo pipefail

avd="${1:-Atoto_Tablette_8}"
[ "$#" -gt 0 ] && shift

# qemu demande 5 ms de tampon audio : trop juste, le moindre retard fait un trou.
export PULSE_LATENCY_MSEC="${PULSE_LATENCY_MSEC:-80}"

# Une instance qui se termine garde ses verrous quelques secondes : l'émulateur refuserait de démarrer.
for _ in $(seq 1 30); do
    pgrep -f "qemu-system-[^ ]* .*-avd $avd( |\$)" >/dev/null || break
    sleep 1
done

# -no-snapshot : le démarrage complet ne prend que ~25 s, et les instantanés échouent au rechargement.
exec "${ANDROID_HOME:-$HOME/Android/Sdk}/emulator/emulator" -avd "$avd" -gpu host -no-snapshot "$@"
