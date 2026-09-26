# Signaux CAN-Confort décodés — référence

Référence **exacte** de ce que l'app et le bridge décodent aujourd'hui, avec le
niveau de preuve de chaque signal. Elle reflète le code :

- `android/app/src/main/java/com/geoffrey/cancitroen/decode/CanDecoder.kt` (app) ;
- `scripts/bridge.py`, fonctions `decode_XXX` (PC) ;
- les deux sont tenus alignés par `fixtures/can_decode_cases.tsv` (voir
  [développement](../app/developpement.md#tests)).

Bus : **CAN-Confort PSA AEE2004, 125 kbit/s**, identifiants 11 bits, accessible
derrière l'autoradio (Quadlock pin 10 CAN-H / pin 13 CAN-L). Octets numérotés
à partir de 0, bit 0 = poids faible, « BE » = gros-boutiste.

## Niveaux de preuve

| | Signification |
|---|---|
| ✅ | Vérifié sur la C2 : action physique ↔ bit qui change (field test 2026-04-26), ou valeur confirmée par l'utilisateur |
| 🎯 | Calé sur **un seul** point de mesure (Lexia/Diagbox) : juste à ce point, **pente non vérifiée** |
| 📄 | Repris d'une structure de référence (PSAVanCanBridge AEE2004), pas vérifié sur la C2 |
| ⚠ | Sources contradictoires — voir [points ouverts](#points-ouverts) |

Sentinelles : `0xFF` (températures) et `0xFFFFFF` (odomètre) signifient « pas
encore disponible » (BSI au réveil) et donnent `null`, jamais une valeur.

## Trames reçues (RX)

### `0x036` — BSI : allumage, luminosité (DLC ≥ 5) 📄

| Octet.bit | Champ app | Décodage |
|---|---|---|
| 2.7 | `economyMode` | délestage (mode économie) actif |
| 3.0-3 | `dashboardBrightness` | 0-15 |
| 3.4 | `blackPanel` | |
| 3.5 | `nightMode` | |
| 4.0-2 | `ignitionMode` | 0 STANDBY, 1 NORMAL, 2 STANDBY_SOON, 3 WAKE_UP, 4 COM_OFF |

### `0x0B6` — régime, vitesse (DLC ≥ 4 ; bridge : ≥ 8)

| Octets | Champ app | Décodage | Preuve |
|---|---|---|---|
| 0-1 BE | `rpm` | ÷ 8, arrondi 0,1 tr/min | ✅ ralenti 704-768 tr/min (Lexia) |
| 2-3 BE | `speed` | ÷ 100, arrondi 0,1 km/h | ✅ à l'arrêt ; en roulant à comparer au GPS (affiché par l'app) |
| 4-5 BE | — (bridge : `trip_dist_cmb_raw`) | brut, unité inconnue | 📄 |
| 6 | — (bridge : `cons_cmb_raw`) | compteur injecteur | 📄 |

Émise à 20-50 Hz : c'est elle qui fixe la cadence de l'état brut (l'UI
l'échantillonne à 10 Hz, l'audio la suit trame par trame).

### `0x0F6` — contact, températures, odomètre (DLC 8)

| Octets | Champ app | Décodage | Preuve |
|---|---|---|---|
| 0.3-4 | `keyPosition` / `contact` | 0 STOP, 1 CONTACT, 2 STARTER, 3 FREE ; `contact` = ≠ 0 | 📄 |
| 1 | `tCoolant` | octet − 53 °C ; 0xFF → null | 🎯 92 °C ↔ 0x91 (T° variait 86-92 °C pendant la mesure) |
| 2-4 BE | `odo` | ÷ 10 km ; 0xFFFFFF → null | ⚠ |
| 6 | `tExt` | octet − 102 °C ; 0xFF → null ; + décalage utilisateur (Réglages) | ⚠ |
| 7.7 | `reverseGear` | marche arrière | 📄 |
| 7.6 | `wiperActive` | essuie-glace | 📄 |
| 7.0 / 7.1 | — (bridge : clignotants, redondants avec 0x128) | | 📄 |

### `0x128` — combiné : feux, frein à main, témoins (DLC 8)

| Octet.bit | Champ app | Preuve |
|---|---|---|
| 0.5 | `handbrake` | ✅ |
| 2.1 | `warningsOn` (feux de détresse) | ✅ |
| 4.1 / 4.2 | `lights.clignoG` / `clignoD` | ✅ |
| 4.3 / 4.4 | `lights.antibrouillardAr` / `antibrouillardAv` | ✅ |
| 4.5 / 4.6 / 4.7 | `lights.feuxRoute` / `feuxCroisement` / `feuxPosition` | ✅ |
| 4.0 | `lights.drl` (feux de jour) | 📄 |
| 0.4 | `fuelLowWarning` et `warn.fuelLow` (réserve) | 📄 |
| 0.6 / 0.1 | `warn.driverBelt` / `passengerBelt` | 📄 |
| 0.7 | `warn.passengerAirbagOff` | 📄 |
| 0.2 | `warn.dieselPreheat` | 📄 |
| 1.1 / 1.7 | `warn.absActive` / `serviceExclamation` | 📄 |
| 1.3 / 1.4 | `warn.doorOpenAbove10` / `doorOpenBelow10` | 📄 |
| 2.1 / 2.3 / 2.4 | `warn.warningActive` / `espInProgress` / `espInactivated` | 📄 |
| 6.4-7 | `gearCmb` : P R N D 6 5 4 3 2 1 (10-14 « - », 15 = aucun) | 📄 (boîte manuelle sur la C2) |

### `0x161` — huile, carburant (DLC ≥ 7)

| Octet | Champ app | Décodage | Preuve |
|---|---|---|---|
| 2 | `tOil` | octet − 64 °C ; 0x00 / 0xFF → null | 🎯 71 °C ↔ 0x86 (donne 70) |
| 3 | `fuelPct` | % direct | ✅ retour utilisateur 2026-05-09 |
| — | `fuelLitersEst` | % × capacité réservoir (réglage, 41 L par défaut), arrondi 0,1 | calcul |
| 6 | `oilLevelPct` | % direct | 📄 |
| — | `oilLevelLitersEst` | % × 3,0 L (TU1JP), arrondi 0,01 | calcul |

### `0x167` — page EMF (DLC ≥ 4) 📄

| Octets | Champ app | Décodage |
|---|---|---|
| 0.0-2 | `emfPage` | 0 NONE, 1 GENERAL, 2 TRIP1, 4 TRIP2, 7 NOT_MGD |
| 2-3 BE | `tripDistTotal` | brut |

### `0x168` — alertes moteur (DLC ≥ 2 ; bridge : ≥ 3) 📄

| Octet.bit | Champ app |
|---|---|
| 0.2 | `warn.brakeFluidAlert` |
| 0.3 / 0.4 | `warn.oilPressureAlert` / `oilLevelAlert` |
| 0.5 | `warn.coolantLevelAlert` |
| 0.6 / 0.7 | `warn.oilTempMax` / `coolantTempMax` |
| 1.0 / 1.2 | `warn.maxRpm2` / `maxRpm1` |
| 1.3 | `wiperAuto` |
| 1.4 | `warn.fapClogged` |
| 1.6 / 1.7 | `warn.tyrePunctured` / `tyrePressureLow` |

### `0x21F` — commandes au volant (DLC ≥ 1) ✅

Objectif initial du projet, entièrement vérifié sur la voiture. **Le câblage
de cette C2 inverse VOL+ et VOL−** par rapport à la documentation PSA générique.

| Octet.bit | Bouton |
|---|---|
| 0.1 | SOURCE |
| 0.2 | VOL− |
| 0.3 | VOL+ |
| 0.2 + 0.3 | MUTE (convention de l'app) |
| 0.6 | PRÉCÉDENT |
| 0.7 | SUIVANT |
| 0.0, 0.4, 0.5 | sans effet sur cette C2 |
| 1 | compteur de molette cumulatif ; l'app calcule le delta signé entre deux trames (`wheelScrollDelta`) |

L'app expose aussi `wheelButtonRaw` (octet 0 brut) et `wheelButton` (libellé).
Traitement : front montant + anti-rebond 200 ms, voir
[architecture](../app/architecture.md#commandes-au-volant).

### `0x220` — ouvrants (DLC ≥ 1)

| Octet.bit | Champ app | Preuve |
|---|---|---|
| 0.7 | `doors.avg` (conducteur) | ✅ |
| 0.6 | `doors.avd` (passager) | ✅ |
| 0.3 | `doors.coffre` | ✅ |
| 0.5 / 0.4 | `doors.arg` / `ard` (absents sur la C2 3 portes) | 📄 |
| 0.2 / 0.1 / 0.0 | `doors.capot` / `vitreAr` / `trappeCarb` | 📄 |

### `0x221` — consommation, autonomie (DLC ≥ 5)

| Octets | Champ app | Décodage | Preuve |
|---|---|---|---|
| 1-2 BE | `fuelInst` | ÷ 10 l/100 ; 0xFFFF → null (non calculée à l'arrêt) | 📄 (0xFFFF à l'arrêt cohérent avec Lexia) |
| 3-4 BE | `rangeKm` | km ; 0xFFFF → null | ✅ 440 km (trame `00 FF FF 01 B8 FF FF`) |

### `0x261` — ordinateur de bord, trajet 1 (DLC ≥ 5)

| Octets | Champ app | Décodage | Preuve |
|---|---|---|---|
| 0 | `tripAvgSpeed` | km/h | 📄 |
| 2-3 BE | `tripDist` | ÷ 10 km | ⚠ les « 884 km » affichés n'ont pas été retrouvés |
| 4 | `tripAvgCons` | ÷ 10 l/100 | ✅ 7,6 l/100 (Lexia) ↔ 0x4C |

`0x2A1` (trajet 2) est déclaré dans `CanIds.kt` mais **pas décodé**.

### `0x276` — date BSI (DLC ≥ 5)

`datetimeBsi` = `1872 + octet0`-`octet1`-`octet2` `octet3`:`octet4`. Trame
**absente du CAN-Confort de cette C2** (field test) : le décodeur ne sert que si
elle apparaît.

### `0x3A7` — entretien (DLC ≥ 7) 📄

| Octets | Champ app | Décodage |
|---|---|---|
| 0.7 | `maintDue` | entretien dû |
| 3-4 BE | `maintKmRemaining` | km avant entretien |
| 5-6 BE | `maintDaysRemaining` | jours avant entretien |

Valeurs Lexia du 2026-05-03 pour comparaison : 15 560 km, 691 jours.

## Trames émises (TX)

Toute émission part sur le bus réel de la voiture.

| ID | Émetteur | Contenu | État |
|---|---|---|---|
| `0x3E5` | app (`EmfController`), `scripts/emf_keyboard_nav.py`, bridge `/api/emf/*` | 6 octets de boutons EMF ; `00×6` toutes les 65 ms tant que le mode est actif, une trame avec le bit du bouton par appui | fonctionne **si la BSI est télécodée « RD4 présent »**, sinon l'EMF ignore |
| `0x39B` | `scripts/set_clock.py`, bridge `/api/clock` | 5 octets : année − 1872, mois, jour, heure, minute | ❌ sans effet sur l'horloge de l'EMF (voir [psa_can_confort.md §3.c](psa_can_confort.md)) |
| `0x21F` | `scripts/send_button.py`, bridge `/api/button` | bitmap boutons volant | test uniquement (autoradio sur le bus) |

Boutons `0x3E5` (source : PSAWifiDisplayControl `CanMenuStructs.h`) :

| Octet | Bits |
|---|---|
| 0 | 0 AIRCON, 4 PHONE, 6 MENU |
| 1 | 4 MODE, 6 TRIP |
| 2 | 2 DARK, 4 ESC, 6 OK |
| 5 | 0 LEFT, 2 RIGHT, 4 DOWN, 6 UP |

## Points ouverts

À vérifier à la prochaine session voiture (ajouter chaque point confirmé à
`fixtures/can_decode_cases.tsv`, puis mettre à jour ce tableau) :

1. **T° extérieure** ⚠ — trois hypothèses, toutes calées sur le même point à
   23 °C :
   - code actuel : octet 6 − 102 (pente 1 °C/bit) ;
   - field test 2026-04-26 : (octet 4 − 49) / 2 ;
   - ludwig-v (arduino-psa-comfort-can-adapter, trame 0xF6) : (octet 5 >> 1) − 40.

   La pente PSA habituelle est de 0,5 °C/bit : avec `− 102`, l'écart atteint
   ~6 °C à 10 °C réels. **Procédure** : capturer 0x0F6 (`scripts/capture.sh`)
   à deux températures extérieures éloignées (matin froid / après-midi), noter
   la valeur affichée par l'EMF, choisir la formule qui colle aux deux points.
2. **Odomètre** ⚠ — `octets 2-4 / 10` vient de la structure PSAVanCanBridge ;
   le field test du 2026-04-26 n'avait trouvé aucun motif correspondant au
   kilométrage, et le point Lexia noté dans le code était incohérent
   (0x10D85F = 1 103 967, pas 1 103 455). **Procédure** : relever le compteur
   du combiné au départ et à l'arrivée d'un trajet et comparer à `odo`.
3. **Pentes T° moteur / huile** 🎯 — un seul point chacune. Comparer à Lexia
   moteur froid puis chaud.
4. **Vitesse en roulant** — comparer `speed` au GPS (l'accueil affiche les deux).
5. **Entretien `0x3A7`** — comparer aux valeurs Lexia (15 560 km / 691 j).
6. **Distance trajet `0x261`** — format non confirmé.

Signaux **introuvables** sur ce bus à ce jour : tension batterie, T° admission,
heure BSI (`0x276` absente), horloge EMF (non réglable par CAN).
