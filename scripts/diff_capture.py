#!/usr/bin/env python3
"""Diff deux captures candump pour isoler les trames qui changent.

Usage:
    ./diff_capture.py <baseline.log> <pressed.log>

Pour chaque ID CAN vu, montre :
  - présent uniquement dans pressed (nouveau)
  - octets qui changent entre les deux captures, position par position
  - les valeurs distinctes observées

Idéal pour isoler la trame d'un bouton volant.
"""
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _canlog import iter_frames  # noqa: E402


def values_per_byte(frames):
    """Pour un ID donné, retourne la liste de sets {valeurs vues} par position d'octet."""
    if not frames:
        return []
    max_len = max(len(f.data) for f in frames)
    cols = [set() for _ in range(max_len)]
    for f in frames:
        for i, b in enumerate(f.data):
            cols[i].add(b)
    return cols


def index(path):
    """Retourne {can_id: [Frame, ...]}."""
    by_id = defaultdict(list)
    for f in iter_frames(path):
        by_id[(f.can_id, f.extended)].append(f)
    return by_id


def fmt_id(key):
    can_id, extended = key
    return f"{can_id:08X}" if extended else f"{can_id:03X}"


def fmt_set(values):
    return "{" + ",".join(f"{v:02X}" for v in sorted(values)) + "}"


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    base_path, pressed_path = sys.argv[1], sys.argv[2]

    base = index(base_path)
    pressed = index(pressed_path)

    base_ids = set(base)
    pressed_ids = set(pressed)

    new_ids = pressed_ids - base_ids
    common_ids = base_ids & pressed_ids
    gone_ids = base_ids - pressed_ids

    print(f"=== {Path(base_path).name}  vs  {Path(pressed_path).name} ===")
    print(f"  baseline : {sum(len(v) for v in base.values())} trames, "
          f"{len(base_ids)} IDs distincts")
    print(f"  pressed  : {sum(len(v) for v in pressed.values())} trames, "
          f"{len(pressed_ids)} IDs distincts")
    print()

    if new_ids:
        print(f"--- IDs APPARUS dans pressed (n={len(new_ids)}) ---")
        for k in sorted(new_ids):
            frames = pressed[k]
            sample = frames[0]
            print(f"  {fmt_id(k):>8}  count={len(frames):4d}  "
                  f"sample={sample.hex_data}")
        print()

    if gone_ids:
        print(f"--- IDs DISPARUS dans pressed (n={len(gone_ids)}) ---")
        for k in sorted(gone_ids):
            print(f"  {fmt_id(k):>8}  count_baseline={len(base[k])}")
        print()

    print(f"--- IDs COMMUNS dont la charge utile diffère (n={len(common_ids)}) ---")
    diffs = []
    for k in sorted(common_ids):
        base_cols = values_per_byte(base[k])
        pressed_cols = values_per_byte(pressed[k])
        max_len = max(len(base_cols), len(pressed_cols))
        differing_bytes = []
        for i in range(max_len):
            b_set = base_cols[i] if i < len(base_cols) else set()
            p_set = pressed_cols[i] if i < len(pressed_cols) else set()
            new_values = p_set - b_set
            if new_values:
                differing_bytes.append((i, b_set, p_set, new_values))
        if differing_bytes:
            diffs.append((k, differing_bytes))

    # Trie par "intéressance" (peu de bytes qui bougent => signal plus net)
    diffs.sort(key=lambda x: (len(x[1]), fmt_id(x[0])))

    for k, differing in diffs:
        print(f"  {fmt_id(k):>8}  -> {len(differing)} octet(s) avec valeurs nouvelles")
        for i, b_set, p_set, new_values in differing:
            print(f"      byte[{i}]  baseline={fmt_set(b_set):<20}  "
                  f"pressed={fmt_set(p_set):<25}  nouveau={fmt_set(new_values)}")
    if not diffs:
        print("  (aucune différence d'octets — le bouton ne génère peut-être pas de trame, "
              "ou la baseline contenait déjà la valeur pressée)")


if __name__ == "__main__":
    main()
