# CAN-Confort PSA AEE2004 — IDs et payloads

> Référence PSA **générique**. Sur cette C2, certains bits diffèrent (VOL+ et
> VOL− inversés sur `0x21F`, par exemple) : ce que l'app décode réellement est
> dans [signaux_decodes.md](signaux_decodes.md).

> **Plateforme** : C2 2003-2009, BSI gen 2 (AEE2004), RD3 (analogique) ou RD4 (CAN).
> Les IDs ci-dessous sont à >90% transposables depuis les cousines (207, C3 phase 1,
> C4 phase 1, 1007, 307, 407) qui partagent la même BSI gen2.
> Sources : projet [ludwig-v/arduino-psa-comfort-can-adapter](https://github.com/ludwig-v/arduino-psa-comfort-can-adapter),
> catalogue [prototux/PSA-RE](https://github.com/prototux/PSA-RE), doc [autowp.github.io](https://autowp.github.io/).

## Bitrate
- **CAN-Confort** : 125 kbit/s — ta cible
- CAN-IS (moteur/ABS/airbag) : 500 kbit/s — hors scope ici

---

## 1. Lecture passive de valeurs

| ID | Émetteur | Période | Contenu (octets) |
|----|----------|---------|------------------|
| **0x036** | BSI | 200 ms | b0 bits 0-3 = mode éco / éclairage tableau ; b0 bits 4-7 = luminosité ; b1 bits 0-2 = état contact (001=ON, 010=OFF) |
| **0x0B6** | BSI (relayé CMM) | 50-200 ms | b0-b1 = **régime moteur** (RPM, big-endian) ; b2-b3 = **vitesse × 100** km/h ; b4-b5 = distance depuis démarrage en cm ; b6 = compteur conso (impulsions injecteur) |
| **0x0F6** | BSI | 500 ms | b0 bit 7 = contact ; b0 bits 0-6 = **T° liquide refroid** = valeur − 39 °C ; b1-b3 = **odomètre** total ; b4 = **T° extérieure** filtrée (T = valeur/2 − 39.5 °C) ; b5-b7 = clignos / marche AR |
| **0x128** | BSI | 200 ms | bitmap : ceintures, frein à main, **portes ouvertes**, feux croisement/route, antibrouillards, clignos, témoin réserve carburant |
| **0x161** | BSI | 1 s | T° / **niveau carburant** |
| **0x220** | BSI | 500 ms | b0 bits 0-4 = portes AVG, AVD, ARG, ARD, coffre (1=ouvert) |
| **0x221** | BSI | 500 ms | **Conso instantanée** (l/100 = valeur/10), autonomie |
| **0x261** | BSI | 1 s | Vitesse moy, distance trajet, conso moy (slot 1) |
| **0x2A1** | BSI | 1 s | Idem 0x261 (slot 2) |
| **0x336 / 0x3B6 / 0x2B6** | BSI | 1 s | **VIN** ASCII en 3 morceaux (3+6+8 octets) |
| **0x3A7** | BSI | 1 s | Maintenance / révision (km restant) |

> Tension batterie : pas dispo cleanly sur AEE2004 confort, passe par UDS sur la BSI.

---

## 2. Commandes au volant — **`0x21F`** (et PAS 0x131 !)

**Correction par rapport à mes notes initiales** : `0x131` est émis par la RADIO vers le chargeur CD, ce n'est pas le volant. Le bon ID est **`0x21F`, émis par le HDC** (boîtier commodo de colonne).

```
ID = 0x21F   DLC = 3   événementiel + heartbeat
b0 = bitmap touches
b1 = scrollValue (cumul molette source ; 0 sur C2 si pas de molette)
b2 = 0x00
```

**Bitmap b0** (à confirmer au sniffer sur ta C2 — le HDC C2 a moins de touches que les cousines plus haut de gamme) :

| valeur b0 / bit | Touche |
|-----------------|--------|
| `0x04` | **VOL+** |
| `0x08` | **VOL-** |
| `0x0C` (= 0x04 ∨ 0x08) | **MUTE** (combo VOL+ ∧ VOL−) |
| bit 1 (`0x02`) | **MODE / SRC** |
| autres bits | TEL / autres selon équipement |

> Méthode : `./scripts/button_isolator.py vol_plus --only 21F` puis `vol_minus`, `source`, `mute`, etc.

Trame **`0x122`** (séparée) : touches MENU/EMF de l'écran central. b0 = 0x80 si MENU appuyé.

---

## 3. Affichage écran + horloge BSI

### 3.a Popups / messages combiné — `0x1A1`

Émis par BSI → COMBINE/EMF, DLC 8 :

```
b0 : high(message_id) ; bit 7 = 1 → afficher, 0 → fermer popup
b1 : low(message_id)   ; (0x7F 0xFF) = clear all
b2 : priorité 0-14 ; bit 7 = destination NAC/EMF, bit 6 = combiné
b3-b7 : paramètres du message
```

Codes message connus : `0x00` Diagnosis OK, `0x01` Engine temp high, `0x61` Frein à main, `0xE0` Carburant bas, etc. Liste complète sur autowp.github.io.

### 3.b Texte radio (artiste/titre) — `0x125`

Multi-frame ISO-TP 15765-2, jusqu'à 20 octets ASCII par bloc, émis par RADIO, lu par le combiné/écran.

### 3.c **Horloge / date — CORRECTION du modèle mental après field test C2**

> ⚠️ **Les pistes 0x39B / 0x276 / 0x228 sont INVALIDES sur AEE2004 LS.CONF (confort)**.
> Elles n'existent que sur AEE2010 / CAN2010 (NAC, SMEG, plus récents).
> Vérifié : la C2 testée le 2026-04-26 ignore complètement ces 3 IDs.

**Modèle correct (validé par prototux/PSA-RE) :**

- L'**EMF (écran multifonction central) est l'horloge mère** du véhicule sur AEE2004,
  PAS la BSI. La BSI est consommatrice.
- L'EMF broadcaste son heure via **`0x3F6`** (CONFIG_DISPLAY) à 1 Hz, DLC 7.
- Émetteurs `0x3F6` : EMF, BTEL, CMB_EMF, NG4, RNEG.
- Récepteurs : BSI, CMB, VTH, KML, MDS, MATT, CDC, BGP.

**Pour régler l'EMF, deux voies natives** :
1. **Bouton extrémité du commodo essuie-glace** : appui long ~2-3s → ouvre menu EMF
   (réglage heure/date via molette + appui court). Disponible selon niveau de finition.
2. **Via le RD4 d'origine** : Source long-pressed → Menu → Heure → validation.
   Le RD4 émet alors une séquence (probablement via 0x167 BUTTON_ACTION + VALEUR)
   qui pousse les valeurs dans l'IHM EMF ouverte.

**Ce qui NE fonctionne pas** (testé sur C2 le 2026-04-26) :
- 0x39B, 0x276 (5 et 7 bytes), 0x228 → IDs absents de LS.CONF AEE2004
- 0x21F bit 0 (LIST) seul → n'ouvre pas le menu EMF (le RD4 émet probablement
  d'autres trames de présence en plus)
- 0x167 BUTTON_ACTION (OUI/NON/ESC/RETOUR_VALEUR) seul → l'EMF ignore si aucun
  menu n'est ouvert
- **Lexia/Diagbox/PP2000** → confirmé non fonctionnel par 3 sources indépendantes
  (concession PSA, forums 207/RCZ). Aucun DID horloge documenté pour l'EMF.

**Format `0x3F6` (EMF broadcast — informatif, à lire seulement)** :
```
DLC 7, période 1000 ms, émis par l'EMF
TIMESTAMP_SECS  (bits 1.7-3.4)  : secondes mod 86400
TIMESTAMP_DAYS  (bits 3.3-4.0)  : jours mod 365
TIMESTAMP_YEARS (bits 5.7-5.0)  : années mod 100
CONFIG_DISPLAY_TIME (12h/24h)
CONFIG_TEMP_UNIT, CONFIG_DISTANCE_UNIT, CONFIG_LANGUAGE
```

### 3.d Voies pour set d'heure si la procédure native échoue

Si le commodo essuie-glace n'a pas le bouton ou que l'EMF reste sourd :

1. **Trouver un RD4 d'occase** (~30-80€), le brancher temporairement sur le bus
   en parallèle, régler via son menu, puis le retirer. L'EMF mémorise.
2. **Émulateur RD4 complet** : projet [`morcibacsi/PSAVanCanBridge`](https://github.com/morcibacsi/PSAVanCanBridge)
   reproduit la séquence complète RD4↔EMF. À porter sur ESP32 + transceiver CAN
   pour intégration permanente.
3. **Pragmatique** : ignorer l'horloge EMF, utiliser l'écran de l'autoradio
   aftermarket (Atoto / autre) qui a NTP / GPS.

---

## Projets de référence (cross-check des payloads)

- **[ludwig-v/arduino-psa-comfort-can-adapter](https://github.com/ludwig-v/arduino-psa-comfort-can-adapter)** — meilleure source de vérité (code C++ avec toutes les trames CAN-Confort)
- **[prototux/PSA-RE](https://github.com/prototux/PSA-RE)** — catalogue exhaustif IDs PSA
- **[autowp.github.io](https://autowp.github.io/)** — doc HTML lisible avec payloads octet par octet
- **[ludwig-v/arduino-psa-diag](https://github.com/ludwig-v/arduino-psa-diag)** — pour le diag UDS si besoin de tension batterie / DTC
- **[kurkpitaine/stellantis-can-adapter](https://github.com/kurkpitaine/stellantis-can-adapter)** — réécriture Rust du projet ludwig-v
- **[iDoka/awesome-automotive-can-id](https://github.com/iDoka/awesome-automotive-can-id)** — méta-liste
- **[Hackaday — RE Peugeot 207 CAN](https://hackaday.com/2017/05/04/reverse-engineering-the-peugeot-207s-can-bus/)** — narratif, 207 ≈ C2 côté confort
