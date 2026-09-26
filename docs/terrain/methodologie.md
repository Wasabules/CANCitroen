# Méthodologie — isoler une commande au volant

## Pré-requis matériel côté voiture

La C2 expose le CAN-Confort sur **deux paires de fils** qu'on peut prendre :

- **Derrière l'autoradio** (le plus simple) : connecteur ISO ou propriétaire RD3/RD4.
  Sur le faisceau dérrière la façade, repérer **CAN-H** et **CAN-L** (typiquement
  marron/vert ou vert/jaune selon millésime — TOUJOURS vérifier au multimètre).
- **Sur la prise OBD-II** (sous le volant) : **PSA n'expose PAS le CAN-Confort sur OBD**
  par défaut. L'OBD donne accès au CAN-IS (500 kbit/s) via la K-Line/CAN, pas au
  bus confort où vivent les commandes au volant. → **Aller derrière l'autoradio.**

## Procédure de branchement

1. Contact OFF, débrancher la cosse - de la batterie 5 min (ou pas, selon paranoïa).
2. Déposer la façade autoradio (4 clips PSA + 2 vis).
3. Identifier CAN-H / CAN-L au multimètre :
   - Au repos : **CAN-H ≈ 2.5 V**, **CAN-L ≈ 2.5 V** (différentiel ~0)
   - En activité : H oscille 2.5→3.5 V, L oscille 2.5→1.5 V
4. **Brancher CANable en parallèle** (pas en série !) :
   - CAN-H voiture → CANable H
   - CAN-L voiture → CANable L
   - **Pas besoin** de masse séparée si le PC est sur batterie ; sinon GND châssis.
5. **PAS DE TERMINAISON 120 Ω** depuis le CANable : le bus est déjà terminé
   correctement par les ECUs de la voiture. Ajouter une résistance casserait le bus.
   (Vérifier que le switch/jumper TERM du CANable est sur OFF.)

## Procédure logicielle

```bash
# 1. Monter le bus
cd ~/CANCitroen
./scripts/can_up.sh 125000

# 2. Vue d'ensemble : quels IDs causent et à quel rythme ?
./scripts/list_ids.py --live slcan0 --duration 15

# 3. Méthode A — diff de captures
./scripts/capture.sh baseline 8        # NE TOUCHE À RIEN
./scripts/capture.sh source 5           # APPUIE sur SOURCE
./scripts/diff_capture.py captures/<...>baseline.log captures/<...>source.log

# 4. Méthode B — isolation interactive (plus robuste)
./scripts/button_isolator.py source --cycles 4

# 5. Méthode C — live, en regardant les changements
./scripts/sniff_changes.py slcan0
# (appuie sur les boutons, note les IDs)

# 6. Quand fini
./scripts/can_down.sh
```

## Astuces pour distinguer le signal du bruit

- Le **compteur d'incrément** (rolling counter) sur 4 bits change CONSTAMMENT.
  Apparaît dans `sniff_changes` même sans rien toucher → ignorer avec `--ignore`.
- Le **CRC / checksum** sur le dernier octet bouge avec n'importe quel autre
  changement → ne pas confondre avec le signal du bouton.
- Beaucoup de boutons sont **édge-triggered** : la trame avec la valeur "appuyé"
  ne dure que **1 ou 2 cycles** puis revient à 0x00. Capturer longtemps PRESS
  pour avoir au moins une occurrence.
- Certains boutons (vol+/-) sont **auto-répétés** par la BSI : tu verras la trame
  toutes les ~100 ms tant que tu maintiens.

## Format trame typique commande au volant PSA

Sur la plupart des PSA récentes (incl. C2), les commandes au volant arrivent dans
une trame émise par le **CMM (calculateur sous volant)** ou la **BSI**, ID à
confirmer pour la C2 (voir `psa_can_confort.md`). Format type :

```
Byte 0 : bitmap des touches "haut" (SRC, MODE, etc.)
Byte 1 : bitmap des touches "bas" (VOL+, VOL-, etc.)
Byte 2 : roller / position si présent
Byte 3 : compteur (4 bits) + checksum (4 bits)
```

À confirmer sur ta C2 — les schémas varient selon millésime et niveau d'équipement.
