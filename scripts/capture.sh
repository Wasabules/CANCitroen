#!/usr/bin/env bash
# Timestamped candump capture.
# Usage: ./capture.sh <label> [duration_seconds]
#   - if duration omitted -> capture jusqu'à Ctrl-C
#   - format candump log (rejouable avec canplayer)
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <label> [duration_seconds]" >&2
  exit 1
fi

LABEL="$1"
DURATION="${2:-}"
IFACE="${CAN_IFACE:-slcan0}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/captures"
TS="$(date +%Y%m%d_%H%M%S)"
OUT="$OUT_DIR/${TS}_${LABEL}.log"

mkdir -p "$OUT_DIR"

if ! ip link show "$IFACE" 2>/dev/null | grep -q "state UP"; then
  echo "[capture] $IFACE n'est pas UP. Lance ./can_up.sh d'abord." >&2
  exit 1
fi

echo "[capture] -> $OUT  (iface=$IFACE)"
if [ -n "$DURATION" ]; then
  echo "[capture] durée=${DURATION}s — appuie sur ta combinaison MAINTENANT."
  timeout "$DURATION" candump -tA -L "$IFACE" > "$OUT" || true
else
  echo "[capture] Ctrl-C pour arrêter."
  candump -tA -L "$IFACE" > "$OUT" || true
fi

LINES=$(wc -l < "$OUT")
echo "[capture] terminé. $LINES trames capturées."
echo "$OUT"
