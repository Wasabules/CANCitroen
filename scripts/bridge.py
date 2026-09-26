#!/usr/bin/env python3
"""Bridge CAN ↔ HTTP/WebSocket pour Citroën C2 + Atoto A6PF.

Architecture cible :
    [Voiture CAN-Confort 125k] -- CANable --> [PC dev OU tablette Atoto]
                                                |
                                                | aiohttp HTTP+WS
                                                v
                                          dashboard.html (WebView Atoto)

Endpoints :
  GET  /                  → dashboard.html (statique)
  GET  /api/state         → snapshot JSON de l'état décodé courant
  GET  /api/raw?id=21F    → dernière trame brute reçue pour cet ID
  WS   /ws                → push JSON de chaque mise à jour d'état
  POST /api/clock         → règle horloge BSI (body: {"datetime": "..."} ou {} = now)
  POST /api/button        → émet bouton volant (body: {"button": "vol+"})
  POST /api/send          → envoi raw (body: {"id":"21F","data":"04 00 00"})

Usage :
    ./bridge.py                              # iface=slcan0, port=8080, host=0.0.0.0
    ./bridge.py --iface slcan0 --port 8080
    ./bridge.py --replay captures/foo.log    # mode replay, sans vraie voiture

Sécurité : aucune. Lance UNIQUEMENT sur le réseau local de la voiture.
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import json
import logging
import math
import sys
import time
from pathlib import Path
from typing import Optional

import can
from aiohttp import web, WSMsgType

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _canlog import iter_frames  # noqa: E402

log = logging.getLogger("bridge")


def round_half_up(x: float, ndigits: int = 1) -> float:
    """Arrondi au plus proche, .5 vers le haut : identique à Math.round côté
    Kotlin (CanDecoder). round() de Python arrondit au pair (0,25 → 0,2)."""
    f = 10 ** ndigits
    return math.floor(x * f + 0.5) / f

# ─── État partagé ──────────────────────────────────────────────────────────────
STATE = {
    # ── Moteur / dynamique ──
    "rpm": None, "speed": None,
    "t_coolant": None, "t_oil": None, "t_ext": None,
    "odo": None,
    "fuel_raw": None, "fuel_liters_est": None, "fuel_low_warning": None,
    "fuel_inst": None, "range_km": None,
    "oil_level_raw": None, "oil_level_alert": None, "oil_pressure_alert": None,
    "oil_temp_max": None, "coolant_temp_max": None, "coolant_level_alert": None,
    "reverse_gear": None, "wiper_active": None, "wiper_auto": None,
    # ── BSI / état véhicule ──
    "contact": None, "key_position": None, "ignition_mode": None,
    "economy_mode": None, "night_mode": None, "dashboard_brightness": None,
    "black_panel": None,
    # ── Portes / ouvertures ──
    "doors": None,
    # ── Feux / clignos ──
    "lights": None,
    "warnings_on": None,
    # ── Témoins / alertes (warnings combiné) ──
    "warn": None,
    # ── Volant / commodo ──
    "wheel_btn": None, "wheel_btn_raw": None,
    "wheel_scroll": None, "wheel_scroll_delta": None,
    # ── Trip computer ──
    "trip_avg_speed": None, "trip_dist": None, "trip_avg_cons": None,
    "trip_dist_total": None,
    "emf_page": None,    # NONE/GENERAL/TRIP1/TRIP2 (0x167 byte[0] bits 0-2)
    # ── Maintenance ──
    "maint_due": None, "maint_km_remaining": None, "maint_days_remaining": None,
    # ── Frein à main ──
    "handbrake": None,
    # ── Date BSI (rarement émise sur C2 confort) ──
    "datetime_bsi": None,
    # ── Meta ──
    "_last_update_ts": 0.0,
    "_frames_seen": 0,
}
# Brutes par ID (utile debug)
RAW_BY_ID: dict[int, dict] = {}

# Set of WebSocket clients
WS_CLIENTS: set[web.WebSocketResponse] = set()

# Boutons volant — calibré sur C2 (field test 2026-04-26)
# Note: VOL+/VOL- sont INVERSÉS par rapport à la doc PSA générique
BUTTON_BITMAP = {
    "source":    0x02,   # bit 1
    "mode":      0x02,   # alias
    "vol-":      0x04,   # bit 2
    "vol+":      0x08,   # bit 3
    "mute":      0x0C,   # combo VOL+ ∧ VOL-
    "precedant": 0x40,   # bit 6
    "suivant":   0x80,   # bit 7
}

# EMF (écran multifonction) — boutons RD4 émulés via 0x3E5
# Mapping issu de github.com/morcibacsi/PSAWifiDisplayControl
# CanMenuStructs.h — DLC=6
EMF_BUTTONS = {
    "MENU":   "40 00 00 00 00 00",   # byte[0] bit 6
    "MODE":   "00 10 00 00 00 00",   # byte[1] bit 4
    "TRIP":   "00 40 00 00 00 00",   # byte[1] bit 6
    "OK":     "00 00 40 00 00 00",   # byte[2] bit 6
    "ESC":    "00 00 10 00 00 00",   # byte[2] bit 4
    "UP":     "00 00 00 00 00 40",   # byte[5] bit 6
    "DOWN":   "00 00 00 00 00 10",   # byte[5] bit 4
    "LEFT":   "00 00 00 00 00 01",   # byte[5] bit 0
    "RIGHT":  "00 00 00 00 00 04",   # byte[5] bit 2
    "PHONE":  "10 00 00 00 00 00",   # byte[0] bit 4
    "AIRCON": "01 00 00 00 00 00",   # byte[0] bit 0
    "DARK":   "00 00 04 00 00 00",   # byte[2] bit 2
}
EMF_RELEASE_DATA = bytes(6)            # 00 00 00 00 00 00
EMF_RELEASE_PERIOD = 0.065             # 15 Hz

# État global du mode EMF (release loop active ?)
EMF_STATE = {
    "active": False,
    "last_action": None,
    "last_action_ts": 0.0,
    "task": None,
}


# ─── Décodeurs trames ──────────────────────────────────────────────────────────
def decode_0B6(data):
    # PSAVanCanBridge CAN_0B6_2004.h :
    #   bytes 0-1 BE : RPM × 8  →  RPM = int_be / 8
    #   bytes 2-3 BE : Speed × 100  →  km/h = int_be / 100
    #   bytes 4-5 BE : Distance trip combiné (mètres ?)
    #   byte 6 : ConsumptionForCMB (compteur impulsions injecteur)
    #   byte 7 : info_valid_value (4 bits) + info_valid (bit 7)
    if len(data) >= 8:
        STATE["rpm"] = round_half_up(int.from_bytes(data[0:2], "big") / 8.0, 1)
        STATE["speed"] = round_half_up(int.from_bytes(data[2:4], "big") / 100.0, 1)
        STATE["trip_dist_cmb_raw"] = int.from_bytes(data[4:6], "big")  # m / unit?
        STATE["cons_cmb_raw"] = data[6]


def decode_0F6(data):
    # Calibré sur C2 via PSAVanCanBridge struct + Lexia ground truth (2026-05-09).
    # Source : github.com/morcibacsi/PSAVanCanBridge AEE2004 CAN_0F6_2004.h
    # Format DLC=8 :
    #   byte[0] : ignition/key/factory_mode bits
    #   byte[1] : CoolantTemperature  (T° moteur)
    #   bytes[2..4] BE : Mileage (odomètre × 10)
    #   byte[5] : Field6 (réservé)
    #   byte[6] : ExternalTemperature (T° ext)
    #   byte[7] : Lights (bit 0=cligno_g, bit 1=cligno_d, bit 6=wiper, bit 7=marche AR)
    if len(data) < 8:
        return
    # byte[0] : key position (bits 3-4) + flags
    key_pos = (data[0] >> 3) & 0x03
    STATE["contact"] = key_pos != 0  # 0=stop, 1=contact, 2=starter, 3=free
    STATE["key_position"] = ["STOP", "CONTACT", "STARTER", "FREE"][key_pos]
    # economy_mode vient de 0x036 uniquement (comme CanDecoder.kt) : le lire
    # aussi ici (bit 6, jamais confirmé) le faisait osciller selon la trame.
    # Sentinelles 0xFF / 0xFFFFFF = capteur pas encore lu (BSI au réveil) → None,
    # sinon on affiche 202 °C ou 1 677 721 km.
    # T° moteur : byte[1] - 53 (calé sur Lexia 92°C ↔ byte=0x91=145)
    STATE["t_coolant"] = None if data[1] == 0xFF else data[1] - 53
    # Odomètre : 24 bits BE / 10 (struct PSAVanCanBridge). ⚠ Le point Lexia noté
    # ici auparavant était incohérent (0x10D85F = 1 103 967 → 110 396,7 km, pas
    # 110 506 km) : facteur à revérifier contre le compteur à la prochaine session.
    raw_odo = int.from_bytes(data[2:5], "big")
    STATE["odo"] = None if raw_odo == 0xFFFFFF else round_half_up(raw_odo / 10.0, 1)
    # T° extérieure : byte[6] - 102 (calé sur 23°C ↔ byte=0x7D=125)
    STATE["t_ext"] = None if data[6] == 0xFF else data[6] - 102
    # Bits divers byte[7]
    b7 = data[7]
    STATE["reverse_gear"] = bool(b7 & 0x80)
    STATE["wiper_active"] = bool(b7 & 0x40)
    # cligno (redondance avec 0x128 byte[4] bits 1,2 — BSI les émet ici aussi)
    STATE["cligno_g_alt"] = bool(b7 & 0x01)
    STATE["cligno_d_alt"] = bool(b7 & 0x02)


def decode_128(data):
    # PSAVanCanBridge CAN_128_2004.h — combiné lights + indicators COMPLET.
    if len(data) < 8:
        return
    b0, b1, b2, b3, b4, b5, b6, b7 = data[0:8]

    # byte[0] : Indicator1 — alertes principales
    STATE["handbrake"] = bool(b0 & 0x20)              # bit 5
    STATE["warn"] = STATE.get("warn") or {}
    STATE["warn"].update({
        "service_blink":          bool(b0 & 0x01),
        "passenger_belt":         bool(b0 & 0x02),
        "diesel_preheat":         bool(b0 & 0x04),
        "fuel_circuit_neutral":   bool(b0 & 0x08),
        "fuel_low":               bool(b0 & 0x10),
        "driver_belt":            bool(b0 & 0x40),
        "passenger_airbag_off":   bool(b0 & 0x80),
        # byte[1] : Indicator2 — ABS, portes ouvertes
        "rear_belt":              bool(b1 & 0x01),
        "abs_active":              bool(b1 & 0x02),
        "front_passenger_protect": bool(b1 & 0x04),
        "door_open_above_10":     bool(b1 & 0x08),
        "door_open_below_10":     bool(b1 & 0x10),
        "stop_blink":             bool(b1 & 0x20),
        "stop":                    bool(b1 & 0x40),
        "service_exclamation":    bool(b1 & 0x80),
        # byte[2] : Indicator3 — ESP, warnings
        "ready_lamp":              bool(b2 & 0x01),
        "warning_active":          bool(b2 & 0x02),
        "esp_in_progress":         bool(b2 & 0x08),
        "esp_inactivated":         bool(b2 & 0x10),
        "child_security":          bool(b2 & 0x20),
    })
    STATE["fuel_low_warning"] = bool(b0 & 0x10)
    STATE["warnings_on"] = bool(b2 & 0x02)             # byte[2] bit 1

    # byte[4] : Indicator5 — feux (calibré sur C2 ✓ confirmé via PSA-RE)
    STATE["lights"] = {
        "drl":               bool(b4 & 0x01),   # bit 0
        "cligno_g":          bool(b4 & 0x02),   # bit 1 = left_turn
        "cligno_d":          bool(b4 & 0x04),   # bit 2 = right_turn
        "antibrouillard_ar": bool(b4 & 0x08),   # bit 3 = rear_foglight
        "antibrouillard_av": bool(b4 & 0x10),   # bit 4 = front_foglight
        "feux_route":        bool(b4 & 0x20),   # bit 5 = high_beam
        "feux_croisement":   bool(b4 & 0x40),   # bit 6 = low_beam
        "feux_position":     bool(b4 & 0x80),   # bit 7 = parking_light
    }

    # byte[6] / byte[7] : gear box (utile sur boîte auto)
    gear_codes = ["P","R","N","D","6","5","4","3","2","1","-","-","-","-","-","?"]
    g_drive = (b6 >> 1) & 0x07
    g_cmb = (b6 >> 4) & 0x0F
    STATE["gear_drive"] = g_drive
    STATE["gear_cmb"] = gear_codes[g_cmb] if g_cmb < 16 else "?"


def decode_220(data):
    # PSAVanCanBridge CAN_220_2004.h — DLC=2.
    # byte[0] = ouvrants, byte[1] = config véhicule
    if len(data) >= 1:
        b = data[0]
        STATE["doors"] = {
            "AVG":         bool(b & 0x80),   # bit 7 — conducteur (front_left)
            "AVD":         bool(b & 0x40),   # bit 6 — passager (front_right)
            "ARG":         bool(b & 0x20),   # bit 5 — n/a sur C2 3p
            "ARD":         bool(b & 0x10),   # bit 4 — n/a sur C2 3p
            "coffre":      bool(b & 0x08),   # bit 3 — hayon (trunk)
            "capot":       bool(b & 0x04),   # bit 2 — hood
            "vitre_ar":    bool(b & 0x02),   # bit 1 — rear window (lunette AR ouvrante)
            "trappe_carb": bool(b & 0x01),   # bit 0 — fuel flap
        }


def decode_221(data):
    # Calibré sur C2 — confirmé autonomie 440 km à bytes[3..4] BE (field test 2026-04-26).
    # Format observé : 00 FF FF 01 B8 FF FF
    if len(data) >= 5:
        # bytes[1..2] = conso instantanée ×10 (ou 0xFFFF = "non calculé")
        raw_cons = int.from_bytes(data[1:3], "big")
        STATE["fuel_inst"] = None if raw_cons == 0xFFFF else round_half_up(raw_cons / 10.0, 1)
        # bytes[3..4] = autonomie en km
        raw_range = int.from_bytes(data[3:5], "big")
        STATE["range_km"] = None if raw_range == 0xFFFF else raw_range


def decode_161(data):
    # Calibré via PSAVanCanBridge struct CAN_161_2004.h + retour utilisateur (2026-05-09).
    # Format DLC=7 :
    #   byte[0] : Field1 (bit 7 = oil_level_restart)
    #   byte[1] : Unused
    #   byte[2] : EngineOilTemperature
    #   byte[3] : FuelLevel  → POURCENTAGE direct (0-100)
    #   bytes 4..5 : Unused
    #   byte[6] : EngineOilLevel → POURCENTAGE direct (0-100)
    #
    # Volumes véhicule (à adapter selon modèle) :
    #   Réservoir carburant Citroën C2 = 40 L
    #   Capacité huile moteur TU1JP (1.1L 60ch) = 3.0 L (vidange + filtre)
    if len(data) < 7:
        return
    FUEL_TANK_L = 41.0   # C2 : 41 L (CanIds.FUEL_TANK_LITERS côté app)
    OIL_CAPACITY_L = 3.0

    # T° huile : byte[2] - 64 (calé sur Lexia 71°C ↔ byte=0x86=134 → 70°C ≈)
    # 0x00 = pas encore lu, 0xFF = sentinelle capteur
    STATE["t_oil"] = None if data[2] in (0x00, 0xFF) else data[2] - 64

    # Carburant : byte[3] = pourcentage direct
    fuel_pct = data[3]
    STATE["fuel_pct"] = fuel_pct
    STATE["fuel_raw"] = fuel_pct  # alias rétro-compat
    STATE["fuel_liters_est"] = round_half_up(fuel_pct / 100.0 * FUEL_TANK_L, 1) if fuel_pct > 0 else None

    # Niveau huile : byte[6] = pourcentage direct
    oil_pct = data[6]
    STATE["oil_level_pct"] = oil_pct
    STATE["oil_level_raw"] = oil_pct  # alias rétro-compat
    STATE["oil_level_liters_est"] = round_half_up(oil_pct / 100.0 * OIL_CAPACITY_L, 2) if oil_pct > 0 else None


def decode_261(data):
    # Calibré sur C2 — format observé 27 27 0F 00 4C 3C 4C (field test 2026-04-26).
    # NB : la trip distance "884 km" annoncée par l'utilisateur n'a pas été trouvée
    # explicitement, mais le format ci-dessous est cohérent avec un trip slot 1.
    if len(data) >= 5:
        STATE["trip_avg_speed"] = data[0]                               # km/h
        STATE["trip_dist"]      = round_half_up(int.from_bytes(data[2:4], "big") / 10.0, 1)
        STATE["trip_avg_cons"]  = round_half_up(data[4] / 10.0, 1)              # l/100


def decode_276(data):
    if len(data) >= 5:
        y = 1872 + data[0]
        STATE["datetime_bsi"] = (
            f"{y:04d}-{data[1]:02d}-{data[2]:02d} {data[3]:02d}:{data[4]:02d}"
        )


def decode_036(data):
    # PSAVanCanBridge CAN_036_2004.h — état BSI.
    # byte 0,1 = mémoire profils
    # byte 2 = LoadShedding (bit 7 = economy_mode_active)
    # byte 3 = Brightness (bits 0-3 = dashboard_brightness, bit 4=black_panel, bit 5=night_mode)
    # byte 4 = Ignition (bits 0-2 = ignition_mode 0=STANDBY, 1=NORMAL, 2=STANDBY_SOON, 3=WAKE_UP, 4=COM_OFF)
    if len(data) < 5:
        return
    STATE["economy_mode"] = bool(data[2] & 0x80)
    STATE["dashboard_brightness"] = data[3] & 0x0F
    STATE["black_panel"] = bool(data[3] & 0x10)
    STATE["night_mode"] = bool(data[3] & 0x20)
    ig_mode = data[4] & 0x07
    ig_names = ["STANDBY", "NORMAL", "STANDBY_SOON", "WAKE_UP", "COM_OFF",
                "?", "?", "?"]
    STATE["ignition_mode"] = ig_names[ig_mode]


def decode_168(data):
    # PSAVanCanBridge CAN_168_2004.h — alertes critiques moteur.
    if len(data) < 3:
        return
    b0, b1, b2 = data[0:3]
    STATE["warn"] = STATE.get("warn") or {}
    STATE["warn"].update({
        "dsg_fault":           bool(b0 & 0x01),
        "auto_gearbox_alert":  bool(b0 & 0x02),
        "brake_fluid_alert":   bool(b0 & 0x04),
        "oil_pressure_alert":  bool(b0 & 0x08),
        "oil_level_alert":     bool(b0 & 0x10),
        "coolant_level_alert": bool(b0 & 0x20),
        "oil_temp_max":        bool(b0 & 0x40),
        "coolant_temp_max":    bool(b0 & 0x80),
        "max_rpm_2":           bool(b1 & 0x01),
        "max_rpm_1":           bool(b1 & 0x04),
        "auto_wiping":         bool(b1 & 0x08),
        "fap_clogged":         bool(b1 & 0x10),
        "diesel_additive":     bool(b1 & 0x20),
        "tyre_punctured":      bool(b1 & 0x40),
        "tyre_pressure_low":   bool(b1 & 0x80),
    })
    STATE["oil_pressure_alert"] = bool(b0 & 0x08)
    STATE["oil_level_alert"] = bool(b0 & 0x10)
    STATE["coolant_level_alert"] = bool(b0 & 0x20)
    STATE["oil_temp_max"] = bool(b0 & 0x40)
    STATE["coolant_temp_max"] = bool(b0 & 0x80)
    STATE["wiper_auto"] = bool(b1 & 0x08)


def decode_167(data):
    # PSAVanCanBridge CAN_167_2004.h — état EMF.
    # byte[0] bits 0-2 : trip_data_on_odometer (0=NONE, 1=GENERAL, 2=TRIP1, 4=TRIP2, 7=NOT_MGD)
    # bytes[2..3] BE : TotalDistanceTraveled (16-bit)
    if len(data) < 4:
        return
    page_code = data[0] & 0x07
    pages = {0: "NONE", 1: "GENERAL", 2: "TRIP1", 4: "TRIP2", 7: "NOT_MGD"}
    STATE["emf_page"] = pages.get(page_code, f"UNKNOWN({page_code})")
    STATE["trip_dist_total"] = int.from_bytes(data[2:4], "big")


def decode_3A7(data):
    # PSAVanCanBridge CAN_3A7_2004.h — info maintenance.
    # byte[0] bit 7 : maintenance_due
    # bytes[3..4] BE : km avant entretien
    # bytes[5..6] BE : jours avant entretien
    if len(data) < 7:
        return
    STATE["maint_due"] = bool(data[0] & 0x80)
    STATE["maint_km_remaining"] = int.from_bytes(data[3:5], "big")
    STATE["maint_days_remaining"] = int.from_bytes(data[5:7], "big")


def decode_21F(data):
    # Calibré sur C2 (field test 2026-04-26).
    if len(data) < 1:
        return
    b0 = data[0]
    STATE["wheel_btn_raw"] = b0
    new_scroll = data[1] if len(data) > 1 else 0
    # Calcul du delta scroll (compteur 8 bits signé)
    prev = STATE.get("wheel_scroll")
    if prev is None:
        delta = 0
    else:
        delta = (new_scroll - prev) % 256
        if delta > 127:
            delta -= 256
    STATE["wheel_scroll"] = new_scroll
    STATE["wheel_scroll_delta"] = delta
    btns = []
    if b0 & 0x04 and b0 & 0x08:
        btns.append("MUTE")
    elif b0 & 0x08:
        btns.append("VOL+")
    elif b0 & 0x04:
        btns.append("VOL-")
    if b0 & 0x02:
        btns.append("SRC")
    if b0 & 0x40:
        btns.append("◀ PRECEDANT")
    if b0 & 0x80:
        btns.append("SUIVANT ▶")
    STATE["wheel_btn"] = " ".join(btns) if btns else None


DECODERS = {
    0x036: decode_036,
    0x0B6: decode_0B6,
    0x0F6: decode_0F6,
    0x128: decode_128,
    0x161: decode_161,
    0x167: decode_167,
    0x168: decode_168,
    0x21F: decode_21F,
    0x220: decode_220,
    0x221: decode_221,
    0x261: decode_261,
    0x276: decode_276,
    0x3A7: decode_3A7,
}


# ─── Lecture CAN ───────────────────────────────────────────────────────────────
async def can_reader(bus, queue: asyncio.Queue):
    """Pompe les messages du bus vers la queue (thread-safe via run_in_executor)."""
    loop = asyncio.get_running_loop()
    while True:
        msg = await loop.run_in_executor(None, bus.recv, 0.5)
        if msg is None:
            continue
        await queue.put(msg)


async def replay_reader(path: str, queue: asyncio.Queue):
    """Mode replay : lit un fichier candump à vitesse réelle."""
    log.info(f"Replay: {path}")
    frames = list(iter_frames(path))
    if not frames:
        log.warning("Capture vide.")
        return
    t0_real = time.time()
    t0_log = frames[0].ts
    for f in frames:
        target = t0_real + (f.ts - t0_log)
        delay = target - time.time()
        if delay > 0:
            await asyncio.sleep(delay)
        # Construire un can.Message équivalent
        msg = can.Message(
            arbitration_id=f.can_id,
            data=f.data,
            is_extended_id=f.extended,
            timestamp=f.ts,
        )
        await queue.put(msg)
    log.info("Replay terminé.")


async def decoder(queue: asyncio.Queue):
    """Décode les messages, met à jour STATE, broadcast WS."""
    while True:
        msg = await queue.get()
        STATE["_frames_seen"] += 1
        STATE["_last_update_ts"] = time.time()
        RAW_BY_ID[msg.arbitration_id] = {
            "id": f"{msg.arbitration_id:03X}",
            "data": bytes(msg.data).hex(" ").upper(),
            "ts": msg.timestamp,
        }
        dec = DECODERS.get(msg.arbitration_id)
        if dec:
            try:
                dec(bytes(msg.data))
            except Exception as e:
                log.error(f"decode {msg.arbitration_id:03X}: {e}")
            await broadcast_state()


async def broadcast_state():
    if not WS_CLIENTS:
        return
    payload = json.dumps({"type": "state", "state": STATE})
    dead = []
    for ws in WS_CLIENTS:
        try:
            await ws.send_str(payload)
        except ConnectionResetError:
            dead.append(ws)
    for ws in dead:
        WS_CLIENTS.discard(ws)


# ─── Périodique : pousse un snapshot toutes les 250 ms même si rien ne change ──
async def periodic_broadcast():
    while True:
        await asyncio.sleep(0.25)
        await broadcast_state()


# ─── Émission CAN ──────────────────────────────────────────────────────────────
def send_clock(bus, when: Optional[dt.datetime]):
    when = when or dt.datetime.now().replace(second=0, microsecond=0)
    payload = bytes([when.year - 1872, when.month, when.day, when.hour, when.minute])
    bus.send(can.Message(arbitration_id=0x39B, data=payload, is_extended_id=False))
    log.info(f"clock 0x39B sent: {when} → {payload.hex(' ')}")
    return when


def send_button(bus, button: str, hold_ms: int = 50):
    if button not in BUTTON_BITMAP:
        raise ValueError(f"unknown button: {button}")
    b0 = BUTTON_BITMAP[button]
    pressed = bytes([b0, 0x00, 0x00])
    released = bytes([0x00, 0x00, 0x00])
    end = time.time() + hold_ms / 1000.0
    while time.time() < end:
        bus.send(can.Message(arbitration_id=0x21F, data=pressed, is_extended_id=False))
        time.sleep(0.05)
    bus.send(can.Message(arbitration_id=0x21F, data=released, is_extended_id=False))
    log.info(f"button {button} sent (hold={hold_ms}ms)")


def send_raw(bus, can_id: int, data: bytes, extended: bool = False):
    bus.send(can.Message(arbitration_id=can_id, data=data, is_extended_id=extended))
    log.info(f"raw sent: {can_id:X} {data.hex(' ')}")


# ─── Mode EMF (release continu + pulse boutons) ────────────────────────────────

async def emf_release_loop(bus):
    """Émet 0x3E5 = 00*6 à 15 Hz tant que EMF_STATE['active'] est vrai.

    Reproduit le comportement du firmware PSAWifiDisplayControl :
    fond permanent "no button pressed" qui permet à l'EMF de détecter
    un edge 0→1 quand on injecte une trame avec le bit du bouton.
    """
    while EMF_STATE["active"]:
        try:
            bus.send(can.Message(
                arbitration_id=0x3E5, data=EMF_RELEASE_DATA, is_extended_id=False
            ))
        except Exception as e:
            log.error(f"emf release send error: {e}")
        await asyncio.sleep(EMF_RELEASE_PERIOD)


def emf_send_button(bus, name: str) -> bool:
    """Émet une trame 0x3E5 avec le bit du bouton — UN SEUL pulse."""
    data_hex = EMF_BUTTONS[name].replace(" ", "")
    data = bytes.fromhex(data_hex)
    bus.send(can.Message(arbitration_id=0x3E5, data=data, is_extended_id=False))
    EMF_STATE["last_action"] = name
    EMF_STATE["last_action_ts"] = time.time()
    log.info(f"emf button: {name}  data={data.hex(' ').upper()}")
    return True


# ─── Routes HTTP ───────────────────────────────────────────────────────────────
async def handle_root(request):
    static = Path(__file__).resolve().parent.parent / "web" / "dashboard.html"
    if static.exists():
        return web.FileResponse(static)
    return web.Response(text="dashboard.html absent (web/dashboard.html)",
                        status=404)


async def handle_state(request):
    return web.json_response(STATE)


async def handle_raw(request):
    cid = request.query.get("id")
    if cid:
        try:
            return web.json_response(RAW_BY_ID.get(int(cid, 16), {}))
        except ValueError:
            return web.json_response({"error": "bad id"}, status=400)
    # tout
    return web.json_response({f"{k:03X}": v for k, v in RAW_BY_ID.items()})


async def handle_post_clock(request):
    bus = request.app["bus"]
    if bus is None:
        return web.json_response({"error": "no bus (replay mode?)"}, status=400)
    try:
        body = await request.json() if request.body_exists else {}
    except json.JSONDecodeError:
        body = {}
    when = None
    if body.get("datetime"):
        when = dt.datetime.strptime(body["datetime"], "%Y-%m-%d %H:%M")
    sent = send_clock(bus, when)
    return web.json_response({"ok": True, "datetime": sent.isoformat()})


async def handle_post_button(request):
    bus = request.app["bus"]
    if bus is None:
        return web.json_response({"error": "no bus (replay mode?)"}, status=400)
    body = await request.json()
    btn = body.get("button")
    hold = int(body.get("hold_ms", 50))
    try:
        send_button(bus, btn, hold)
    except ValueError as e:
        return web.json_response({"error": str(e)}, status=400)
    return web.json_response({"ok": True})


async def handle_post_send(request):
    bus = request.app["bus"]
    if bus is None:
        return web.json_response({"error": "no bus"}, status=400)
    body = await request.json()
    cid = int(body["id"], 16)
    data = bytes.fromhex(body["data"].replace(" ", ""))
    ext = bool(body.get("extended", False))
    send_raw(bus, cid, data, ext)
    return web.json_response({"ok": True})


async def handle_emf_status(request):
    """Lecture de l'état du mode EMF."""
    return web.json_response({
        "active": EMF_STATE["active"],
        "last_action": EMF_STATE["last_action"],
        "last_action_ts": EMF_STATE["last_action_ts"],
        "available_buttons": list(EMF_BUTTONS.keys()),
    })


async def handle_emf_toggle(request):
    """Active/désactive le mode EMF.

    body : {"active": true|false}  ou {} pour toggle.
    Quand actif : démarre la release-loop 0x3E5 = 00*6 @ 15 Hz.
    """
    bus = request.app["bus"]
    if bus is None:
        return web.json_response({"error": "no bus (replay mode?)"}, status=400)
    try:
        body = await request.json() if request.body_exists else {}
    except json.JSONDecodeError:
        body = {}

    if "active" in body:
        desired = bool(body["active"])
    else:
        desired = not EMF_STATE["active"]

    if desired and not EMF_STATE["active"]:
        EMF_STATE["active"] = True
        EMF_STATE["task"] = asyncio.create_task(emf_release_loop(bus))
        log.info("EMF mode ENABLED — release loop started")
    elif not desired and EMF_STATE["active"]:
        EMF_STATE["active"] = False
        # Le loop sortira naturellement à la prochaine itération.
        # On émet un release final pour être sûr.
        try:
            bus.send(can.Message(arbitration_id=0x3E5,
                                 data=EMF_RELEASE_DATA, is_extended_id=False))
        except Exception:
            pass
        log.info("EMF mode DISABLED")

    return web.json_response({"active": EMF_STATE["active"]})


async def handle_emf_button(request):
    """Émet une trame 0x3E5 avec le bouton donné (1 pulse)."""
    bus = request.app["bus"]
    if bus is None:
        return web.json_response({"error": "no bus"}, status=400)
    body = await request.json()
    name = (body.get("button") or "").upper()
    if name not in EMF_BUTTONS:
        return web.json_response(
            {"error": f"unknown button '{name}'", "available": list(EMF_BUTTONS)},
            status=400)
    if not EMF_STATE["active"]:
        return web.json_response(
            {"error": "EMF mode not active. POST /api/emf/toggle d'abord."},
            status=409)
    emf_send_button(bus, name)
    return web.json_response({"ok": True, "button": name,
                              "data": EMF_BUTTONS[name]})


async def handle_ws(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    WS_CLIENTS.add(ws)
    log.info(f"WS client connected ({len(WS_CLIENTS)} total)")
    try:
        # snapshot initial
        await ws.send_str(json.dumps({"type": "state", "state": STATE}))
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                log.error(f"WS error: {ws.exception()}")
            # on n'attend pas de messages entrants côté WS pour l'instant
    finally:
        WS_CLIENTS.discard(ws)
        log.info(f"WS client disconnected ({len(WS_CLIENTS)} left)")
    return ws


# ─── Main ──────────────────────────────────────────────────────────────────────
async def main_async(args):
    queue: asyncio.Queue = asyncio.Queue()

    if args.replay:
        bus = None
        reader_task = asyncio.create_task(replay_reader(args.replay, queue))
    else:
        bus = can.Bus(channel=args.iface, interface=args.bustype)
        reader_task = asyncio.create_task(can_reader(bus, queue))

    decoder_task = asyncio.create_task(decoder(queue))
    periodic_task = asyncio.create_task(periodic_broadcast())

    app = web.Application()
    app["bus"] = bus
    app.router.add_get("/", handle_root)
    app.router.add_get("/api/state", handle_state)
    app.router.add_get("/api/raw", handle_raw)
    app.router.add_post("/api/clock", handle_post_clock)
    app.router.add_post("/api/button", handle_post_button)
    app.router.add_post("/api/send", handle_post_send)
    app.router.add_get("/api/emf", handle_emf_status)
    app.router.add_post("/api/emf/toggle", handle_emf_toggle)
    app.router.add_post("/api/emf/button", handle_emf_button)
    app.router.add_get("/ws", handle_ws)
    # static autres
    web_dir = Path(__file__).resolve().parent.parent / "web"
    if web_dir.exists():
        app.router.add_static("/static/", web_dir)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    await site.start()
    log.info(f"Bridge en écoute sur http://{args.host}:{args.port}")
    log.info(f"Dashboard : http://localhost:{args.port}/  — WS : ws://localhost:{args.port}/ws")

    try:
        # bloque jusqu'à Ctrl-C
        while True:
            await asyncio.sleep(3600)
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        for t in (reader_task, decoder_task, periodic_task):
            t.cancel()
        await runner.cleanup()
        if bus:
            bus.shutdown()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--iface", default="slcan0")
    ap.add_argument("--bustype", default="socketcan")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--replay", help="rejouer une capture .log au lieu d'écouter le bus")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )

    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
