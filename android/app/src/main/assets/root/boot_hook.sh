#!/system/bin/sh
# CANCitroen — relance le garde launcher au boot (service.d Magisk / KernelSU).
# Installé par LauncherGuard.kt à l'armement, supprimé au désarmement. Le garde
# attend lui-même sys.boot_completed ; s'il est déjà lancé (par l'app), rien.
DIR=/data/local/tmp/cancitroen
[ -f "$DIR/enabled" ] && [ -f "$DIR/home_guard.sh" ] || exit 0
pgrep -f "$DIR/[h]ome_guard" >/dev/null && exit 0
setsid sh "$DIR/home_guard.sh" </dev/null >/dev/null 2>&1 &
