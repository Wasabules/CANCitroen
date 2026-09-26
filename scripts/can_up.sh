#!/usr/bin/env bash
# Bring up slcan0 from a CANable (slcan firmware) on /dev/ttyACM0.
# Usage: ./can_up.sh [bitrate]
#   bitrate: 10000 | 20000 | 50000 | 100000 | 125000 (default, CAN-Confort PSA) |
#            250000 | 500000 (CAN-IS PSA) | 800000 | 1000000
set -euo pipefail

BITRATE="${1:-125000}"
TTY="${CAN_TTY:-/dev/ttyACM0}"
IFACE="${CAN_IFACE:-slcan0}"

# Map bitrate -> slcand -s flag
case "$BITRATE" in
  10000)   S=0 ;;
  20000)   S=1 ;;
  50000)   S=2 ;;
  100000)  S=3 ;;
  125000)  S=4 ;;
  250000)  S=5 ;;
  500000)  S=6 ;;
  800000)  S=7 ;;
  1000000) S=8 ;;
  *) echo "Bitrate non supporté: $BITRATE" >&2; exit 1 ;;
esac

if [ ! -e "$TTY" ]; then
  echo "Pas de $TTY — le CANable est-il branché ?" >&2
  exit 1
fi

# Tear down a previous instance if it exists
if ip link show "$IFACE" >/dev/null 2>&1; then
  echo "[can_up] $IFACE existe déjà, on le démonte d'abord."
  sudo ip link set "$IFACE" down 2>/dev/null || true
  sudo pkill -f "slcand.*$TTY" 2>/dev/null || true
  sleep 0.3
fi

echo "[can_up] slcand -o -c -s$S -S 3000000 $TTY $IFACE  (bitrate ${BITRATE} bit/s)"
sudo slcand -o -c -s"$S" -S 3000000 "$TTY" "$IFACE"
sleep 0.3
sudo ip link set "$IFACE" up
# Allow non-root candump on the txqueue (optional)
sudo ip link set "$IFACE" txqueuelen 1000 || true

echo "[can_up] OK :"
ip -details -statistics link show "$IFACE" | head -15
