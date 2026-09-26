#!/usr/bin/env python3
"""Live monitor : affiche uniquement les IDs dont la charge utile vient de changer.

Très efficace pour repérer un bouton volant : tu lances le script, tu appuies,
seules les trames qui ont muté apparaissent.

Usage:
    ./sniff_changes.py [iface]        # default slcan0
    ./sniff_changes.py slcan0 --hold 2.0   # garde l'historique 2s pour comparer

Astuces:
  - les trames cycliques qui ne changent JAMAIS sont silencieuses
  - les compteurs / heartbeat qui changent en permanence apparaîtront en flux
    continu — note leur ID pour les ignorer (--ignore 1A0,2B1)
"""
from __future__ import annotations

import argparse
import sys
import time

import can


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("iface", nargs="?", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    ap.add_argument("--ignore", default="",
                    help="IDs hex à ignorer, séparés par virgule (ex: 1A0,2B1)")
    ap.add_argument("--only", default="",
                    help="N'afficher QUE ces IDs hex (séparés par virgule)")
    args = ap.parse_args()

    ignore = {int(x, 16) for x in args.ignore.split(",") if x.strip()}
    only = {int(x, 16) for x in args.only.split(",") if x.strip()}

    bus = can.Bus(channel=args.iface, interface=args.bustype)
    print(f"[sniff_changes] iface={args.iface}  ignore={ignore or '-'}  only={only or '-'}")
    print("[sniff_changes] Ctrl-C pour quitter. Appuie sur tes boutons...\n")

    last = {}  # can_id -> bytes
    try:
        while True:
            msg = bus.recv(timeout=1.0)
            if msg is None:
                continue
            if msg.arbitration_id in ignore:
                continue
            if only and msg.arbitration_id not in only:
                continue
            data = bytes(msg.data)
            prev = last.get(msg.arbitration_id)
            if prev != data:
                ts = time.strftime("%H:%M:%S", time.localtime(msg.timestamp))
                ms = int((msg.timestamp % 1) * 1000)
                id_str = (f"{msg.arbitration_id:08X}" if msg.is_extended_id
                          else f"{msg.arbitration_id:03X}")
                if prev is None:
                    diff_str = "(NEW)"
                else:
                    # marqueur sur les octets qui changent
                    marks = "".join(
                        "^^" if i < len(prev) and i < len(data) and prev[i] != data[i]
                        else ".." for i in range(max(len(prev), len(data)))
                    )
                    diff_str = f"prev={prev.hex(' ').upper()}  Δ={marks}"
                print(f"{ts}.{ms:03d}  {id_str:>8}  {data.hex(' ').upper():<24}  {diff_str}")
                last[msg.arbitration_id] = data
    except KeyboardInterrupt:
        print("\n[sniff_changes] arrêté.")
    finally:
        bus.shutdown()


if __name__ == "__main__":
    sys.exit(main())
