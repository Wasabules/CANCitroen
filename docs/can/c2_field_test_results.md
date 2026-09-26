# Résultats field test Citroën C2 — 2026-04-26

> **Document historique.** Plusieurs conclusions ont été dépassées depuis
> (T° extérieure, odomètre, T° moteur, carburant trouvés ou recalculés le
> 2026-05-09). Référence à jour : [signaux_decodes.md](signaux_decodes.md).

Calibration et tests sur la voiture du user (Citroën C2, BSI gen 2 AEE2004,
RD4 d'origine retiré, remplacé par autoradio Atoto A6PF).

## Configuration validée

- **Bus CAN-Confort** détecté à **125 kbit/s**, ~185 trames/s, 38 IDs distincts
- **16 IDs PSA AEE2004 reconnus** : 0x036, 0x0B6, 0x0F6, 0x128, 0x161, 0x1A1, 0x1A8, 0x21F, 0x220, 0x221, 0x261, 0x2A1, 0x2B6, 0x336, 0x3A7, 0x3B6
- TX émission validée (TX errors = 0, ACK reçu)

## Décodeurs validés

### Boutons volant 0x21F — **objectif principal du projet ✅**

```
byte[0] (bitmap) :
  bit 1 (0x02) = SOURCE/MODE
  bit 2 (0x04) = VOL-      (inversé vs doc PSA générique)
  bit 3 (0x08) = VOL+      (inversé vs doc PSA générique)
  bit 6 (0x40) = PRECEDANT
  bit 7 (0x80) = SUIVANT
  bit 0 (0x01), bits 4 (0x10), 5 (0x20) : non-fonctionnels sur cette C2
                                          (testés mais aucun effet visible)
byte[1] = compteur scroll signé (cumul, ↑ scroll+, ↓ scroll-)
byte[2] = 0x00 réservé
```

### Feux 0x128 byte[4]
```
bit 1 (0x02) = cligno gauche
bit 2 (0x04) = cligno droit  (warnings = bits 1+2 ensemble = 0x06)
bit 3 (0x08) = antibrouillard arrière
bit 4 (0x10) = antibrouillard avant
bit 5 (0x20) = feux route
bit 6 (0x40) = feux croisement   (mutuellement exclusif avec route)
bit 7 (0x80) = feux position
```

### Frein à main 0x128 byte[0] bit 5 (0x20)
- 1 = frein levé (activé)
- 0 = frein baissé

### Témoin warnings 0x128 byte[2] bit 1 (0x02)

### Portes 0x220 byte[0] (C2 3-portes)
```
bit 3 (0x08) = coffre / hayon
bit 6 (0x40) = AVD passager
bit 7 (0x80) = AVG conducteur
```
(bits 0,1,2,4,5 inutilisés sur C2 3-portes)

### RPM/vitesse 0x0B6
```
bytes 0-1 BE / 8 = RPM
bytes 2-3 BE / 100 = vitesse km/h
```
Vérifié à ralenti (~750 RPM) et 0 km/h à l'arrêt.

### T° extérieure 0x0F6 byte[4]
```
T_°C = (byte - 49) / 2
```
Confirmé à 23°C avec byte = 0x5F = 95 → (95-49)/2 = 23 ✓
**(Différent de la doc PSA générique qui donnait `b/2 - 39.5`)**

### Autonomie 0x221 byte[3..4] BE
```
range_km = int.from_bytes(data[3:5], 'big')
si == 0xFFFF → "non calculé"
```
Confirmé à 440 km avec bytes 0x01 0xB8 = 440 ✓

### Conso instantanée 0x221 byte[1..2] BE
```
l/100 = int.from_bytes(data[1:3], 'big') / 10
si == 0xFFFF → "non calculé"
```

### Trip 0x261 (slot 1)
```
byte[0]    = vitesse moyenne km/h
byte[2..3] BE / 10 = distance trip en 0.1 km
byte[4]    / 10 = conso moyenne en 0.1 l/100
```

## Décodeurs NON fonctionnels / non calibrés

| Signal | Statut | Raison |
|---|---|---|
| **Odomètre total** | ❌ pas trouvé | Aucun pattern correspondant à 110396 km dans aucun ID. Géré localement par le combiné. |
| **T° moteur** numérique | ❌ pas trouvé | Le combiné C2 n'affiche qu'une jauge (pas de valeur numérique). Pas de signal CAN trouvé. |
| **Niveau carburant %** | ❌ pas trouvé | 85% réel ne correspond à aucun byte sur les IDs candidats. |
| **Trip "884.0 km"** affiché | ❌ pas trouvé | Probablement calculé localement ou format inconnu. |
| **Heure BSI (via 0x276)** | ❌ N/A | 0x276 absent du LS.CONF AEE2004. |

## Tentatives de set d'heure — TOUTES ÉCHOUÉES

L'utilisateur voulait régler l'heure de l'écran multifonction central (MFD/EMF)
clignotante depuis le retrait du RD4. Méthodiquement testé sans aucune réaction :

- ❌ `0x39B` 5-byte (set BSI)
- ❌ `0x276` 7-byte (broadcast date) en single-shot, burst 5×, sustained 1Hz × 20s
- ❌ `0x228` 2-byte court
- ❌ `0x122` MENU avec présence radio idle
- ❌ `0x21F` bit 0 (LIST) brief + sustained 3s
- ❌ `0x21F` bits 4, 5 sustained 3s
- ❌ `0x167` BUTTON_ACTION (OUI/NON/ESC/RETOUR_VALEUR avec valeurs 15/45)
- ❌ Bouton extrémité commodo essuie-glace (appui long, propre à la C2) — chez ce user

**Diagnostic technique** : sur AEE2004, l'EMF est lui-même l'horloge mère
(émet 0x3F6). Le set se fait UNIQUEMENT via le menu interne EMF, navigué soit
par le commodo (si l'option est présente — pas le cas ici visiblement) soit
par une séquence de trames RD4→EMF dont seul le RD4 d'origine connaît le
protocole exact.

**Confirmé inutile** : Lexia / Diagbox / PP2000 ne savent pas régler l'horloge
EMF sur C2 (3 sources indépendantes : concession PSA, forums 207 et RCZ).

## Solutions futures pour le set d'heure

1. **Atoto comme horloge unique** (recommandé) — coût 0€, immédiat
2. **RD4 d'occase** (~30-80€) → branchement temporaire, set, retrait
3. **Émulateur RD4 ESP32** via [PSAVanCanBridge](https://github.com/morcibacsi/PSAVanCanBridge) — long terme
