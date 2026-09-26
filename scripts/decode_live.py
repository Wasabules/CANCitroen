#!/usr/bin/env python3
"""Décodeur live des trames CAN-Confort PSA C2 — affichage type tableau de bord.

Décode en temps réel les trames connues (0x0B6, 0x0F6, 0x128, 0x161, 0x220,
0x221, 0x261, 0x276, 0x21F, 0x39B) et affiche un dashboard texte qui se met
à jour. Idéal pour valider que ton sniff fonctionne et que les payloads
correspondent aux attendus PSA.

Usage:
    ./decode_live.py [iface]    # default slcan0

Appuie Ctrl-C pour quitter.
"""
from __future__ import annotations

import argparse
import sys
import time

import can

# état courant à afficher
state = {
    "rpm": None, "speed": None,
    "t_coolant": None, "t_ext": None,
    "odo": None,
    "fuel_pct": None, "fuel_inst": None, "range_km": None,
    "doors": None, "lights": None, "handbrake": None, "belts": None,
    "trip_avg_speed": None, "trip_dist": None, "trip_avg_cons": None,
    "datetime": None,
    "wheel_btn": None, "wheel_scroll": None,
    "last_update": {},
}


def decode_0B6(data: bytes):
    if len(data) < 7:
        return
    state["rpm"] = int.from_bytes(data[0:2], "big") / 8.0  # facteur 1/8 PSA
    state["speed"] = int.from_bytes(data[2:4], "big") / 100.0


def decode_0F6(data: bytes):
    if len(data) < 8:
        return
    contact = bool(data[0] & 0x80)
    state["t_coolant"] = (data[0] & 0x7F) - 39  # °C
    state["odo"] = int.from_bytes(data[1:4], "big")  # km
    state["t_ext"] = data[4] / 2.0 - 39.5
    state["_contact"] = contact


def decode_128(data: bytes):
    if len(data) < 8:
        return
    # bitmap approximatif — varie selon millésime
    state["lights"] = {
        "feux_position": bool(data[0] & 0x01),
        "feux_croisement": bool(data[0] & 0x02),
        "feux_route": bool(data[0] & 0x04),
        "antibrouillard_av": bool(data[0] & 0x08),
        "antibrouillard_ar": bool(data[0] & 0x10),
        "cligno_g": bool(data[0] & 0x20),
        "cligno_d": bool(data[0] & 0x40),
    }
    state["handbrake"] = bool(data[1] & 0x10)
    state["belts"] = {"conducteur": bool(data[2] & 0x01)}


def decode_220(data: bytes):
    if len(data) < 1:
        return
    b = data[0]
    state["doors"] = {
        "AVG": bool(b & 0x01), "AVD": bool(b & 0x02),
        "ARG": bool(b & 0x04), "ARD": bool(b & 0x08),
        "coffre": bool(b & 0x10),
    }


def decode_221(data: bytes):
    if len(data) < 4:
        return
    state["fuel_inst"] = int.from_bytes(data[0:2], "big") / 10.0  # l/100km
    state["range_km"] = int.from_bytes(data[2:4], "big")


def decode_161(data: bytes):
    if len(data) >= 1:
        state["fuel_pct"] = data[0]  # niveau brut, % à valider


def decode_261(data: bytes):
    if len(data) < 6:
        return
    state["trip_avg_speed"] = data[0]  # km/h
    state["trip_dist"] = int.from_bytes(data[1:3], "big") / 10.0  # km
    state["trip_avg_cons"] = int.from_bytes(data[3:5], "big") / 10.0  # l/100


def decode_276(data: bytes):
    if len(data) < 5:
        return
    y = 1872 + data[0]
    state["datetime"] = f"{y:04d}-{data[1]:02d}-{data[2]:02d} {data[3]:02d}:{data[4]:02d}"


def decode_21F(data: bytes):
    if len(data) < 2:
        return
    b0 = data[0]
    state["wheel_scroll"] = data[1]
    btns = []
    if b0 & 0x04 and b0 & 0x08:
        btns.append("MUTE")
    elif b0 & 0x04:
        btns.append("VOL+")
    elif b0 & 0x08:
        btns.append("VOL-")
    if b0 & 0x02:
        btns.append("SRC")
    if b0 & 0x10:
        btns.append("TEL+")
    if b0 & 0x20:
        btns.append("TEL-")
    state["wheel_btn"] = " ".join(btns) if btns else f"(0x{b0:02X})"


DECODERS = {
    0x0B6: decode_0B6,
    0x0F6: decode_0F6,
    0x128: decode_128,
    0x161: decode_161,
    0x220: decode_220,
    0x221: decode_221,
    0x261: decode_261,
    0x276: decode_276,
    0x21F: decode_21F,
}


def render():
    s = state
    lines = [
        "\033[2J\033[H",  # clear screen
        "=" * 60,
        " Citroën C2 — CAN-Confort live decode",
        "=" * 60,
    ]

    def line(label, value, unit=""):
        if value is None:
            v = "—"
        elif isinstance(value, float):
            v = f"{value:.1f}{unit}"
        else:
            v = f"{value}{unit}"
        lines.append(f"  {label:<22} {v}")

    line("RPM",                 s["rpm"], " tr/min")
    line("Vitesse",              s["speed"], " km/h")
    line("T° liquide refroid",   s["t_coolant"], " °C")
    line("T° extérieure",        s["t_ext"], " °C")
    line("Odomètre",             s["odo"], " km")
    line("Niveau carburant",     s["fuel_pct"], " (raw)")
    line("Conso instantanée",    s["fuel_inst"], " l/100")
    line("Autonomie",            s["range_km"], " km")
    line("Trip — vit. moy",      s["trip_avg_speed"], " km/h")
    line("Trip — distance",      s["trip_dist"], " km")
    line("Trip — conso moy",     s["trip_avg_cons"], " l/100")
    line("Date/heure BSI",       s["datetime"])
    line("Frein à main",         "ON" if s["handbrake"] else ("OFF" if s["handbrake"] is not None else None))
    if s["doors"]:
        opened = [k for k, v in s["doors"].items() if v]
        line("Portes ouvertes",   ", ".join(opened) if opened else "aucune")
    if s["lights"]:
        on = [k for k, v in s["lights"].items() if v]
        line("Feux actifs",       ", ".join(on) if on else "—")
    line("Bouton volant",        s["wheel_btn"])
    line("Roller scroll",        s["wheel_scroll"])
    print("\n".join(lines), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("iface", nargs="?", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    args = ap.parse_args()

    bus = can.Bus(channel=args.iface, interface=args.bustype)
    last_render = 0.0
    try:
        while True:
            msg = bus.recv(timeout=0.5)
            if msg is not None:
                dec = DECODERS.get(msg.arbitration_id)
                if dec:
                    try:
                        dec(bytes(msg.data))
                    except Exception as e:
                        print(f"[decode error on {msg.arbitration_id:03X}] {e}",
                              file=sys.stderr)
            now = time.time()
            if now - last_render > 0.25:
                render()
                last_render = now
    except KeyboardInterrupt:
        print("\n[decode_live] arrêté.")
    finally:
        bus.shutdown()


if __name__ == "__main__":
    sys.exit(main())
