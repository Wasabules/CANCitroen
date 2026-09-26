#!/usr/bin/env python3
"""Menu interactif de tests émulation RD4 / EMF — Citroën C2.

Tu lances, tu choisis dans le menu, tu observes l'écran central, tu coches.
Les résultats sont logués dans captures/rd4_tests_<timestamp>.log pour ne rien
oublier entre les sessions.

Pré-requis :
  - bridge.py tournant sur :8080 (./scripts/bridge.py)
  - voiture contact ON, idéalement moteur tournant

Usage :
    ./scripts/rd4_test_menu.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import requests

# ──────────────────────────────────────────────────────────────────────────────
URL = "http://127.0.0.1:8080/api/send"
LOG_DIR = Path(__file__).resolve().parent.parent / "captures"
LOG_FILE = LOG_DIR / f"rd4_tests_{time.strftime('%Y%m%d_%H%M%S')}.log"
LOG_DIR.mkdir(exist_ok=True)


def send(id_hex: str, data: str) -> bool:
    """Émet une trame via le bridge HTTP."""
    try:
        r = requests.post(URL, json={"id": id_hex, "data": data}, timeout=2)
        return r.json().get("ok", False)
    except Exception as e:
        print(f"    ⚠️  envoi raté: {e}")
        return False


def burst(id_hex: str, data: str, duration_s: float = 2.0, period_s: float = 0.1):
    """Émission soutenue pendant duration_s."""
    end = time.time() + duration_s
    while time.time() < end:
        send(id_hex, data)
        time.sleep(period_s)


def burst_multi(frames: list[tuple[str, str]], duration_s: float, period_s: float = 0.1):
    """Émet plusieurs frames simultanément (chacune répétée à period_s)."""
    end = time.time() + duration_s
    while time.time() < end:
        for id_hex, data in frames:
            send(id_hex, data)
        time.sleep(period_s)


def log_result(test_name: str, result: str, notes: str = ""):
    with LOG_FILE.open("a") as f:
        ts = time.strftime("%Y-%m-%d %H:%M:%S")
        f.write(f"[{ts}] {test_name}  →  {result}  {notes}\n")


def ask_observation(label: str) -> str:
    """Demande à l'user ce qu'il a observé. Retourne 'y'/'n'/'r' (retest) / 'q'."""
    while True:
        ans = input(f"    {label} [y=oui réaction / n=rien / r=retest / q=quit] : ").strip().lower()
        if ans in ("y", "n", "r", "q", ""):
            return ans or "n"


def header(text: str):
    print("\n" + "═" * 70)
    print(f"  {text}")
    print("═" * 70)


def run_test(name: str, frames_or_action, duration: float = 2.5, idle_first: bool = True):
    """Lance un test, demande feedback, log. Retourne True si user veut quitter."""
    print(f"\n▶ {name}")
    if idle_first:
        print(f"  [présence radio idle 1s]")
        burst("122", "00 00 00 00 00 02 00 00", 1.0)
    print(f"  [émission {duration}s — 👀 ÉCRAN CENTRAL]")
    if callable(frames_or_action):
        frames_or_action()
    elif isinstance(frames_or_action, list):
        burst_multi(frames_or_action, duration)
    else:
        # tuple (id, data)
        id_hex, data = frames_or_action
        burst(id_hex, data, duration)
    # release
    burst("122", "00 00 00 00 00 02 00 00", 0.3)
    ans = ask_observation("Réaction visuelle ?")
    desc = ""
    if ans == "y":
        desc = input("    📝 Décris ce que tu as vu : ").strip()
    log_result(name, ans, desc)
    if ans == "r":
        return run_test(name, frames_or_action, duration, idle_first)
    return ans == "q"


# ──────────────────────────────────────────────────────────────────────────────
# Catégories de tests
# ──────────────────────────────────────────────────────────────────────────────

def cat_122_byte0_sweep():
    """Sweep tous les bits de byte[0] de 0x122."""
    header("0x122 byte[0] — SWEEP des 8 bits")
    for bit in range(8):
        v = 1 << bit
        name = f"0x122 byte[0]=0x{v:02X} (bit {bit})"
        if run_test(name, ("122", f"{v:02X} 00 00 00 00 02 00 00")):
            return True
    return False


def cat_122_byte1_sweep():
    """Sweep tous les bits de byte[1] de 0x122 (peut-être une autre couche de boutons)."""
    header("0x122 byte[1] — SWEEP des 8 bits (byte[0]=0)")
    for bit in range(8):
        v = 1 << bit
        name = f"0x122 byte[1]=0x{v:02X} (bit {bit})"
        if run_test(name, ("122", f"00 {v:02X} 00 00 00 02 00 00")):
            return True
    return False


def cat_122_combinations():
    """MENU + autres bits en combinaison (parfois besoin d'un OK + flèche)."""
    header("0x122 — combinaisons MENU + autres bits")
    combos = [
        ("MENU+OK (0x80|0x40)",        "C0 00 00 00 00 02 00 00"),
        ("MENU+ESC (0x80|0x20)",       "A0 00 00 00 00 02 00 00"),
        ("MENU+MODE (0x80|0x10)",      "90 00 00 00 00 02 00 00"),
        ("OK+UP (0x40|0x10)",          "50 00 00 00 00 02 00 00"),
        ("OK seul long (0x40)",        "40 00 00 00 00 02 00 00"),
        ("Tous bits actifs (0xFF)",    "FF 00 00 00 00 02 00 00"),
    ]
    for name, data in combos:
        if run_test(f"0x122 {name}", ("122", data), duration=3.0):
            return True
    return False


def cat_1A8_sweep_b0():
    """0x1A8 byte[0] sweep — autre ID radio possiblement écouté par EMF."""
    header("0x1A8 byte[0] — SWEEP des 8 bits")
    # 0x1A8 observé : 00 FF FF 00 00 05 C8 15
    for bit in range(8):
        v = 1 << bit
        name = f"0x1A8 byte[0]=0x{v:02X} (bit {bit})"
        if run_test(name, ("1A8", f"{v:02X} FF FF 00 00 05 C8 15")):
            return True
    return False


def cat_167_button_action():
    """0x167 BUTTON_ACTION — tous les codes documentés et leurs combos."""
    header("0x167 BUTTON_ACTION — codes documentés")
    actions = [
        ("OUI/OK (b6=0x10)",            "01 06 FF FF 7F FF 10 00"),
        ("NON (b6=0x20)",               "01 06 FF FF 7F FF 20 00"),
        ("ESC (b6=0x30)",               "01 06 FF FF 7F FF 30 00"),
        ("RETOUR_VALEUR + 0  (b6=0x40 b7=0)",     "01 06 FF FF 7F FF 40 00"),
        ("RETOUR_VALEUR + 12 (b7=0x0C)", "01 06 FF FF 7F FF 40 0C"),
        ("RETOUR_VALEUR + 23 (b7=0x17)", "01 06 FF FF 7F FF 40 17"),
        ("RETOUR_VALEUR + 30 (b7=0x1E)", "01 06 FF FF 7F FF 40 1E"),
        ("RETOUR_VALEUR + 59 (b7=0x3B)", "01 06 FF FF 7F FF 40 3B"),
        ("Inconnu (b6=0x50)",           "01 06 FF FF 7F FF 50 00"),
        ("Inconnu (b6=0x60)",            "01 06 FF FF 7F FF 60 00"),
        ("Inconnu (b6=0x70)",            "01 06 FF FF 7F FF 70 00"),
        ("Inconnu (b6=0x80)",            "01 06 FF FF 7F FF 80 00"),
    ]
    for name, data in actions:
        if run_test(f"0x167 {name}", ("167", data), duration=2.0):
            return True
    return False


def cat_21F_remaining_bits():
    """0x21F byte[0] — bits 0/4/5 (non utilisés par boutons volant identifiés)."""
    header("0x21F byte[0] — bits restants (LIST/?)")
    tests = [
        ("bit 0 (LIST?)",  "01 00 00"),
        ("bit 4",          "10 00 00"),
        ("bit 5",          "20 00 00"),
        ("byte[2] = 0x01", "00 00 01"),
        ("byte[2] = 0x80", "00 00 80"),
    ]
    for name, data in tests:
        if run_test(f"0x21F {name}", ("21F", data), duration=3.0, idle_first=False):
            return True
    return False


def cat_276_formats():
    """0x276 dans plusieurs formats (5/6/7/8 bytes)."""
    header("0x276 — formats alternatifs (cible 22:30 26/04/2026)")
    # cible: année=154=0x9A, mois=04, jour=0x1A=26, heure=0x16=22, minute=0x1E=30
    formats = [
        ("5 bytes", "9A 04 1A 16 1E"),
        ("6 bytes + 0x3F", "9A 04 1A 16 1E 3F"),
        ("7 bytes BSI std", "9A 04 1A 16 1E 3F FE"),
        ("8 bytes + 0x00", "9A 04 1A 16 1E 3F FE 00"),
        ("ordre LE", "1E 16 1A 04 9A 3F FE"),
    ]
    for name, data in formats:
        # Émission soutenue 5s (la BSI émet à 1Hz normalement)
        if run_test(f"0x276 {name}", ("276", data), duration=5.0, idle_first=False):
            return True
    return False


def cat_39B_variants():
    """0x39B avec variantes payload."""
    header("0x39B — variantes")
    formats = [
        ("5 bytes std", "9A 04 1A 16 1E"),
        ("8 bytes (padding 00)", "9A 04 1A 16 1E 00 00 00"),
        ("8 bytes (padding FF)", "9A 04 1A 16 1E FF FF FF"),
        ("3 bytes minimal h:m:s", "16 1E 00"),
    ]
    for name, data in formats:
        if run_test(f"0x39B {name}", ("39B", data), duration=3.0, idle_first=False):
            return True
    return False


def cat_125_multiframe():
    """0x125 multiframe ISO-TP — radio text channel."""
    header("0x125 — multiframe ISO-TP (texte radio)")
    print("\n  Format ISO-TP 15765-2 :")
    print("    First Frame  : 10 LL DD DD DD DD DD DD  (LL=longueur totale)")
    print("    Cons. Frames : 21 DD DD DD DD DD DD DD  (sequence number 1-15)")
    print()
    # Test 1: simple text "TIME=22:30"
    text = b"TIME=22:30"
    L = len(text)
    # First frame max 6 data bytes, then 7 bytes per consecutive frame
    if L <= 7:
        # Single frame format: 0L DD...
        sf = bytes([L]) + text
        sf = sf.ljust(8, b'\xff')
        data = " ".join(f"{b:02X}" for b in sf)
        if run_test("0x125 single frame 'TIME=22:30'", ("125", data), duration=3.0, idle_first=False):
            return True
    else:
        # First frame
        ff = bytes([0x10, L]) + text[:6]
        data1 = " ".join(f"{b:02X}" for b in ff)
        # Consecutive frame
        cf = bytes([0x21]) + text[6:]
        cf = cf.ljust(8, b'\xff')
        data2 = " ".join(f"{b:02X}" for b in cf)
        print(f"    First frame  : {data1}")
        print(f"    Cons. frame  : {data2}")
        # Émission
        send("125", data1)
        time.sleep(0.05)
        send("125", data2)
        ans = ask_observation("Réaction ?")
        log_result("0x125 multiframe TIME=22:30", ans)
        if ans == "q":
            return True

    # Test 2: tentative "set time" command struct
    sets = [
        ("CMD=SET_TIME 22:30", "07 81 16 1E 00 FF FF FF"),
        ("Pure date hex 7 bytes", "07 9A 04 1A 16 1E FF FF"),
    ]
    for name, data in sets:
        if run_test(f"0x125 {name}", ("125", data), duration=3.0, idle_first=False):
            return True
    return False


def cat_full_rd4_simulation():
    """Émulation RD4 complète : présence permanente toutes les trames radio."""
    header("Émulation RD4 complète — toutes trames radio à 10Hz pendant 8s")
    print("\n  On émet en parallèle :")
    print("    0x122  : présence touches (00 00 00 00 00 02 00 00)")
    print("    0x1A8  : status radio (00 FF FF 00 00 05 C8 15)")
    print("    0x165  : RD4 status (00 00 00 00 00 00)")
    print("    0x125  : info radio (idle)")
    print("    0x1E0  : ?")
    print()

    frames_idle = [
        ("122", "00 00 00 00 00 02 00 00"),
        ("1A8", "00 FF FF 00 00 05 C8 15"),
        ("165", "00 00 00 00 00 00 00 00"),
        ("1E0", "00 00 00 00 00 00 00 00"),
    ]
    print("  [t=0-3s] Présence radio idle complète...")
    burst_multi(frames_idle, 3.0)

    print("  [t=3-7s] Présence + MENU pressé (0x122 byte[0]=0x80)")
    frames_menu = [
        ("122", "80 00 00 00 00 02 00 00"),
        ("1A8", "00 FF FF 00 00 05 C8 15"),
        ("165", "00 00 00 00 00 00 00 00"),
    ]
    burst_multi(frames_menu, 4.0)

    print("  [t=7-8s] release...")
    burst_multi(frames_idle, 1.0)

    ans = ask_observation("L'EMF a-t-il réagi pendant les 8 secondes ?")
    desc = ""
    if ans == "y":
        desc = input("    📝 À quel moment ? Décris : ").strip()
    log_result("Full RD4 simulation 8s", ans, desc)
    return ans == "q"


def cat_extended_rd4_session():
    """Présence permanente RD4 sur 30 secondes — pour user manipuler le commodo."""
    header("Présence RD4 permanente 30s — toi tu fais la manip")
    print("\n  Pendant 30s, je vais émettre en boucle TOUS les frames RD4 idle.")
    print("  → essaie le commodo essuie-glaces (court + long press)")
    print("  → essaie de naviguer le menu si l'EMF s'ouvre")
    print()
    input("  Appuie sur Enter pour démarrer (Ctrl-C pour arrêter)...")

    frames_idle = [
        ("122", "00 00 00 00 00 02 00 00"),
        ("1A8", "00 FF FF 00 00 05 C8 15"),
        ("165", "00 00 00 00 00 00 00 00"),
    ]
    try:
        end = time.time() + 30
        while time.time() < end:
            for id_hex, data in frames_idle:
                send(id_hex, data)
            remaining = int(end - time.time())
            print(f"\r  ⏱  {remaining}s restantes... ", end="", flush=True)
            time.sleep(0.1)
        print()
    except KeyboardInterrupt:
        print("\n  Interrompu.")
    ans = ask_observation("Quelque chose s'est passé sur l'EMF ?")
    desc = ""
    if ans == "y":
        desc = input("    📝 Décris : ").strip()
    log_result("RD4 idle presence 30s + user manipulation", ans, desc)
    return ans == "q"


def cat_custom():
    """Test custom : user fournit ID et payload."""
    header("Test custom — tu fournis ID et payload")
    while True:
        cid = input("\n  ID en hex (ex: 1A1) ou 'q' pour quitter : ").strip()
        if cid.lower() == "q" or not cid:
            return False
        data = input(f"  Payload bytes (ex: '01 02 03') : ").strip()
        if not data:
            continue
        dur = input("  Durée émission en secondes [2.0] : ").strip()
        try:
            dur = float(dur) if dur else 2.0
        except ValueError:
            dur = 2.0
        if run_test(f"CUSTOM 0x{cid.upper()} = {data}", (cid, data), duration=dur, idle_first=False):
            return True


# ──────────────────────────────────────────────────────────────────────────────
# Tests AVANCÉS post research 2026-05-09
# ──────────────────────────────────────────────────────────────────────────────

def cat_3E5_real_rd4_buttons():
    """0x3E5 — mapping EXACT depuis PSAWifiDisplayControl/CanMenuStructs.h.

    Structure (DLC=6) :
      byte[0] = MenuField  : bit 0=aircon, bit 4=phone, bit 6=MENU (0x40)
      byte[1] = ModeField  : bit 0=audio, bit 4=MODE (0x10), bit 6=TRIP (0x40)
      byte[2] = EscOkField : bit 2=dark,  bit 4=ESC (0x10), bit 6=OK (0x40)
      byte[3] = (réservé)
      byte[4] = (réservé)
      byte[5] = ArrowsField: bit 0=LEFT (0x01), bit 2=RIGHT (0x04),
                             bit 4=DOWN (0x10), bit 6=UP (0x40)

    Précédents tests avaient byte index décalé (faux mapping de l'agent).
    """
    header("0x3E5 — mapping CORRIGÉ (source PSAWifiDisplayControl)")
    print("\n  ⚠️ Mes précédents tests étaient sur les MAUVAIS bytes !")
    print("  Source : github.com/morcibacsi/PSAWifiDisplayControl src/Can/Structs/CanMenuStructs.h")
    print()
    tests = [
        ("MENU       (byte[0]=0x40)",  "40 00 00 00 00 00"),
        ("MODE       (byte[1]=0x10)",  "00 10 00 00 00 00"),
        ("TRIP       (byte[1]=0x40)",  "00 40 00 00 00 00"),
        ("OK         (byte[2]=0x40)",  "00 00 40 00 00 00"),
        ("ESC        (byte[2]=0x10)",  "00 00 10 00 00 00"),
        ("UP         (byte[5]=0x40)",  "00 00 00 00 00 40"),
        ("DOWN       (byte[5]=0x10)",  "00 00 00 00 00 10"),
        ("LEFT       (byte[5]=0x01)",  "00 00 00 00 00 01"),
        ("RIGHT      (byte[5]=0x04)",  "00 00 00 00 00 04"),
        ("PHONE      (byte[0]=0x10)",  "10 00 00 00 00 00"),
        ("AIRCON     (byte[0]=0x01)",  "01 00 00 00 00 00"),
        ("AUDIO      (byte[1]=0x01)",  "01 00 00 00 00 00"),
        ("DARK       (byte[2]=0x04)",  "00 00 04 00 00 00"),
    ]
    for name, data in tests:
        if run_test(f"0x3E5 {name}", ("3E5", data), duration=2.0):
            return True
    return False


def cat_psawifi_emulation():
    """Émulation EXACTE de PSAWifiDisplayControl — release continu + pulse unique.

    Ce que fait le firmware ESP32 :
    1. Loop permanent à 15Hz : émet 0x3E5 = 00 00 00 00 00 00 (release)
    2. Quand user veut presser : injecte UNE trame avec le bit set
    3. Le loop reset à 00 au cycle suivant → auto-release ~65ms

    Sans le release continu, l'EMF voit le bouton "bloqué" et ignore.
    """
    header("PSAWifiDisplayControl — émulation EXACTE (release continu + pulses)")
    print("\n  Logique :")
    print("    • Background : 0x3E5 = 00*6 émis toutes les 65ms")
    print("    • Action     : 1 trame avec bit du bouton, puis retour à 00")
    print()
    import threading

    BUTTONS = {
        "1": ("MENU",  "40 00 00 00 00 00"),
        "2": ("MODE",  "00 10 00 00 00 00"),
        "3": ("TRIP",  "00 40 00 00 00 00"),
        "4": ("OK",    "00 00 40 00 00 00"),
        "5": ("ESC",   "00 00 10 00 00 00"),
        "6": ("UP",    "00 00 00 00 00 40"),
        "7": ("DOWN",  "00 00 00 00 00 10"),
        "8": ("LEFT",  "00 00 00 00 00 01"),
        "9": ("RIGHT", "00 00 00 00 00 04"),
    }

    stop_event = threading.Event()

    def background_release():
        """Émet 0x3E5 = 00*6 toutes les 65ms (15Hz)."""
        while not stop_event.is_set():
            try:
                send("3E5", "00 00 00 00 00 00")
            except Exception:
                pass
            time.sleep(0.065)

    bg = threading.Thread(target=background_release, daemon=True)
    bg.start()
    print("  ▶ Background release démarré (0x3E5 = 00*6 @ 15Hz)")
    print("  ⏱  Attends 2s pour que l'EMF capte la présence...")
    time.sleep(2.0)

    print("\n  Boutons disponibles :")
    for k, (name, _) in BUTTONS.items():
        print(f"    {k}. {name}")
    print("    q. Stop et retour menu")
    print()
    print("  👉 Tape un numéro pour PRESSER le bouton (1 pulse), Enter pour répéter.")
    print()

    last_pressed = None
    try:
        while True:
            ans = input("  > ").strip()
            if ans == "q":
                break
            if ans == "" and last_pressed:
                ans = last_pressed
            if ans not in BUTTONS:
                print(f"    ⚠️  choix invalide")
                continue
            name, data = BUTTONS[ans]
            # Pulse unique (1 trame). Le background a déjà émis du 00 et continuera.
            send("3E5", data)
            print(f"    ▶ {name} pulsé ({data})")
            last_pressed = ans
    finally:
        stop_event.set()
        bg.join(timeout=1.0)
        # Émission finale propre
        send("3E5", "00 00 00 00 00 00")
        print("\n  ◀ Background release arrêté.")

    ans = ask_observation("L'EMF a-t-il réagi à un moment ? (menu, navigation, etc.)")
    desc = ""
    if ans == "y":
        desc = input("    📝 À quel(s) bouton(s) et qu'as-tu vu ? : ").strip()
    log_result("PSAWifiDisplayControl emulation", ans, desc)
    return ans == "q"


def cat_167_byte1():
    """0x167 byte[1] sweep — la doc utilisateur indique 0x167#0010000000000000 = MENU."""
    header("0x167 byte[1] — selon la doc utilisateur (jamais testé)")
    print("\n  Source : docs/recherche/controle_ecran_sans_rd4.md (ligne 87)")
    print("    \"cansend can0 167#0010000000000000 déclenchera l'apparition du menu\"")
    print()
    tests = [
        ("byte[1]=0x10 (MENU selon doc)", "00 10 00 00 00 00 00 00"),
        ("byte[1]=0x20",                  "00 20 00 00 00 00 00 00"),
        ("byte[1]=0x40",                  "00 40 00 00 00 00 00 00"),
        ("byte[1]=0x80",                  "00 80 00 00 00 00 00 00"),
        ("byte[1]=0x10 + byte[5]=0x02",   "00 10 00 00 00 02 00 00"),
        ("byte[1]=0x10 (1 pulse seul)",   "00 10 00 00 00 00 00 00"),
    ]
    for name, data in tests:
        if run_test(f"0x167 {name}", ("167", data), duration=2.0):
            return True
    # Test impulsionnel : 1 seule trame puis release
    print("\n  >>> Test PULSE UNIQUE (sans burst) — comme PSAWifiDisplayControl")
    send("167", "00 10 00 00 00 00 00 00")
    time.sleep(0.1)
    send("167", "00 00 00 00 00 00 00 00")
    ans = ask_observation("Réaction au pulse unique ?")
    log_result("0x167 byte[1]=0x10 PULSE UNIQUE", ans)
    return ans == "q"


def cat_swc_long_press():
    """Method Connects2 : maintenir SOURCE ou MUTE 3+ secondes pour bascule menu."""
    header("Méthode Connects2 — SWC long press (3 secondes)")
    print("\n  Doc utilisateur ligne 144 : 'maintenir Source (SRC) ou Mute pendant 3 secondes'")
    print("  → bascule l'EMF en mode menu (sur boîtier Connects2 commercial)")
    print()
    print("  On simule des appuis maintenus 4s sur 0x21F :")
    print()
    tests = [
        ("SOURCE held 4s (b1=0x02)",        "02 00 00", 4.0),
        ("MUTE = VOL+ ∧ VOL- held 4s",      "0C 00 00", 4.0),
        ("VOL+ held 4s (test)",             "08 00 00", 4.0),
        ("SOURCE+VOL+ held 4s",             "0A 00 00", 4.0),
        ("Tous boutons held 4s (0xFF)",     "FF 00 00", 4.0),
    ]
    for name, data, dur in tests:
        if run_test(f"0x21F {name}", ("21F", data), duration=dur, idle_first=False):
            return True
    return False


def cat_full_rd4_9frames():
    """Émulation RD4 complète AEE2004 — 9 trames idle simultanées à 10Hz.

    L'EMF Magneti Marelli refuse peut-être tant qu'il ne voit pas TOUTES
    les trames de présence radio. Liste tirée de la doc PSA-RE LS.CONF.
    """
    header("Émulation RD4 complète — 9 trames simultanées à 10Hz")
    print("\n  Trames émises en parallèle (idle, simulant RD4 alimenté/silencieux) :")
    print("    0x165  RADIO_STATUS_GENERAL    (4 bytes)")
    print("    0x1A5  RADIO_STATUS_VOLUME     (1 byte)")
    print("    0x1E0  RADIO_STATUS_CONFIG     (5 bytes)")
    print("    0x225  RADIO_STATUS_TUNER      (5 bytes)")
    print("    0x265  RADIO_STATUS_FM_CURRENT (4 bytes)")
    print("    0x325  RADIO_STATUS_CD_GEN     (3 bytes)")
    print("    0x365  RADIO_STATUS_CD_DISK    (5 bytes)")
    print("    0x3A5  RADIO_STATUS_CD_TRACK   (6 bytes)")
    print("    0x3E5  RADIO_STATUS_PANEL      (6 bytes — boutons)")
    print()

    # Phase 1 : présence idle stable 5 secondes
    radio_idle = [
        ("165", "01 00 00 00"),                    # POWER=on
        ("1A5", "10"),                              # volume idle
        ("1E0", "00 00 00 00 00"),
        ("225", "00 00 00 00 00"),
        ("265", "00 00 00 00"),
        ("325", "00 00 00"),
        ("365", "00 00 00 00 00"),
        ("3A5", "00 00 00 00 00 00"),
        ("3E5", "00 00 00 00 00 00"),               # tous boutons relâchés
    ]
    print("  [Phase 1] Présence idle 5s pour stabiliser l'EMF...")
    burst_multi(radio_idle, 5.0)
    ans1 = ask_observation("L'EMF a-t-il changé d'apparence (logo radio, source affichée, etc.) ?")
    log_result("RD4 9-frames presence 5s", ans1)

    if ans1 == "q":
        return True

    # Phase 2 : MENU pressé pendant que présence continue
    print("\n  [Phase 2] MENU maintenu 5s avec présence + 0x3E5 b1=0x40...")
    radio_menu = list(radio_idle)
    radio_menu[-1] = ("3E5", "00 40 00 00 00 00")  # MENU pressé
    burst_multi(radio_menu, 5.0)
    ans2 = ask_observation("L'EMF a-t-il ouvert un menu / changé l'affichage ?")
    log_result("RD4 9-frames + MENU 5s", ans2)
    if ans2 == "q":
        return True

    # Release
    burst_multi(radio_idle, 1.0)

    # Phase 3 : OK pressé
    print("\n  [Phase 3] OK maintenu 3s...")
    radio_ok = list(radio_idle)
    radio_ok[-1] = ("3E5", "00 00 00 40 00 00")
    burst_multi(radio_ok, 3.0)
    ans3 = ask_observation("Réaction sur OK ?")
    log_result("RD4 9-frames + OK 3s", ans3)

    return ans3 == "q"


def cat_uds_emf_probe():
    """UDS session sur l'EMF via 0x765/0x665 — lecture seule, sans risque."""
    header("UDS — session diagnostic sur EMF (0x765/0x665)")
    print("\n  Service 0x10 sub 0x03 (Extended Diagnostic Session) sur l'EMF.")
    print("  ECU AFFICHEUR PSA = req 0x765 / resp 0x665.")
    print("  Si on obtient une réponse, on tente de lire des DIDs pour")
    print("  trouver l'horloge/date interne.")
    print()
    print("  ⚠️  LECTURE SEULE. Aucun risque de DTC.")
    print()

    try:
        import can  # noqa
    except ImportError:
        print("  ❌ python-can non installé. Installe avec : sudo apt install python3-can")
        return False

    import can
    try:
        bus = can.Bus(channel="slcan0", interface="socketcan")
    except Exception as e:
        print(f"  ❌ Impossible d'ouvrir slcan0 : {e}")
        return False

    def uds_send_recv(req_data: bytes, expected_resp_id: int = 0x665, timeout: float = 1.0):
        """Envoie une trame UDS single-frame et attend la réponse."""
        # Padding ISO-TP single frame : byte[0] = 0x0L (L=length), reste padding 0xAA ou 0x00
        sf = bytes([len(req_data)]) + req_data
        sf = sf.ljust(8, b'\xAA')
        msg = can.Message(arbitration_id=0x765, data=sf, is_extended_id=False)
        try:
            bus.send(msg)
        except Exception as e:
            print(f"    ⚠️  TX raté : {e}")
            return None
        # Attendre réponse
        end = time.time() + timeout
        while time.time() < end:
            r = bus.recv(timeout=0.1)
            if r is None:
                continue
            if r.arbitration_id == expected_resp_id:
                return bytes(r.data)
        return None

    print("  📡 Test 1 : Extended Diagnostic Session (0x10 0x03)...")
    resp = uds_send_recv(bytes([0x10, 0x03]))
    if resp is None:
        print("     ❌ Pas de réponse sur 0x665.")
        # Try other addresses
        print("\n  📡 Test 2 : essai sur autres adresses EMF candidates...")
        for req_id, resp_id in [(0x764, 0x664), (0x76C, 0x66C), (0x732, 0x632), (0x7B5, 0x6B5)]:
            sf = bytes([2, 0x10, 0x03]).ljust(8, b'\xAA')
            msg = can.Message(arbitration_id=req_id, data=sf, is_extended_id=False)
            bus.send(msg)
            time.sleep(0.05)
            end = time.time() + 0.5
            got = None
            while time.time() < end:
                r = bus.recv(timeout=0.1)
                if r and r.arbitration_id == resp_id:
                    got = bytes(r.data)
                    break
            if got:
                print(f"     ✅ Réponse sur 0x{resp_id:03X} : {got.hex(' ').upper()}")
            else:
                print(f"     ❌ rien sur 0x{resp_id:03X}")
        log_result("UDS EMF probe — no response", "n")
        bus.shutdown()
        ans = ask_observation("Tu vois quelque chose sur l'écran (mode diag) ?")
        log_result("UDS EMF probe visual", ans)
        return ans == "q"
    print(f"     ✅ Réponse : {resp.hex(' ').upper()}")
    if resp[1] == 0x50 and resp[2] == 0x03:
        print("     🎯 Session UDS Extended OUVERTE sur EMF !")
    elif resp[1] == 0x7F:
        nrc = resp[3] if len(resp) > 3 else 0
        print(f"     ⚠️  Negative response code : 0x{nrc:02X}")
    log_result("UDS EMF probe response", "y", resp.hex(' ').upper())

    # Test lecture DID standard (0xF190 = VIN)
    print("\n  📡 Test 3 : ReadDataByIdentifier 0xF190 (VIN)...")
    resp = uds_send_recv(bytes([0x22, 0xF1, 0x90]))
    if resp:
        print(f"     Réponse : {resp.hex(' ').upper()}")
        if resp[1] == 0x62:
            try:
                vin = resp[4:].decode('ascii', errors='replace').strip('\x00\xAA')
                print(f"     VIN extrait : {vin!r}")
            except Exception:
                pass
    else:
        print("     ❌ pas de réponse")

    # Scan rapide de quelques DIDs probables pour heure/date
    print("\n  📡 Test 4 : scan DIDs candidates pour horloge...")
    candidates = [
        (0xF1, 0x9D, "current date/time PSA"),
        (0x21, 0x00, "?"),
        (0x21, 0x01, "?"),
        (0x21, 0x10, "?"),
        (0x22, 0x00, "?"),
        (0x22, 0x05, "?"),
        (0x22, 0x06, "?"),
        (0x2A, 0x00, "?"),
    ]
    for hi, lo, desc in candidates:
        resp = uds_send_recv(bytes([0x22, hi, lo]), timeout=0.5)
        if resp and resp[1] == 0x62:
            print(f"     0x{hi:02X}{lo:02X} ({desc}) : {resp.hex(' ').upper()}")
        elif resp and resp[1] == 0x7F:
            pass  # ignored (negative)
        # pas de print si pas de réponse pour pas spammer

    bus.shutdown()
    log_result("UDS EMF DID scan complete", "y")
    print("\n  ✅ Scan terminé. Si la session UDS est ouverte et qu'un DID horloge a été")
    print("     trouvé, on pourra écrire dessus avec service 0x2E (mais SecurityAccess")
    print("     0x27 sera probablement requis — clé inconnue).")
    return False


# ──────────────────────────────────────────────────────────────────────────────
# Menu principal
# ──────────────────────────────────────────────────────────────────────────────

CATEGORIES = [
    ("🔥 PSAWifiDisplayControl — émulation EXACTE (release+pulse)",  cat_psawifi_emulation),
    ("🔥 0x3E5 — mapping CORRIGÉ (source GitHub)",                   cat_3E5_real_rd4_buttons),
    ("🔥 0x167 byte[1]=0x10 — selon doc utilisateur (jamais testé)", cat_167_byte1),
    ("🔥 SWC long press 3-4s (méthode Connects2)",                   cat_swc_long_press),
    ("⭐ Émulation RD4 complète 9 trames (PSA-RE conforme)",         cat_full_rd4_9frames),
    ("⭐ UDS probe EMF (0x765/0x665) — lecture diag",                 cat_uds_emf_probe),
    ("0x122 byte[0] — sweep 8 bits (NOTE: ID inexistant LS.CONF AEE2004)", cat_122_byte0_sweep),
    ("0x122 byte[1] — sweep 8 bits",                             cat_122_byte1_sweep),
    ("0x122 combinaisons",                                       cat_122_combinations),
    ("0x1A8 byte[0] — sweep 8 bits",                             cat_1A8_sweep_b0),
    ("0x167 BUTTON_ACTION — tous codes",                         cat_167_button_action),
    ("0x21F bits restants (0/4/5/byte[2])",                      cat_21F_remaining_bits),
    ("0x276 — formats alt (NOTE: AEE2010 only)",                 cat_276_formats),
    ("0x39B — variantes",                                        cat_39B_variants),
    ("0x125 — multiframe ISO-TP",                                cat_125_multiframe),
    ("Émulation RD4 ancienne (8s, 4 frames)",                    cat_full_rd4_simulation),
    ("Présence RD4 permanente 30s",                              cat_extended_rd4_session),
    ("Test custom (ID + payload libre)",                         cat_custom),
]


def show_menu():
    print("\n" + "═" * 70)
    print("  🎛  MENU TESTS RD4 / EMF — Citroën C2")
    print("═" * 70)
    for i, (label, _) in enumerate(CATEGORIES, 1):
        print(f"  {i:>2}.  {label}")
    print("   q.  Quitter")
    print()


def check_bridge():
    try:
        r = requests.get("http://127.0.0.1:8080/api/state", timeout=2)
        return r.status_code == 200
    except Exception:
        return False


def main():
    print("\n┌─────────────────────────────────────────────────────────────────┐")
    print("│  Tests RD4 / EMF — Citroën C2  (interactif)                     │")
    print("│  Log  : " + str(LOG_FILE).ljust(57) + "│")
    print("└─────────────────────────────────────────────────────────────────┘")

    if not check_bridge():
        print("\n❌ Bridge HTTP non accessible sur :8080")
        print("   Lance d'abord : ./scripts/bridge.py &")
        return 1

    print("\n✅ Bridge OK")

    while True:
        show_menu()
        choice = input("  Choix : ").strip().lower()
        if choice == "q":
            break
        if not choice.isdigit():
            print("  ⚠️  entrée invalide")
            continue
        n = int(choice)
        if 1 <= n <= len(CATEGORIES):
            label, fn = CATEGORIES[n - 1]
            try:
                if fn():
                    break
            except KeyboardInterrupt:
                print("\n  Interrompu (Ctrl-C). Retour au menu.")
        else:
            print(f"  ⚠️  choix entre 1 et {len(CATEGORIES)}")

    print(f"\n📁 Tous les résultats sont dans {LOG_FILE}")
    print("   Tu peux le coller dans la conversation pour analyse.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
