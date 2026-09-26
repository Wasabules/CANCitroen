#!/usr/bin/env python3
"""Émet une trame "commande au volant" 0x21F pour piloter un autoradio.

Cas d'usage : autoradio aftermarket branché sur le CAN-Confort qui écoute 0x21F
et réagit aux pressions virtuelles (utile aussi pour tester si TON autoradio
réagit comme prévu en absence de signal du HDC d'origine).

Usage:
    ./send_button.py vol+
    ./send_button.py vol-
    ./send_button.py mute
    ./send_button.py source
    ./send_button.py raw 0x10           # bitmap arbitraire b0
    ./send_button.py vol+ --hold 0.5    # maintient 500 ms (utile pour vol auto-repeat)
    ./send_button.py vol+ --pulses 3    # envoie 3 pulses brefs

Pré-requis : interface UP, voiture/autoradio en marche.
"""
from __future__ import annotations

import argparse
import sys
import time

import can

BUTTONS = {
    # Calibré sur Citroën C2 — VOL+ / VOL- sont inversés par rapport à doc PSA générique.
    "source":    0x02,
    "mode":      0x02,
    "vol-":      0x04,
    "vol+":      0x08,
    "mute":      0x0C,   # combo VOL+ ∧ VOL-
    "precedant": 0x40,
    "suivant":   0x80,
}


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("button", help=f"nom de touche ({', '.join(BUTTONS)}) ou 'raw'")
    ap.add_argument("raw_value", nargs="?",
                    help="si button=raw : valeur b0 en hex (ex 0x10)")
    ap.add_argument("--iface", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    ap.add_argument("--hold", type=float, default=0.05,
                    help="durée de l'appui en secondes (défaut 0.05 = pulse)")
    ap.add_argument("--pulses", type=int, default=1,
                    help="nombre de pulses (défaut 1)")
    ap.add_argument("--rate", type=float, default=0.05,
                    help="période de ré-émission pendant un hold (s, défaut 50ms)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.button == "raw":
        if not args.raw_value:
            ap.error("button=raw exige une valeur hex")
        b0 = int(args.raw_value, 16)
    else:
        if args.button not in BUTTONS:
            ap.error(f"touche inconnue: {args.button}")
        b0 = BUTTONS[args.button]

    pressed = bytes([b0, 0x00, 0x00])
    released = bytes([0x00, 0x00, 0x00])

    print(f"[send_button] {args.button} → 0x21F data={pressed.hex(' ').upper()}  "
          f"hold={args.hold*1000:.0f}ms  pulses={args.pulses}")

    if args.dry_run:
        print("[send_button] --dry-run : rien d'envoyé.")
        return 0

    bus = can.Bus(channel=args.iface, interface=args.bustype)
    try:
        for i in range(args.pulses):
            t_end = time.time() + args.hold
            while time.time() < t_end:
                bus.send(can.Message(arbitration_id=0x21F, data=pressed,
                                     is_extended_id=False))
                time.sleep(args.rate)
            # release frame (un seul pulse de release)
            bus.send(can.Message(arbitration_id=0x21F, data=released,
                                 is_extended_id=False))
            if i < args.pulses - 1:
                time.sleep(0.1)
        print("[send_button] OK")
    finally:
        bus.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
