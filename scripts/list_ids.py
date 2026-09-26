#!/usr/bin/env python3
"""Inventaire des IDs présents sur le bus, avec période moyenne et payload type.

Utile pour avoir une vue d'ensemble du bus avant de chercher un bouton.

Usage:
    ./list_ids.py <capture.log>
    ./list_ids.py --live slcan0 --duration 10
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _canlog import iter_frames  # noqa: E402


def analyze(frames_iter):
    by_id = defaultdict(list)
    for f in frames_iter:
        by_id[(f.can_id, f.extended)].append(f)

    rows = []
    for k, frames in by_id.items():
        ts = [f.ts for f in frames]
        if len(ts) > 1:
            deltas = [t2 - t1 for t1, t2 in zip(ts, ts[1:])]
            period_ms = statistics.mean(deltas) * 1000
            jitter_ms = statistics.pstdev(deltas) * 1000
        else:
            period_ms = jitter_ms = 0.0
        # payload variability
        payloads = {bytes(f.data) for f in frames}
        rows.append({
            "id": (f"{k[0]:08X}" if k[1] else f"{k[0]:03X}"),
            "count": len(frames),
            "period_ms": period_ms,
            "jitter_ms": jitter_ms,
            "n_distinct_payloads": len(payloads),
            "sample": frames[0].hex_data,
        })
    rows.sort(key=lambda r: r["id"])
    return rows


def print_rows(rows):
    print(f"{'ID':>8}  {'count':>6}  {'period_ms':>10}  {'jitter_ms':>9}  "
          f"{'#payloads':>9}  sample")
    print("-" * 80)
    for r in rows:
        print(f"{r['id']:>8}  {r['count']:>6}  {r['period_ms']:>10.2f}  "
              f"{r['jitter_ms']:>9.2f}  {r['n_distinct_payloads']:>9}  {r['sample']}")


def live_capture(iface, duration):
    import can
    bus = can.Bus(channel=iface, interface="socketcan")
    end = time.time() + duration
    frames = []

    class _F:
        __slots__ = ("ts", "iface", "can_id", "extended", "data")

        def __init__(self, ts, iface, can_id, extended, data):
            self.ts = ts
            self.iface = iface
            self.can_id = can_id
            self.extended = extended
            self.data = data

        @property
        def hex_data(self):
            return self.data.hex(" ").upper()

    print(f"[list_ids] live {duration}s sur {iface}...")
    while time.time() < end:
        msg = bus.recv(timeout=0.5)
        if msg is None:
            continue
        frames.append(_F(msg.timestamp, iface, msg.arbitration_id,
                         msg.is_extended_id, bytes(msg.data)))
    bus.shutdown()
    return frames


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", nargs="?", help="fichier .log candump")
    ap.add_argument("--live", metavar="IFACE",
                    help="capture live sur l'interface au lieu de lire un fichier")
    ap.add_argument("--duration", type=float, default=10.0,
                    help="durée en mode --live (s, défaut 10)")
    args = ap.parse_args()

    if args.live:
        frames = live_capture(args.live, args.duration)
    else:
        if not args.source:
            ap.error("indique un fichier .log ou utilise --live IFACE")
        frames = list(iter_frames(args.source))

    rows = analyze(frames)
    print_rows(rows)
    print(f"\nTotal: {sum(r['count'] for r in rows)} trames, "
          f"{len(rows)} IDs distincts")


if __name__ == "__main__":
    sys.exit(main())
