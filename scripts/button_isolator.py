#!/usr/bin/env python3
"""Isole interactivement la trame/octet d'un bouton volant.

Méthodologie :
  1. Le script enregistre une fenêtre "IDLE" (rien n'est appuyé)
  2. Puis une fenêtre "PRESS" pendant laquelle TU APPUIES sur le bouton
  3. Répète N fois pour éliminer le bruit (heartbeats, compteurs)
  4. Affiche le score de corrélation ID×octet : un score proche de 1.0 = candidat fort.

Un bon candidat = un octet qui prend une valeur unique pendant PRESS et JAMAIS
pendant IDLE, sur les N essais.

Usage:
    ./button_isolator.py <nom_bouton> [--iface slcan0] [--cycles 3] [--idle 3] [--press 2]
"""
from __future__ import annotations

import argparse
import sys
import time
from collections import defaultdict

import can


def collect(bus, duration_s, label):
    print(f"  [{label}] capture {duration_s}s — GO")
    end = time.time() + duration_s
    seen = defaultdict(set)  # (can_id, ext) -> set of (byte_index, value)
    n = 0
    while time.time() < end:
        msg = bus.recv(timeout=0.5)
        if msg is None:
            continue
        n += 1
        key = (msg.arbitration_id, msg.is_extended_id)
        for i, b in enumerate(msg.data):
            seen[key].add((i, b))
    print(f"  [{label}] {n} trames capturées sur {len(seen)} IDs")
    return seen


def fmt_id(key):
    can_id, ext = key
    return f"{can_id:08X}" if ext else f"{can_id:03X}"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("name")
    ap.add_argument("--iface", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--idle", type=float, default=3.0,
                    help="durée IDLE par cycle (s)")
    ap.add_argument("--press", type=float, default=2.0,
                    help="durée PRESS par cycle (s)")
    args = ap.parse_args()

    bus = can.Bus(channel=args.iface, interface=args.bustype)
    print(f"=== Isolation bouton: {args.name} ===")
    print(f"iface={args.iface}  cycles={args.cycles}  idle={args.idle}s  press={args.press}s\n")

    idle_obs = []   # par cycle: dict (id) -> set((byte, val))
    press_obs = []
    try:
        for c in range(1, args.cycles + 1):
            print(f"--- Cycle {c}/{args.cycles} ---")
            print("  IDLE: NE TOUCHE À RIEN (laisse le contact ON, mains éloignées du volant)")
            for s in (3, 2, 1):
                print(f"   {s}...", end=" ", flush=True); time.sleep(1)
            print()
            idle_obs.append(collect(bus, args.idle, "IDLE"))

            print(f"  PRESS: appuie sur '{args.name}' pendant toute la fenêtre")
            for s in (3, 2, 1):
                print(f"   {s}...", end=" ", flush=True); time.sleep(1)
            print()
            press_obs.append(collect(bus, args.press, "PRESS"))
    except KeyboardInterrupt:
        print("\n[button_isolator] interrompu.")
        bus.shutdown()
        sys.exit(1)

    bus.shutdown()

    # Pour chaque (id, byte_index, value) : compter cycles IDLE / cycles PRESS où vu
    # Candidat = vu dans TOUS les PRESS et AUCUN des IDLE
    all_keys = set()
    for d in idle_obs + press_obs:
        for k, vals in d.items():
            for (bi, v) in vals:
                all_keys.add((k, bi, v))

    candidates = []
    for (k, bi, v) in all_keys:
        idle_hits = sum(1 for d in idle_obs if (bi, v) in d.get(k, ()))
        press_hits = sum(1 for d in press_obs if (bi, v) in d.get(k, ()))
        if press_hits == args.cycles and idle_hits == 0:
            candidates.append((k, bi, v, press_hits, idle_hits))

    print(f"\n=== Résultats pour '{args.name}' ===")
    if not candidates:
        # rapport plus permissif
        print("Aucun candidat parfait. Top 10 par score (press_hits - idle_hits):")
        scored = []
        for (k, bi, v) in all_keys:
            ih = sum(1 for d in idle_obs if (bi, v) in d.get(k, ()))
            ph = sum(1 for d in press_obs if (bi, v) in d.get(k, ()))
            scored.append((ph - ih, k, bi, v, ph, ih))
        scored.sort(reverse=True)
        for sc, k, bi, v, ph, ih in scored[:10]:
            print(f"  score={sc:+d}  {fmt_id(k):>8}  byte[{bi}]=0x{v:02X}  "
                  f"press={ph}/{args.cycles}  idle={ih}/{args.cycles}")
    else:
        print(f"{len(candidates)} candidat(s) parfait(s) (vu dans tous les PRESS, "
              f"jamais en IDLE):")
        # group by (id, byte_index)
        by_id_byte = defaultdict(list)
        for (k, bi, v, ph, ih) in candidates:
            by_id_byte[(k, bi)].append(v)
        for (k, bi), vals in sorted(by_id_byte.items()):
            vstr = ", ".join(f"0x{v:02X}" for v in sorted(vals))
            print(f"  {fmt_id(k):>8}  byte[{bi}] = {vstr}")


if __name__ == "__main__":
    sys.exit(main())
