# Découvertes Lexia/Diagbox — session 2026-05-03

> **Journal de session.** Les valeurs de vérité ci-dessous restent valables ;
> les décodages cités datent du 2026-05-03. État actuel et points à vérifier
> (T° extérieure, odomètre) : [signaux_decodes.md](signaux_decodes.md).

Analyse des screenshots Lexia capturés sur la C2 du user. **Données ground-truth
précieuses** pour la prochaine session de calibration CAN.

## Identification ECUs

### ECRAN_A (= EMF, écran multifonction central)
- **PSA reference** : `96 597 970 77`
- **Supplier** : **MAGNETI MARELLI**
- **Software version** : `007.016`
- **Diagnostic message system** : `96 586 891 99`

→ Cet EMF est le bloc qui clignote l'heure. Magneti Marelli signifie qu'il a
son propre firmware spécifique. Différent des EMF Borg/Johnson Controls cités
dans la doc agent.

### J34P (= ECU moteur / injection)
- Supplier : JCAE
- ECU reference : `9651696680`
- Software ref : `9663255180`
- Software version : `0B02`

### BSI / BSM
Présents et accessibles. Pas d'identification capturée mais menus disponibles.

## 🔥 LA DÉCOUVERTE — Boutons EMF monitorables en LIVE

Page **`ECRAN_A → Parameters → Multifunction display control status`** liste 9 boutons
suivis par Diagbox en temps réel :

| Bouton EMF | Origine probable |
|---|---|
| ESC | RD4 d'origine |
| MODE | RD4 d'origine |
| MENU | RD4 d'origine |
| OK | RD4 d'origine |
| To-the-left | RD4 d'origine |
| To-the-right | RD4 d'origine |
| Downwards | RD4 d'origine |
| Upwards | RD4 d'origine |
| **"Trip computer" switch** | **Bouton extrémité commodo essuie-glaces** |

Tous à "No" dans le screenshot car aucun bouton pressé à l'instant.

### Implication critique pour le projet

Lexia LIT ces états quelque part. Soit :
- **Sur le bus CAN** → on peut les trouver en sniffant pendant que les boutons sont pressés
- **Sur des fils dédiés à l'EMF** (pas CAN) → impossible à émuler sans modif hardware

**Test à faire en prochaine session** :
1. Brancher le CANable + lancer le bridge avec capture continue
2. Ouvrir Lexia simultanément sur cette page "Multifunction display control status"
3. Appuyer sur le bouton commodo essuie-glaces (Trip computer switch)
4. Observer SIMULTANÉMENT :
   - Lexia : "Trip computer switch" passe-t-il à "Yes" ?
   - CANable capture : quel ID/byte change au même instant ?
5. Idem pour les autres boutons (avec un RD4 si disponible, sinon impossible de tester)

**Si Trip computer switch reste à "No" dans Lexia même avec appui** → bouton commodo
physiquement défaillant (mauvais contact, fil cassé). Explique pourquoi notre
émulation CAN n'a pas marché : la BSI ne reçoit même pas le signal originel.

## 📊 Ground truth values — pour calibrer les décodeurs CAN

Capturés moteur tournant pendant la session Lexia :

| Champ | Valeur réelle | Encodage à chercher |
|---|---|---|
| **T° liquide refroidissement** | 86-92°C (varie) | byte = T - X (offset à trouver) |
| **T° huile** | 64-72°C | idem |
| **T° admission** | 47-48°C | idem |
| **T° extérieure** | 23°C ✅ | `(b - 49) / 2` confirmé sur `0x0F6 byte[4]` |
| **Niveau carburant brut** | 22.70 L | sur réservoir 41L |
| **Niveau carburant affiché** | 22.58 L | |
| **Impédance jauge carburant** | 162 Ω | |
| **Capacité réservoir** | 41 L | constante |
| **Plein détecté** | 54 Ω | seuil bas impédance |
| **Loi jauge carburant** | Law N°5 | |
| **Type carburant** | Petrol | |
| **Odomètre total** | **110506.9 km** ← ODO REEL | pas en clair sur le bus CONF (déjà cherché et pas trouvé) |
| **Avant maintenance** | 15560 km | |
| **Vidanges effectuées** | 7 | |
| **Km au dernier reset** | 8228.0 km | |
| **Durée depuis reset** | 1421 jours (~3.9 ans) | |
| **Jours avant maintenance** | 691 jours | |
| **Conso moyenne** | **7.6 L/100** ✅ | confirmé `0x261 byte[4]/10` |
| **Conso lissée** | 6.4 L/100 | différent de la conso moyenne |
| **Conso instantanée** | --- (moteur OFF à ce moment) | |
| **RPM ralenti** | 704-768 ✅ | confirmé `0x0B6 bytes[0..1] BE / 8` |
| **Tension batterie** | 12.30 V (OFF) → 14.10-14.20 V (ON) | non trouvé sur CONF jusqu'ici |
| **Niveau huile mesuré** | 52-62% | |
| **Avertissement pression huile** | No (off) → Yes (in BSM screen) | |
| **Pression admission** | 333 mbars (ralenti) | |
| **Tension papillon** | 608 mV (ralenti, 12% ouvert) | |

### Fait important sur la consommation
- "Conso instantanée" = "---" sur le screenshot → BSI/J34P n'émet PAS la conso instant en stationnaire
- Notre `0x221 byte[1..2]` = `FF FF` = "non calculé" cohérent
- Doit apparaître en roulant

## 📌 État du télécodage post-session

Le user a **réactivé le télécodage RD4** dans la BSI via Diagbox.
Conséquences attendues (à vérifier prochaine session) :
- L'EMF doit retourner en mode "écoute trames RD4"
- Les émulations `0x122` (MENU), `0x167` BUTTON_ACTION, `0x21F` LIST devraient
  enfin avoir un effet
- Possible témoin "audio absent" sur le combiné car RD4 attendu mais physiquement absent

## 🎯 Plan d'attaque pour la prochaine session voiture

### Étape A — Sanity check post re-télécodage
1. Bring up bus, capture initial state
2. Vérifier qu'aucun nouveau code défaut bloquant n'est apparu
3. Le bouton commodo essuie-glaces fonctionne-t-il maintenant ? (Lexia doit voir "Trip computer switch=Yes")

### Étape B — Cartographie boutons EMF via Lexia + capture parallèle
1. Lancer bridge.py + capture brute simultanée
2. Lexia ouvert sur ECRAN_A → "Multifunction display control status"
3. Pour chaque bouton (commodo Trip computer switch en priorité), appuyer en observant Lexia ET capture CAN
4. Identifier l'ID/byte qui change EN MÊME TEMPS que Lexia change
5. Documenter le mapping complet

### Étape C — Re-tenter émulation RD4 maintenant que télécodage est OK
1. `0x122` byte[0]=0x80 (MENU) avec présence radio idle
2. `0x21F` bit 0 (LIST) brief + sustained
3. `0x167` BUTTON_ACTION (OK/RETOUR_VALEUR)
4. Si l'EMF entre en menu réglage → on a la solution

### Étape D — Recalibrage décodeurs T° moteur / niveau carburant / odo
Avec ground-truth en main, faire une capture pendant que la voiture tourne et :
1. Chercher la T° moteur ~90°C dans tous les bytes
2. Chercher 22 ou 23 (niveau L) ou 162 (Ω jauge) ou 55 (%) dans tous les bytes
3. Chercher 110506 dans toutes combinaisons d'octets
4. Si aucun pattern : confirmer que ces signaux ne transitent vraiment pas sur CONF
   et qu'il faut passer par UDS (sur bus IS)

## 🛠 Configuration télécodage utiles (BSI > Configuration)

D'après le menu vu dans Lexia, le user peut aussi configurer :
- **Loi jauge carburant** (Law N°5 actuellement, choisi pour 41L petrol)
- **Source info niveau d'huile** : "Engine management ECU"
- **Capacité réservoir** : 41 L
- **Seuil "plein"** : 54 Ω

À ne pas modifier sans raison — déjà OK pour cette C2.
