#!/usr/bin/env python3
"""EMF Menu Navigator — clavier ↔ boutons RD4 émulés.

Reproduit la logique de PSAWifiDisplayControl :
  - Émet 0x3E5 = 00*6 toutes les 65ms (release continu, 15 Hz)
  - À chaque touche : 1 pulse 0x3E5 avec le bit du bouton
  - Le loop release suivant auto-relâche dans ~65ms (l'EMF détecte un edge)

Pré-requis :
  - bridge.py tournant sur :8080  (./scripts/bridge.py)
  - télécodage RD4 actif sur la BSI (sinon l'EMF ignore)
  - voiture contact ON

Usage :
    ./scripts/emf_keyboard_nav.py

Touches :
    ↑ ↓ ← →     navigation EMF
    w s a d     idem (alternative)
    h j k l     idem (style vim, h=←  j=↓  k=↑  l=→)
    Enter / o   OK
    Esc / e     ESC
    m           MENU (ouvre menu principal)
    t           TRIP (cycle ordinateur de bord)
    M           MODE (Shift+m)
    p           PHONE
    a (Shift)   AIRCON
    n           DARK (mode nuit)
    ?           afficher l'aide
    q           Quitter
"""
from __future__ import annotations

import select
import sys
import termios
import threading
import time
import tty

import requests

URL = "http://127.0.0.1:8080/api/send"
BG_PERIOD = 0.065   # 15 Hz
RELEASE_DATA = "00 00 00 00 00 00"

# Mapping action → (label affiché, payload 6 bytes 0x3E5)
BUTTONS = {
    "UP":     ("↑ UP",      "00 00 00 00 00 40"),
    "DOWN":   ("↓ DOWN",    "00 00 00 00 00 10"),
    "LEFT":   ("← LEFT",    "00 00 00 00 00 01"),
    "RIGHT":  ("→ RIGHT",   "00 00 00 00 00 04"),
    "OK":     ("✓ OK",      "00 00 40 00 00 00"),
    "ESC":    ("✗ ESC",     "00 00 10 00 00 00"),
    "MENU":   ("☰ MENU",    "40 00 00 00 00 00"),
    "TRIP":   ("⊙ TRIP",    "00 40 00 00 00 00"),
    "MODE":   ("⇋ MODE",    "00 10 00 00 00 00"),
    "PHONE":  ("☎ PHONE",   "10 00 00 00 00 00"),
    "AIRCON": ("❄ AIRCON",  "01 00 00 00 00 00"),
    "DARK":   ("☾ DARK",    "00 00 04 00 00 00"),
}

# Mapping touche raw → action
KEY_MAP = {
    # Validation
    "\r": "OK", "\n": "OK", "o": "OK", "O": "OK", " ": "OK",
    # Boutons texte (par fonction, pas par position physique)
    "m": "MENU",
    "t": "TRIP", "T": "TRIP",
    "M": "MODE",
    "p": "PHONE", "P": "PHONE",
    "x": "AIRCON", "X": "AIRCON",      # 'x' au lieu de Shift+a (conflit AZERTY/QWERTY)
    "n": "DARK", "N": "DARK",
    "e": "ESC",  "E": "ESC",
    # Flèches QWERTY WASD
    "w": "UP",
    "s": "DOWN", "S": "DOWN",
    "a": "LEFT",
    "d": "RIGHT", "D": "RIGHT",
    # Flèches AZERTY ZQSD (équivalent ergonomique de WASD)
    "z": "UP", "Z": "UP",
    "q": "LEFT", "Q": "LEFT",
    # 's' déjà mappé à DOWN, 'd' déjà à RIGHT — partagés AZERTY/QWERTY
    # Flèches vim hjkl
    "k": "UP", "K": "UP",
    "j": "DOWN", "J": "DOWN",
    "h": "LEFT",
    "l": "RIGHT", "L": "RIGHT",
    # Aide / quit
    "?": "HELP", "/": "HELP",
    "W": "QUIT",                       # 'W' (Shift+w, AZERTY/QWERTY commun)
}
# Note: la touche 'q' AZERTY est mappée LEFT (pas QUIT pour éviter conflit avec
# l'utilisateur qui veut juste naviguer à gauche). Quitter = Ctrl-C ou ESC ESC ESC.

# 'q' a été retiré de QUIT car en AZERTY c'est la touche de gauche du clavier.
# Pour quitter : Ctrl-C ou taper "exit" (gérer plus bas)

# Couleurs ANSI
C_RESET = "\x1b[0m"
C_BOLD = "\x1b[1m"
C_DIM = "\x1b[2m"
C_GREEN = "\x1b[32m"
C_YELLOW = "\x1b[33m"
C_CYAN = "\x1b[36m"
C_MAGENTA = "\x1b[35m"
C_RED = "\x1b[31m"

running = True
session = requests.Session()


def send_can(data: str) -> bool:
    try:
        session.post(URL, json={"id": "3E5", "data": data}, timeout=0.5)
        return True
    except Exception:
        return False


def background_release():
    """Émet 0x3E5 = 00*6 à 15 Hz tant que running."""
    while running:
        send_can(RELEASE_DATA)
        time.sleep(BG_PERIOD)


def read_key_blocking() -> str:
    """Lit 1 touche en bloquant. Gère les séquences ANSI flèches.

    Retourne un label d'action (UP/DOWN/.../OK/ESC/MENU/QUIT/HELP) ou "UNKNOWN".

    Détection ESC vs flèche : on vide complètement le buffer après un ESC
    (timeout 200 ms) et on parse l'ensemble ; ça évite les fuites de chars
    dans le prochain cycle (problème classique des terminaux lents/AZERTY).
    """
    ch = sys.stdin.read(1)
    if ch != "\x1b":
        return KEY_MAP.get(ch, "UNKNOWN")

    # On a lu un ESC — peut être ESC seul OU début de séquence ANSI.
    # On lit TOUT ce qui arrive dans les 200 ms suivantes pour ne rien laisser
    # traîner dans le buffer.
    buf = ""
    deadline = time.time() + 0.20
    while time.time() < deadline:
        remaining = deadline - time.time()
        if remaining <= 0:
            break
        if select.select([sys.stdin], [], [], remaining)[0]:
            try:
                buf += sys.stdin.read(1)
            except Exception:
                break
            # Si on reconnaît une séquence flèche, on sort tout de suite
            if buf in ("[A", "[B", "[C", "[D", "OA", "OB", "OC", "OD"):
                return {
                    "[A": "UP", "[B": "DOWN", "[C": "RIGHT", "[D": "LEFT",
                    "OA": "UP", "OB": "DOWN", "OC": "RIGHT", "OD": "LEFT",
                }[buf]
        else:
            break

    if buf == "":
        # ESC seul, sans rien après → c'est bien la touche Échap
        return "ESC"
    if buf == "\x1b\x1b" or buf == "\x1b":
        # Double ESC ou ESC + ESC → on quitte
        return "QUIT"
    # Séquence inconnue, on ignore
    return "UNKNOWN"


def print_banner():
    bar = "═" * 70
    print(f"\n{C_CYAN}{bar}{C_RESET}")
    print(f"  {C_BOLD}🎛  EMF Menu Navigator — Citroën C2 (RD4 émulé){C_RESET}")
    print(f"{C_CYAN}{bar}{C_RESET}")
    print(f"  {C_DIM}Background : 0x3E5 = 00 00 00 00 00 00 @ 15 Hz (release continu){C_RESET}")
    print()


def print_help():
    print(f"  {C_BOLD}Touches (compatible AZERTY/QWERTY) :{C_RESET}")
    print(f"    {C_GREEN}↑ ↓ ← →{C_RESET}        navigation EMF (flèches du clavier)")
    print(f"    {C_GREEN}z q s d{C_RESET}        idem AZERTY")
    print(f"    {C_GREEN}w a s d{C_RESET}        idem QWERTY")
    print(f"    {C_GREEN}k j h l{C_RESET}        idem style vim")
    print(f"    {C_GREEN}Enter / Espace / o{C_RESET}   OK")
    print(f"    {C_GREEN}Esc / e{C_RESET}        ESC")
    print(f"    {C_GREEN}m{C_RESET}              MENU (ouvre menu principal)")
    print(f"    {C_GREEN}t{C_RESET}              TRIP")
    print(f"    {C_GREEN}M{C_RESET}              MODE (Shift+m)")
    print(f"    {C_GREEN}p{C_RESET}              PHONE")
    print(f"    {C_GREEN}x{C_RESET}              AIRCON")
    print(f"    {C_GREEN}n{C_RESET}              DARK")
    print(f"    {C_GREEN}?{C_RESET}              afficher cette aide")
    print(f"    {C_RED}W (Shift+w) / Ctrl-C{C_RESET}   Quitter")
    print(f"  {C_DIM}─────────────────────────────────────────────────────────────────────{C_RESET}")
    print(f"  {C_DIM}Note: 'q' = LEFT (AZERTY) — pour quitter, utilise Ctrl-C ou Shift+W{C_RESET}")


def main():
    # 0. Vérifier que stdin est un TTY (sinon on ne peut pas lire les touches en raw)
    if not sys.stdin.isatty():
        print(f"{C_RED}❌ stdin n'est pas un TTY{C_RESET} — lance directement dans ton terminal,")
        print("   pas via un pipe ou une redirection.")
        return 1

    # 1. Vérifier le bridge
    try:
        r = session.get("http://127.0.0.1:8080/api/state", timeout=1)
        if r.status_code != 200:
            raise RuntimeError("HTTP non-200")
    except Exception as e:
        print(f"{C_RED}❌ Bridge non joignable sur :8080{C_RESET}")
        print("   Lance d'abord : ./scripts/bridge.py &")
        return 1

    print_banner()
    print_help()
    print()

    # 2. Démarrer le thread release (CRITIQUE)
    global running
    running = True
    bg = threading.Thread(target=background_release, daemon=True)
    bg.start()

    # 3. Setup terminal en mode raw (lecture caractère par caractère)
    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)

    try:
        tty.setcbreak(fd)
        print(f"  {C_YELLOW}▶ Prêt. Appuie sur 'm' pour ouvrir le MENU EMF.{C_RESET}")
        print(f"  {C_DIM}  (q pour quitter, ? pour ré-afficher l'aide){C_RESET}")
        print()

        while True:
            action = read_key_blocking()

            if action == "QUIT":
                break
            if action == "HELP":
                print()
                print_help()
                print()
                continue
            if action == "UNKNOWN":
                # Ignorer silencieusement les touches non mappées
                continue

            if action in BUTTONS:
                label, data = BUTTONS[action]
                send_can(data)
                ts = time.strftime("%H:%M:%S")
                print(
                    f"  {C_DIM}[{ts}]{C_RESET}  "
                    f"{C_GREEN}▶ {label}{C_RESET}  "
                    f"{C_DIM}0x3E5 = {data}{C_RESET}"
                )
    except KeyboardInterrupt:
        pass
    finally:
        running = False
        bg.join(timeout=1.0)
        # Final release pour être sûr qu'aucun bouton ne reste "appuyé"
        send_can(RELEASE_DATA)
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
        print()
        print(f"  {C_CYAN}◀ Navigation arrêtée. Background release stoppé.{C_RESET}")
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
