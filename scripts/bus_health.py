#!/usr/bin/env python3
"""Diagnostic du bus CAN à la connexion.

Le 1er truc à lancer quand tu te branches sur la voiture. Te dit si :
  - le bus parle (combien de trames/s, IDs distincts)
  - le bitrate est correct (erreurs RX, error frames)
  - les IDs PSA AEE2004 connus sont présents (confirme qu'on est bien sur CONF)

Usage:
    ./bus_health.py                   # diag sur slcan0 (déjà UP)
    ./bus_health.py --auto-bitrate    # essaie 125k, 500k, 250k pour trouver le bon
    ./bus_health.py --duration 10     # écoute plus longtemps (défaut 5s)
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

import can

# IDs connus PSA AEE2004 — si on en voit, c'est qu'on est bien sur le CAN-Confort
KNOWN_PSA_IDS = {
    0x036, 0x0B6, 0x0F6, 0x122, 0x125, 0x128, 0x161,
    0x1A1, 0x1A8, 0x1D0, 0x1E0,
    0x21F, 0x220, 0x221, 0x228, 0x261, 0x276,
    0x2A1, 0x2B6,
    0x336, 0x39B, 0x3A7, 0x3B6,
}


def get_iface_stats(iface):
    base = Path(f"/sys/class/net/{iface}/statistics")
    if not base.exists():
        return None
    out = {}
    for f in base.iterdir():
        try:
            out[f.name] = int(f.read_text().strip())
        except Exception:
            pass
    return out


def iface_is_up(iface):
    try:
        r = subprocess.run(["ip", "-d", "link", "show", iface],
                           capture_output=True, text=True)
        return "state UP" in r.stdout
    except Exception:
        return False


def listen(iface, duration_s):
    bus = can.Bus(channel=iface, interface="socketcan")
    deadline = time.time() + duration_s
    by_id = defaultdict(int)
    payloads = defaultdict(set)
    n = err = 0
    try:
        while time.time() < deadline:
            msg = bus.recv(timeout=0.2)
            if msg is None:
                continue
            if msg.is_error_frame:
                err += 1
                continue
            n += 1
            by_id[msg.arbitration_id] += 1
            if len(payloads[msg.arbitration_id]) < 32:
                payloads[msg.arbitration_id].add(bytes(msg.data))
    finally:
        bus.shutdown()
    return {
        "n_frames": n, "n_err_frames": err,
        "by_id": dict(by_id),
        "n_payloads": {k: len(v) for k, v in payloads.items()},
        "duration": duration_s,
    }


def diag_health(iface, duration_s):
    if not iface_is_up(iface):
        print(f"❌ Interface {iface} pas UP. Lance ./scripts/can_up.sh d'abord.")
        return 1

    print(f"=== Diagnostic bus CAN ({iface}, {duration_s}s) ===\n")

    stats_before = get_iface_stats(iface) or {}
    print(f"Écoute {duration_s}s...")
    r = listen(iface, duration_s)
    stats_after = get_iface_stats(iface) or {}

    rx_err = stats_after.get("rx_errors", 0) - stats_before.get("rx_errors", 0)
    rx_drop = stats_after.get("rx_dropped", 0) - stats_before.get("rx_dropped", 0)
    rate = r["n_frames"] / max(duration_s, 1)

    print(f"\n  Trames reçues       : {r['n_frames']}  ({rate:.1f}/s)")
    print(f"  IDs distincts       : {len(r['by_id'])}")
    print(f"  Trames d'erreur     : {r['n_err_frames']}")
    print(f"  RX errors (système) : {rx_err}")
    print(f"  RX dropped          : {rx_drop}")
    print()

    if r["n_frames"] == 0:
        print("⚠️  AUCUNE TRAME REÇUE.\n")
        print("   Causes les plus probables :")
        print("   1. Mauvais bitrate")
        print("        → ./scripts/bus_health.py --auto-bitrate")
        print("   2. CAN-H et CAN-L inversés sur le branchement")
        print("        → permute les deux fils et relance")
        print("   3. Contact pas en position ON (juste ACC ne suffit pas toujours)")
        print("        → tourne la clé d'un cran de plus")
        print("   4. Bus endormi (la BSI passe en veille après ~30s sans activité)")
        print("        → ouvre une porte ou démarre le moteur")
        print("   5. Mauvais point de prélèvement")
        print("        → essaie un autre faisceau (combiné, BSI sous le volant)")
        print("   6. CANable mal configuré")
        print("        → vérifie que le jumper TERM est sur OFF\n")
        return 2

    if r["n_err_frames"] > 5 or rx_err > 10:
        print("⚠️  ERREURS DÉTECTÉES sur le bus.\n")
        print("   Causes probables :")
        print("   - Bitrate faux (essaie --auto-bitrate)")
        print("   - Mauvais contact, faux contact intermittent")
        print("   - Terminaison parasite (jumper TERM CANable ?)\n")

    if rate < 5:
        print("⚠️  BUS TRÈS PEU ACTIF (<5 trames/s).")
        print("   La voiture est peut-être en veille — démarre le moteur ou\n"
              "   active une fonction (radio, clim, lumières).\n")

    # Top 10 IDs
    print("Top 15 IDs par volume :")
    print(f"  {'ID':>6}  {'count':>6}  {'rate/s':>8}  payloads")
    print("  " + "─" * 50)
    top = sorted(r["by_id"].items(), key=lambda x: -x[1])[:15]
    for cid, count in top:
        np = r["n_payloads"][cid]
        marker = " ★" if cid in KNOWN_PSA_IDS else ""
        is29 = cid > 0x7FF
        idstr = f"{cid:08X}" if is29 else f"{cid:03X}"
        print(f"  {idstr:>6}  {count:>6}  {count/duration_s:>8.1f}  {np} distinct{marker}")
    print("\n  ★ = ID PSA AEE2004 connu")

    seen_known = KNOWN_PSA_IDS & set(r["by_id"].keys())
    if seen_known:
        print(f"\n  ✅ {len(seen_known)} IDs PSA AEE2004 reconnus : "
              + ", ".join(f"0x{i:03X}" for i in sorted(seen_known)))
        # IDs critiques pour notre projet
        critical = {0x0B6, 0x0F6, 0x21F, 0x276}
        missing = critical - seen_known
        if missing:
            print(f"  ⚠️  Critiques absents : "
                  + ", ".join(f"0x{i:03X}" for i in sorted(missing)))
            for cid in missing:
                hint = {
                    0x0B6: "RPM/vitesse — démarre le moteur ou avance",
                    0x0F6: "T°/odo — devrait être présent contact ON",
                    0x21F: "boutons volant — appuie sur une touche pour le réveiller",
                    0x276: "horloge BSI — émis par cycle ~1s, devrait être là",
                }.get(cid, "")
                if hint:
                    print(f"      0x{cid:03X} : {hint}")
    else:
        print("\n  ⚠️  Aucun ID PSA AEE2004 reconnu. Possibilités :")
        print("     - Ce n'est pas le bus CONF (peut-être l'IS, mais il est à 500k)")
        print("     - Millésime / équipement avec mapping non standard")
        print("     - Bitrate proche mais faux (250k au lieu de 125k → trames partielles)")

    if r["n_frames"] > 0 and not (r["n_err_frames"] > 5 or rx_err > 10):
        print("\n✅ BUS SAIN. Tu peux passer à l'étape suivante.")

    return 0


def auto_bitrate():
    project = Path(__file__).resolve().parent.parent
    can_up = project / "scripts" / "can_up.sh"
    can_down = project / "scripts" / "can_down.sh"

    rates = [125000, 500000, 250000, 100000]
    print("=== Auto-détection bitrate ===")
    print("Essai des bitrates les plus courants (CONF=125k, IS=500k)...\n")

    results = []
    for rate in rates:
        subprocess.run([str(can_down)], capture_output=True)
        time.sleep(0.3)
        rc = subprocess.run([str(can_up), str(rate)], capture_output=True, text=True)
        if rc.returncode != 0:
            print(f"  {rate:>7} bit/s  →  bring-up FAILED ({rc.stderr.strip()[:60]})")
            continue
        time.sleep(0.5)
        try:
            r = listen("slcan0", 2.0)
        except Exception as e:
            print(f"  {rate:>7} bit/s  →  listen failed: {e}")
            continue
        stats = get_iface_stats("slcan0") or {}
        rx_err = stats.get("rx_errors", 0)
        score = r["n_frames"] - 5 * r["n_err_frames"] - rx_err
        is_psa = bool(KNOWN_PSA_IDS & set(r["by_id"].keys()))
        if is_psa:
            score += 50  # gros bonus si on reconnaît PSA
        marker = " ← IDs PSA reconnus" if is_psa else ""
        print(f"  {rate:>7} bit/s  →  {r['n_frames']:>4} trames, "
              f"{len(r['by_id']):>2} IDs, {r['n_err_frames']} err, "
              f"score={score}{marker}")
        results.append((rate, score, r["n_frames"], is_psa))

    subprocess.run([str(can_down)], capture_output=True)

    print()
    if not results or all(s <= 0 for _, s, _, _ in results):
        print("❌ Aucun bitrate ne donne de trafic propre.")
        print("   Vérifie le câblage physique (H/L, contact ON, point de prélèvement).")
        return 1

    best = max(results, key=lambda x: x[1])
    print(f"✅ Bitrate optimal : {best[0]} bit/s")
    if best[3]:
        print("   IDs PSA reconnus → c'est très probablement le bon.")
    print(f"\n   Re-monte avec : ./scripts/can_up.sh {best[0]}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--iface", default="slcan0")
    ap.add_argument("--duration", type=float, default=5.0)
    ap.add_argument("--auto-bitrate", action="store_true",
                    help="essaie plusieurs bitrates (démonte/remonte slcan0 plusieurs fois)")
    args = ap.parse_args()

    if args.auto_bitrate:
        return auto_bitrate() or 0
    return diag_health(args.iface, args.duration)


if __name__ == "__main__":
    sys.exit(main())
