# Playbook test sur la C2 — checklist pas-à-pas

Procédure complète pour valider la chaîne CAN (sniff, décodage, émission) sur
la vraie voiture. À suivre **dans l'ordre**, en cochant au fur et à mesure.

## Prérequis

- [ ] Multimètre + pinces croco
- [ ] Voiture accessible, batterie en forme (pas vidée)
- [ ] PC chargé avec ce projet, CANable + câbles dupont
- [ ] `docs/can/psa_can_confort.md` ouvert dans un autre onglet pour référence

---

## Étape 0 — Inspection CANable (avant tout branchement)

Avant même de connecter au véhicule, inspecter visuellement la carte CANable v2 :

- [ ] **Jumper TERM ENLEVÉ** (pas de cavalier sur les pins de terminaison). Si présent, la résistance 120 Ω du CANable s'ajoute à celles du véhicule (~60 Ω existants) → erreurs CAN garanties. **Retirer le cavalier avant de continuer.**
- [ ] **Jumper BOOT NON ponté**. Si ponté au moment du branchement USB, la puce démarre en mode DFU et `/dev/ttyACM0` ne sera pas créé.
- [ ] **LED POWER** s'allume fixe quand USB branché au PC = device alimenté OK.

Vérifier que tout fonctionne avec le PC (sans la voiture) :

```bash
ls /dev/ttyACM0           # doit exister
lsusb | grep -i canable   # doit montrer "MCS CANable2"
```

## Étape 1 — Raccord physique au véhicule

1. Contact OFF, dépose la façade autoradio (4 clips PSA + 2 vis selon millésime).
2. Identifier sur le faisceau ISO/PSA le **CAN-Confort** (pas l'OBD) :
   - Le câblage des autoradios RD3/RD4 expose CAN-H et CAN-L sur 2 broches
     du connecteur principal noir 16 voies (côté véhicule, pas côté radio).
   - Couleur typique : marron/jaune ou jaune/vert — **À VÉRIFIER au multimètre**.

3. **Vérification multimètre** (avant tout branchement CANable) :
   - Continuité ohmique entre H et L, voiture éteinte : **~60 Ω** = bus terminé OK.
     Si ~120 Ω : un seul terminateur, bizarre. Si infini : pas de bus là.
   - Voltage DC contact ON : **H ≈ 2.5 V**, **L ≈ 2.5 V**, différentiel proche de 0.

4. **Brancher le CANable EN PARALLÈLE** sur le bus (jamais en série) :
   - CAN-H voiture ↔ CANable H
   - CAN-L voiture ↔ CANable L
   - **GND** : optionnel si laptop sur batterie. Recommandé si laptop sur chargeur secteur (différence de potentiel possible).

5. Préférer des **T-tap / grabbers / dupont** sur les fils existants, **ne pas dénuder** le faisceau du véhicule.

6. Brancher le CANable au PC (USB-C). Vérifier la **LED CAN** : elle doit clignoter dès que le bus parle (contact ON véhicule).

---

## Étape 2 — Bring-up et health check

```bash
cd ~/CANCitroen
./scripts/can_up.sh 125000
./scripts/bus_health.py
```

**Verdict attendu** : `✅ BUS SAIN` avec ~50–200 trames/s, 10–30 IDs distincts,
mention `IDs PSA AEE2004 reconnus`.

### Troubleshooting

| Symptôme | Action |
|---|---|
| `❌ AUCUNE TRAME REÇUE` | Lance `./scripts/bus_health.py --auto-bitrate`. Si ça reste sec : permute physiquement H ↔ L. |
| `⚠️ ERREURS RX > 10` | Bitrate faux ou faux contact. Vérifie soudures / pinces. |
| Trames OK mais `Aucun ID PSA reconnu` | Tu es probablement sur le mauvais bus (peut-être l'IS à 500k via diag, ou un sous-bus partiel). Cherche un autre point de prélèvement. |
| Peu de trames, voiture en veille | Mets le contact ON, démarre le moteur, ou ouvre/ferme une porte pour réveiller la BSI. |

- [ ] **Étape 2 OK** — bus parle à 125k, IDs PSA reconnus.

---

## Étape 3 — Inventaire des IDs présents

```bash
./scripts/list_ids.py --live slcan0 --duration 20
```

Note la liste. Vérifier la présence des IDs critiques (cocher) :

- [ ] `0x036` (BSI état général)
- [ ] `0x0B6` (RPM/vitesse)
- [ ] `0x0F6` (T° moteur, odo, T° ext)
- [ ] `0x128` (feux/clignos/frein)
- [ ] `0x161` (carburant)
- [ ] `0x21F` (boutons volant) ← **CRITIQUE projet**
- [ ] `0x220` (portes)
- [ ] `0x221` (conso instantanée)
- [ ] `0x261` (trip)
- [ ] `0x276` (date/heure BSI)

Si certains manquent et que la voiture est contact ON moteur tournant :
- pour `0x21F` : appuie une fois sur un bouton volant pour le réveiller
- pour `0x0B6` : il faut moteur tournant
- pour les autres : c'est probablement spécifique à ton millésime/équipement

Capture une référence de session pour analyse offline plus tard :
```bash
./scripts/capture.sh field_test_session_$(date +%H%M) 30
```

- [ ] **Étape 3 OK** — on a une vue d'ensemble du bus.

---

## Étape 4 — Validation des décodeurs (lecture)

Lance le bridge + ouvre le dashboard :

```bash
./scripts/bridge.py
```

Dans un navigateur sur le PC : **http://localhost:8080/**

### Calibration interactive

Fais chaque action de la liste, vérifie le dashboard, coche.

| # | Action | Décodeur attendu | OK |
|---|--------|------------------|----|
| 1 | Contact ON sans démarrer | RPM=0, vitesse=0, T° moteur affiche valeur ambiante | ☐ |
| 2 | Démarre moteur | RPM ~800, T° moteur monte progressivement | ☐ |
| 3 | Clignotant gauche | indicateur "◀ CLIGNO G" jaune | ☐ |
| 4 | Clignotant droit | "CLIGNO D ▶" jaune | ☐ |
| 5 | Warnings (les 2) | les 2 indicateurs cligno alternent | ☐ |
| 6 | Allume veilleuses | "POSITION" allumé | ☐ |
| 7 | Codes (croisement) | "CROISEMENT" allumé (ET position aussi) | ☐ |
| 8 | Route (full) | "ROUTE" allumé | ☐ |
| 9 | Antibrouillard avant | "ANTIBR. AV" allumé | ☐ |
| 10 | Antibrouillard arrière | "ANTIBR. AR" allumé | ☐ |
| 11 | Frein à main serré | "FREIN À MAIN" orange | ☐ |
| 12 | Frein à main relâché | indicateur éteint | ☐ |
| 13 | Ouvre porte conducteur | "PORTE AVG" rouge | ☐ |
| 14 | Ouvre porte passager | "PORTE AVD" | ☐ |
| 15 | Ouvre coffre | "COFFRE" | ☐ |
| 16 | Conduis 2 m | vitesse change | ☐ |
| 17 | Accélère un peu | RPM monte | ☐ |
| 18 | Vérifier T° extérieure | valeur cohérente avec la météo | ☐ |
| 19 | Vérifier odomètre | valeur = compteur tableau de bord ± 1 km | ☐ |
| 20 | Vérifier heure BSI | = horloge tableau de bord à la minute | ☐ |

### Si un décodeur affiche faux

1. Ouvre `./scripts/sniff_changes.py --only <ID-suspect>` dans un autre terminal
2. Refais l'action, observe les payloads bruts
3. Compare au décodeur dans `scripts/bridge.py` fonctions `decode_XXX(data)`
4. Corrige (souvent un facteur d'échelle ou un offset différent du standard PSA)
5. Relance le bridge, retest

**Capture les bizarreries pour référence** :
```bash
./scripts/capture.sh decoder_anomaly_<nom> 10  # pendant que tu reproduis
```

- [ ] **Étape 4 OK** — tous les décodeurs validés ou notés comme à corriger.

---

## Étape 5 — Identification des boutons volant

L'objectif : confirmer le bitmap `0x21F` byte 0 pour TA C2.

```bash
./scripts/button_isolator.py vol_plus --cycles 4 --idle 3 --press 2
```

Suis les instructions interactives (alternance IDLE / PRESS).

**Verdict attendu** : un seul candidat parfait du type :
```
0x21F  byte[0] = 0x04
```

Répète pour chaque bouton du volant de TA C2 (skip ceux qu'elle n'a pas) :

| Bouton | Commande | Bitmap attendu (cousines PSA) | Trouvé sur C2 |
|--------|----------|-------------------------------|---------------|
| VOL+ | `./scripts/button_isolator.py vol_plus` | `0x04` | ☐ |
| VOL- | `./scripts/button_isolator.py vol_moins` | `0x08` | ☐ |
| MUTE | `./scripts/button_isolator.py mute` | `0x0C` | ☐ |
| SOURCE | `./scripts/button_isolator.py source` | `0x02` | ☐ |
| TEL ↑ (si présent) | `./scripts/button_isolator.py tel_on` | `0x10` | ☐ |
| TEL ↓ (si présent) | `./scripts/button_isolator.py tel_off` | `0x20` | ☐ |

### Tests complémentaires

- [ ] **Auto-répétition** : maintient VOL+ 3s. Le byte 0 reste à 0x04 en
      continu, ou retombe à 0 entre chaque pulse ? Note dans
      `docs/can/psa_can_confort.md`.
- [ ] **Byte 1 (scrollValue)** : la C2 a-t-elle une molette source ? Si non,
      byte 1 doit rester à 0.
- [ ] **Bouton OK / SELECT** (si présent sur ton commodo) : isole-le.

### Si bitmap différent du standard PSA

Mets à jour les constantes dans :
- `scripts/bridge.py` → variable `BUTTON_BITMAP`
- `scripts/send_button.py` → idem
- `docs/can/psa_can_confort.md` → tableau des bitmaps

- [ ] **Étape 5 OK** — bitmaps boutons volant C2 documentés.

---

## Étape 6 — Test émission (write)

⚠️ **Avant de continuer**, relire `docs/app/atoto_a6pf_integration.md` section
"Risques" : conflit d'IDs, risque bus-off.

### 6.1 Sanity check : la TX marche ?

```bash
ip -s link show slcan0 | grep -A1 TX
```

Note les compteurs `packets`, `errors`. Émets une trame de test :

```bash
cansend slcan0 7FF#00
```

(`0x7FF` = ID très prioritaire bas, aucun ECU PSA n'écoute là, pas d'effet
même si transmis.)

```bash
ip -s link show slcan0 | grep -A1 TX
```

`packets` devrait avoir incrémenté de 1, `errors` à 0 → émission OK et
**ACK reçu** d'un autre ECU sur le bus (preuve qu'on est bien sur un bus actif).

Si `errors` incrémente de 1 → pas d'ACK, soit bus mort soit isolé.

- [ ] **6.1 OK** — TX fonctionnelle.

### 6.2 Test set horloge BSI (ID 0x39B)

D'abord, dry-run pour voir ce qu'on enverrait :
```bash
./scripts/set_clock.py --datetime "2026-04-26 16:30" --dry-run
```

Note l'heure actuelle au combiné. Puis envoi réel :
```bash
./scripts/set_clock.py --datetime "2026-04-26 16:30"
```

**Attendu** : l'horloge du combiné passe à 16:30 dans les 1-3 secondes.

**Si rien ne change** :
- Ta C2 est probablement < 2005 (RD3 → BSI ignore les sets CAN)
- OU la BSI a besoin de la trame 0x228 ou 0x276 en plus → on peut investiguer
- Vérifie avec `./scripts/sniff_changes.py --only 276` que la BSI continue
  d'émettre 0x276 avec son ancienne heure (= ignore notre set) ou la nouvelle
  (= a accepté mais pas affiché)

Remet l'heure à l'heure réelle :
```bash
./scripts/set_clock.py     # utilise l'heure système courante
```

- [ ] **6.2 OK** — ou noté comme "BSI ignore le set" si C2 ancienne.

### 6.3 Test bouton volant émis (uniquement si autoradio sur le bus)

⚠️ **Skip cette étape** si tu n'as PAS d'autoradio actuellement connecté au
bus (RD4 d'origine retiré, Atoto pas encore branché côté CAN). Sans
récepteur, ça n'a aucun effet visible.

Si l'Atoto (ou autre récepteur) écoute le bus :
```bash
./scripts/send_button.py vol+
./scripts/send_button.py source
```

**Attendu** : ton autoradio réagit comme si tu avais appuyé physiquement.

- [ ] **6.3 OK** ou **N/A** (skip si pas de récepteur).

### 6.4 Émission raw via l'API HTTP du bridge

Avec le bridge tournant :
```bash
curl -X POST http://localhost:8080/api/send \
  -H "Content-Type: application/json" \
  -d '{"id":"21F","data":"02 00 00"}'   # SOURCE
```

Pareil avec le bouton "Régler heure BSI (now)" du dashboard.

- [ ] **6.4 OK** — API émission fonctionnelle.

---

## Étape 7 — Capture longue de référence

Avant de tout démonter, fais un dataset complet pour analyse offline :

```bash
./scripts/capture.sh full_session_$(date +%Y%m%d_%H%M) 600
```

10 minutes de trafic divers (rouler un peu, pousser des boutons, allumer/éteindre
des trucs). Servira pour :
- Tester le bridge en mode `--replay` quand tu seras chez toi
- Identifier des trames non encore décodées plus tard
- Datapoint pour comparer après modification d'un décodeur

- [ ] **Étape 7 OK** — capture longue archivée dans `captures/`.

---

## Étape 8 — Cleanup

```bash
./scripts/can_down.sh
```

Débranche le CANable. Remonte la façade autoradio.

---

## En sortie de cette session

Tu dois avoir validé :

- ☐ Inspection matériel CANable (jumpers, LEDs) (étape 0)
- ☐ Bus C2 parle à 125 kbit/s (étape 2)
- ☐ IDs PSA AEE2004 présents (étape 3)
- ☐ Décodeurs bridge.py corrects ou corrigés (étape 4)
- ☐ Bitmaps boutons volant 0x21F documentés pour TA C2 (étape 5)
- ☐ Émission TX OK (étape 6.1)
- ☐ Set horloge BSI fonctionne ou noté comme N/A (étape 6.2)
- ☐ Capture longue de référence (étape 7)

À ce stade, **la phase de découverte est terminée**. Le bridge est calibré
sur ta voiture, les protocoles d'émission sont validés. On peut passer à :

- **Phase 2** : porter le bridge sur l'Atoto via Termux pour valider en
  conditions réelles dans la voiture (sans laptop)
- **Phase 3** : implémentation finale (app Android dédiée OU ESP32-S3
  passerelle USB HID + série)

Voir `docs/app/atoto_a6pf_integration.md` pour les détails des phases suivantes.
