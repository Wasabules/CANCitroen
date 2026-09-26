#!/usr/bin/env bash
# Bring down slcan0 and kill the slcand daemon.
set -euo pipefail
IFACE="${CAN_IFACE:-slcan0}"
TTY="${CAN_TTY:-/dev/ttyACM0}"

if ip link show "$IFACE" >/dev/null 2>&1; then
  sudo ip link set "$IFACE" down
  echo "[can_down] $IFACE down."
else
  echo "[can_down] $IFACE n'existe pas."
fi

if pgrep -f "slcand.*$TTY" >/dev/null; then
  sudo pkill -f "slcand.*$TTY"
  echo "[can_down] slcand killed."
fi
