#!/usr/bin/env python3
"""Régler l'horloge de la BSI Citroën C2 via CAN-Confort.

Émet la trame 0x39B (set de l'heure depuis l'IHM) que la BSI absorbe.
Référence : projet ludwig-v/arduino-psa-comfort-can-adapter.

Usage:
    ./set_clock.py                       # utilise l'heure système actuelle
    ./set_clock.py --datetime "2026-04-26 14:35"
    ./set_clock.py --iface slcan0
    ./set_clock.py --dry-run             # affiche la trame sans envoyer

Le script :
  1. Émet 0x39B (set heure) — la BSI met à jour son horloge interne
  2. Optionnel: ré-émet aussi 0x276 (broadcast) avec les mêmes valeurs pour
     forcer le rafraîchissement immédiat des autres ECUs (combiné, radio).
     ATTENTION : la BSI émet déjà 0x276, conflit possible. Désactivé par défaut.

Pré-requis : interface slcan0 UP au bitrate 125000 (./scripts/can_up.sh 125000)
et adaptateur branché sur le bus CAN-Confort de la voiture, contact ON.
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys

import can


def build_39b(now: dt.datetime) -> bytes:
    """Format set-heure : b0=année-1872, b1=mois, b2=jour, b3=heure, b4=minute."""
    year_byte = now.year - 1872
    if not 0 <= year_byte <= 255:
        raise ValueError(f"Année hors range CAN PSA: {now.year}")
    return bytes([year_byte, now.month, now.day, now.hour, now.minute])


def build_276(now: dt.datetime) -> bytes:
    """Format broadcast date+heure (7 octets)."""
    return bytes([now.year - 1872, now.month, now.day, now.hour, now.minute, 0x3F, 0xFE])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--iface", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    ap.add_argument("--datetime", default=None,
                    help='Date/heure cible "YYYY-MM-DD HH:MM" (défaut = maintenant)')
    ap.add_argument("--also-broadcast", action="store_true",
                    help="Émet aussi 0x276 (rafraîchit affichage radio mais conflit avec BSI)")
    ap.add_argument("--dry-run", action="store_true",
                    help="Affiche les trames sans les envoyer")
    args = ap.parse_args()

    if args.datetime:
        now = dt.datetime.strptime(args.datetime, "%Y-%m-%d %H:%M")
    else:
        now = dt.datetime.now().replace(second=0, microsecond=0)

    payload_39b = build_39b(now)
    payload_276 = build_276(now)

    print(f"[set_clock] cible : {now.strftime('%Y-%m-%d %H:%M')}")
    print(f"[set_clock] 0x39B  data = {payload_39b.hex(' ').upper()}  (set heure → BSI)")
    if args.also_broadcast:
        print(f"[set_clock] 0x276  data = {payload_276.hex(' ').upper()}  (broadcast — risque conflit)")

    if args.dry_run:
        print("[set_clock] --dry-run : rien d'envoyé.")
        return 0

    bus = can.Bus(channel=args.iface, interface=args.bustype)
    try:
        msg = can.Message(arbitration_id=0x39B, data=payload_39b, is_extended_id=False)
        bus.send(msg)
        print("[set_clock] 0x39B envoyé.")

        if args.also_broadcast:
            msg2 = can.Message(arbitration_id=0x276, data=payload_276, is_extended_id=False)
            bus.send(msg2)
            print("[set_clock] 0x276 envoyé.")
    finally:
        bus.shutdown()

    print("[set_clock] OK. Vérifie le combiné/radio — l'heure devrait s'afficher.")
    print("[set_clock] Si rien ne change, ta C2 est probablement < 2005 (RD3 analogique)")
    print("            et la BSI ignore les sets venant d'un device tiers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
