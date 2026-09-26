# Outillage PC (`scripts/`)

Scripts Linux pour brancher un PC sur le CAN-Confort de la C2 via le CANable,
explorer le bus, valider des décodeurs et piloter la voiture.

## Prérequis

- CANable 2.0 en firmware **slcan** (`/dev/ttyACM0`, USB `16d0:117e`)
- `can-utils` (candump, cansend, slcand), `python3-can`, `aiohttp`
  (bridge), `requests` (menus EMF/RD4)
- `sudo` pour `slcand` / `ip link`
- Branchement derrière l'autoradio, **sans** terminaison 120 Ω côté CANable
  (voir [docs/terrain/field_test_playbook.md](../docs/terrain/field_test_playbook.md))

## Scripts

### Interface

| Script | Usage | Rôle |
|---|---|---|
| `can_up.sh` | `./can_up.sh [bitrate]` | monte `slcan0` via `slcand` (défaut 125000 = CAN-Confort ; `CAN_TTY`, `CAN_IFACE` surchargeables) |
| `can_down.sh` | `./can_down.sh` | démonte l'interface |
| `bus_health.py` | `./bus_health.py [--duration 5] [--auto-bitrate]` | premier diagnostic : le bus parle ? bon débit ? IDs PSA reconnus ? |

### Découverte

| Script | Usage | Rôle |
|---|---|---|
| `capture.sh` | `./capture.sh <label> [secondes]` | `candump -L` horodaté → `captures/<date>_<label>.log` (rejouable) |
| `list_ids.py` | `./list_ids.py <capture.log>` ou `--live slcan0 [--duration 10]` | inventaire des IDs : période, charge utile type |
| `diff_capture.py` | `./diff_capture.py <référence.log> <appui.log>` | IDs / octets qui diffèrent entre deux captures |
| `sniff_changes.py` | `./sniff_changes.py [iface] [--hold 2] [--only …] [--ignore …]` | n'affiche que les IDs dont la charge utile vient de changer |
| `button_isolator.py` | `./button_isolator.py <nom> [--cycles 3] [--idle 3] [--press 2]` | alterne fenêtres repos / appui pour isoler le bit d'un bouton |

### Décodage

| Script | Usage | Rôle |
|---|---|---|
| `decode_live.py` | `./decode_live.py [iface]` | tableau de bord texte des trames connues |
| `bridge.py` | voir ci-dessous | décodeurs de référence + API HTTP/WebSocket + dashboard web |
| `test_decoders.py` | `python3 -m unittest scripts/test_decoders.py` (racine) | vérifie les décodeurs de `bridge.py` sur `fixtures/can_decode_cases.tsv` |
| `_canlog.py` | (module) | parseur partagé du format `candump -L` |

### Émission (⚠ part sur le bus réel)

| Script | Usage | Rôle |
|---|---|---|
| `set_clock.py` | `./set_clock.py [--datetime "AAAA-MM-JJ HH:MM"] [--dry-run]` | trame `0x39B` (set heure BSI) — **sans effet sur l'EMF de la C2** |
| `send_button.py` | `./send_button.py vol+\|vol-\|mute\|source\|raw 0x10 [--hold 0.5] [--pulses 3]` | émule un bouton volant (`0x21F`) |
| `emf_keyboard_nav.py` | `./emf_keyboard_nav.py` (bridge lancé) | navigue dans l'EMF au clavier (flèches, Entrée = OK, Échap, m = MENU…) via `0x3E5` |
| `rd4_test_menu.py` | `./rd4_test_menu.py` (bridge lancé) | menu de tests d'émulation RD4 ; résultats dans `captures/rd4_tests_<date>.log` |

L'EMF n'accepte `0x3E5` que si la BSI est télécodée « RD4 présent ».

## Bridge HTTP / WebSocket

```bash
./can_up.sh 125000
./bridge.py                                   # http://<pc>:8080, écoute slcan0
./bridge.py --replay ../captures/<fichier>.log --port 8088   # rejoue une capture, sans voiture
```

Options : `--iface slcan0`, `--bustype socketcan`, `--host 0.0.0.0`,
`--port 8080`, `--replay <log>`, `-v`. **Aucune authentification** : réseau
local de la voiture uniquement.

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/` | dashboard (`web/dashboard.html`) |
| GET | `/api/state` | état décodé courant (JSON) |
| GET | `/api/raw?id=21F` | dernière trame brute d'un ID |
| WS | `/ws` | push de chaque mise à jour |
| POST | `/api/clock` | `{}` ou `{"datetime": "AAAA-MM-JJ HH:MM"}` → `0x39B` |
| POST | `/api/button` | `{"button": "vol+"}` → `0x21F` |
| POST | `/api/send` | `{"id": "21F", "data": "04 00 00"}` → trame brute |
| GET | `/api/emf` | état du mode EMF |
| POST | `/api/emf/toggle` | `{"active": true\|false}` ou `{}` (bascule) : release périodique `0x3E5` |
| POST | `/api/emf/button` | `{"button": "MENU"}` (MENU, MODE, TRIP, OK, ESC, UP, DOWN, LEFT, RIGHT…) ; mode EMF actif requis (sinon 409) |

Les décodeurs `decode_XXX()` sont la référence dont `CanDecoder.kt` (app) est
le portage : toute modification se fait des deux côtés, avec une ligne dans
`fixtures/can_decode_cases.tsv`. Signaux et niveaux de preuve :
[docs/can/signaux_decodes.md](../docs/can/signaux_decodes.md).

## Isoler un bouton

```bash
./can_up.sh 125000
./capture.sh reference 10          # rien d'appuyé
./capture.sh vol_plus 5            # appuyer sur VOL+ pendant la capture
./diff_capture.py ../captures/*_reference.log ../captures/*_vol_plus.log
./can_down.sh
```

Méthodes détaillées : [docs/terrain/methodologie.md](../docs/terrain/methodologie.md) ;
commandes can-utils : [docs/terrain/cheatsheet.md](../docs/terrain/cheatsheet.md).
