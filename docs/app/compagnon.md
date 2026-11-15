# App compagnon (téléphone)

Pendant une session Android Auto **sans fil**, le téléphone suit la voiture sur le Wi-Fi qui le
relie déjà à l'Atoto : il en récupère l'historique, l'écrit dans `Documents/CANCitroen`, le
publie dans [Home Assistant](home_assistant.md) et l'affiche, sur le téléphone comme dans
Android Auto. Android Auto n'offre pas de canal de données aux apps tierces : la liaison passe
à côté, sans lui.

Trois onglets : **Voiture** (état, position, graphiques, conduite, trajets, pleins), **Suivi**
(état de la liaison, fiabilité, journal), **Réglages** (association, conservation, carburant,
position, Home Assistant, test de liaison). Plus un widget pour l'écran d'accueil.

Côté Atoto, les deux réglages sont désactivés par défaut : Réglages → Développeur → App compagnon.

## Mise en route

1. Installer `CANCitroen` sur l'Atoto et `CANCitroen Compagnon` sur le téléphone (`dist/`).
2. À l'arrêt, Android Auto lancé : activer « Transfert vers le téléphone ». L'Atoto affiche un code.
3. Sur le téléphone, onglet Réglages : saisir ce code, « Associer », puis « Transférer maintenant ».
4. Onglet Suivi, rubrique Fiabilité : tout passer à « OK » avec les boutons « Régler » (batterie
   non optimisée, Appareils à proximité, notifications). Sans l'exemption de batterie, Android
   refuse de démarrer le suivi quand l'app n'est pas à l'écran.
5. Facultatif, pour l'écran dans Android Auto : voir [Dans Android Auto](#dans-android-auto).

Ensuite, le suivi démarre seul à chaque trajet. Le premier trajet suivi apprend le Bluetooth de
la voiture : les autres appareils (casque, montre) ne le déclenchent plus.

## Résultat de la liaison

Mesuré sur la voiture le 2026-10-31 (Android 16, Android Auto connecté) :

| Voie | Résultat |
|---|---|
| Socket sur le réseau par défaut | échec : il part sur le réseau mobile |
| Socket lié au Wi-Fi (`Network.socketFactory`) | répond en 26 ms, 46,6 Mbit/s |

L'Atoto est la passerelle du Wi-Fi (`192.168.43.1`) et le téléphone n'a pas besoin du root.
Le test reste disponible pour diagnostiquer (`LinkTest.kt`, port 8765, aucune donnée véhicule).

## Sécurité

| Menace | Protection |
|---|---|
| Écoute du Wi-Fi | TLS 1.3 sur le port 8766 ; aucune donnée véhicule sur le port en clair |
| Faux Atoto | certificat épinglé à l'association : rien n'est envoyé à un autre certificat |
| Autre appareil sur le Wi-Fi, autre app du téléphone | jeton de 256 bits, un seul téléphone associé |
| Vol du code | à usage unique, jamais envoyé : une preuve HMAC sur l'empreinte du certificat |
| Essais de code ou de jeton | 1 s de pénalité par échec, 4 connexions à la fois |
| Atoto client d'un autre Wi-Fi | refus si la connexion n'arrive pas sur son point d'accès (`.1` privé) |
| Téléphone perdu | « Oublier ce téléphone » sur l'Atoto révoque le jeton |

La clé privée du certificat reste dans le magasin de clés Android de l'Atoto. Son empreinte
et le jeton sont dans les préférences privées du téléphone ; sur l'Atoto, seule l'empreinte
du jeton est gardée. Les fichiers de `Documents/CANCitroen` ne sont pas chiffrés et l'historique
ne contient aucune position GPS.

## Suivi pendant le trajet

Un service au premier plan (`CarSessionService`, type « appareil connecté »), avec sa notification
« Suivi de la voiture », tient la liaison du début à la fin du trajet. Une tâche périodique seule ne
suffit pas : Android la repousse de plusieurs heures pour une app qu'on n'ouvre pas, et Home
Assistant restait figé sur le dernier envoi manuel.

| Étape | Rythme (`SessionPlan`) |
|---|---|
| Recherche de l'Atoto | toutes les 10 s, abandon après 5 min |
| Interrogation (`/telemetry/summary`) | toutes les 30 s |
| Home Assistant | à chaque changement, et toutes les 5 min au moins ; après un échec, 30 s, 1, 2, 4 puis 5 min |
| Échantillons neufs (transfert léger) | à chaque interrogation : les courbes suivent le trajet |
| Trajets, pleins, agrégats (tour complet) | au premier contact, puis toutes les 10 min : l'Atoto les recalcule à chaque demande |
| Fin du trajet | l'Atoto ne répond plus depuis 90 s : dernier envoi à Home Assistant (voiture déconnectée), puis arrêt |

Home Assistant et le transfert tournent chacun sur leur thread : un serveur lent ou injoignable ne
retarde pas l'interrogation de l'Atoto, et ne peut donc pas faire croire à une fin de trajet. Le
téléphone reste éveillé le temps du trajet (verrou renouvelé à chaque tour) ; la projection
d'Android Auto le garde de toute façon actif.

**Ce qui le démarre**, du plus tôt au plus tard :

| Déclencheur | Condition |
|---|---|
| Connexion Bluetooth (`ACL_CONNECTED`) | autorisation « Appareils à proximité » ; ensuite, seul le Bluetooth de la voiture |
| Diffusion d'Android Auto (`CAR_CONNECTION_UPDATED`) | si elle atteint l'app (non vérifié) |
| Compagnon ouvert pendant Android Auto, écran Android Auto du compagnon, bouton « Démarrer le suivi » | — |
| Vérification périodique (15 min, persistante) | l'Atoto répond ; elle renvoie aussi l'état resté en attente (fin de trajet sans réseau) |

Démarré depuis l'arrière-plan (Bluetooth, tâche), le service exige que l'optimisation de la
batterie soit désactivée pour le compagnon : sinon Android refuse, le journal le dit, et une seule
passe (transfert puis Home Assistant) est faite à la place.

**Côté Atoto :** le port reste ouvert tant que le réglage est actif, mais ne sert que le téléphone
associé, sur le point d'accès.

Pas de réveil sur l'arrivée du Wi-Fi : un `registerNetworkCallback` à `PendingIntent` a été
retiré par le système 5 s après son premier envoi sur Android 16.

### Journal

L'onglet Suivi garde un journal (400 lignes au plus, privé à l'app) : début et fin de suivi, Atoto
joint et par quelle voie, transferts, échecs et retour de Home Assistant, refus d'Android. Sans
jeton, en-tête ni adresse de webhook. « Partager le journal » l'envoie où l'on veut, pour
diagnostiquer un trajet après coup.

## Onglet Voiture

Tout vient des fichiers déjà reçus : l'onglet marche aussi loin de la voiture.

- **État** : dernier résumé reçu de l'Atoto (carburant, odomètre, températures, dernier trajet,
  mois en cours, dernier plein), mis à jour toutes les 30 s pendant un trajet. Avec la position
  partagée, l'heure et la précision du dernier point, et « Ouvrir la carte ».
- **Graphiques** sur 24 h, 7 j, 30 j ou toute la période gardée : carburant, températures (eau,
  huile, extérieur), vitesse, régime, consommation. Le détail à 5 s là où le téléphone l'a, les
  agrégats ailleurs. Ramenés à une moyenne par tranche (240 au plus) ; un arrêt reste un trou. Un
  appui ou un glissé lit les valeurs à un instant.
- **Conduite** sur la période (détail à 5 s seulement) : temps de roulage et de ralenti, distance
  intégrée de la vitesse, part en ville (< 50 km/h), sur route (50 à 90) et sur voie rapide avec
  leur conso, régime moyen et part au-dessus de 3 000 tr/min, rapports engagés, et pour les trajets
  terminés litres, coût et CO₂ (essence 2,31 kg/L, gazole 2,64, au choix dans Réglages).
- **Trajets et pleins** de la période, avec le coût de chaque trajet ; toucher un trajet affiche
  ses courbes.

Le régime au passage de rapport n'est pas mesurable : un passage dure moins d'une seconde, les
relevés sont espacés de 5 s. Le rapport engagé se reconnaît en revanche au rapport vitesse / régime,
qui ne prend qu'une valeur par rapport de boîte (`DrivingAnalysis.gears`, pics d'un histogramme
pondéré par le temps). Ses numéros sont relatifs : le plus court vu sur la période est « 1 ». Sur le
simulateur, les démultiplications retrouvées (15,3 / 21,3 / 27,8 / 35,3 km/h à 1 000 tr/min) sont
celles de son modèle de boîte, à 0,2 près ; la 1re, peu tenue, n'apparaît pas.

Le coût prend le prix du dernier ticket, sinon celui réglé sur l'Atoto.

## Widget

« CANCitroen Compagnon » dans les widgets du lanceur : carburant, autonomie, trajet en cours ou
dernier trajet, et « connectée » ou l'heure du dernier contact. Le suivi le met à jour à chaque
nouveau résumé ; un appui ouvre l'app.

## Position de la voiture

Désactivée par défaut (Réglages → « Position de la voiture »). Activée, le téléphone demande au
résumé le dernier point GPS de l'Atoto (`?position=1`) : l'onglet Voiture l'affiche, et Home
Assistant le reçoit comme localisation de `device_tracker.citroen_c2`, donc où la voiture est garée
et ses trajets sur la carte. Coupée, plus aucune position n'est demandée.

Pendant Android Auto, l'app de l'Atoto n'est plus à l'écran et arrête son GPS. Chaque demande du
téléphone le garde donc actif 5 min, à un point toutes les 10 s (`GpsTracker.freshFix`), et le
dernier point connu du système sert s'il est plus récent. Sur Android 10, cela exige la
**localisation toujours autorisée** pour CANCitroen (Réglages → Permissions de l'app ; ligne dédiée
dans l'onglet Permissions). Sans elle, la position reste celle du moment où Android Auto a pris
l'écran.

## Conservation sur le téléphone

Réglages → « Conserver le détail à 5 s sur le téléphone » : 30 j, 90 j, 1 an ou tout (par défaut,
comme avant). Au-delà, seuls les fichiers `echantillons-...` sont retirés, après confirmation qui
annonce le nombre de jours ; trajets, pleins et agrégats restent, et les graphiques s'en servent.
La conservation s'applique aussi après chaque transfert. Pas moins de 30 jours : l'Atoto garde
jusque-là le brut non reçu, qui reviendrait sinon à la récupération. « Vérifier et récupérer » ne
compte pas comme manque ce que la conservation a retiré.

## Dans Android Auto

Le compagnon apparaît dans Android Auto (bibliothèque Car App, catégorie IoT) :

| Écran | Contenu |
|---|---|
| Accueil | carburant, températures, dernier trajet, état du suivi ; courbe du trajet en cours, sinon des 24 h ; « Courbe » change de graphique |
| Trajets | les 6 derniers des 30 jours ; chacun ouvre ses chiffres et ses courbes |
| Suivi | état de la liaison, « Envoyer à HA », « Transférer » |

Android Auto n'affiche que ses propres modèles d'écran : les graphiques y sont des images, sans
curseur, et seulement si la voiture accepte les images (API Car App 4 et plus). L'ouvrir lance le
suivi s'il ne tourne pas. Seul Android Auto, signé par Google, peut s'y lier (`HostValidator`).

Installée hors du Play Store, une app n'apparaît dans Android Auto qu'avec les sources inconnues :
paramètres d'Android Auto sur le téléphone → toucher dix fois « Version » pour le mode développeur →
menu ⋮ → Paramètres pour les développeurs → **Sources inconnues**. Puis, dans le lanceur d'Android
Auto, « Personnaliser » si l'icône n'y est pas.

## Protocole

HTTP/1.0 dans TLS, une connexion par requête, jeton en `Authorization: Bearer`. Chemins, en-têtes,
colonnes des CSV et champs du résumé sont dans le module `android/shared`, utilisé par les deux apps.

| Requête | Réponse |
|---|---|
| `POST /telemetry/pair?proof=` | jeton, si la preuve est bonne et qu'aucun téléphone n'est associé |
| `GET /telemetry/status` | `lastId`, `ackId`, `pending` |
| `GET /telemetry/samples?after=&limit=&upto=` | CSV gzip (5 000 lignes au plus), `X-Count`, `X-Last-Id` |
| `POST /telemetry/ack?id=` | confirme la réception |
| `GET /telemetry/inventory` | numéros encore en base, en plages : `12-500,520-900` |
| `GET /telemetry/trips`, `refuels`, `aggregates` | CSV gzip complet et `ETag` ; `304` si `If-None-Match` correspond |
| `GET /telemetry/summary[?position=1]` | état de la voiture en JSON (`SummaryFields`), trajet en cours compris ; position GPS seulement si demandée |

Sortie limitée à environ 1 Mo/s et thread en priorité basse, pour la projection et le CAN.

## Fichiers

Format d'un tableur français (`;` et virgule décimale).

| Fichier | Contenu | Écriture |
|---|---|---|
| `echantillons-AAAA-MM-JJ.csv` | échantillons de 5 s, par jour | ajout, sans doublon |
| `trajets.csv`, `pleins.csv` | tout | remplacé seulement s'il a changé |
| `agregats.csv` | tout ce que l'Atoto a déjà agrégé | remplacé seulement s'il a changé |
| `manques.csv` | périodes dont le détail est perdu | écrit par « Vérifier et récupérer » |

## Coupures

L'Atoto ne considère un échantillon comme reçu qu'après sa confirmation, envoyée une fois le lot
écrit. Une coupure ne perd donc rien :

| Moment | Effet |
|---|---|
| Pendant un téléchargement | réponse incomplète refusée, rien n'est écrit, le lot repart |
| Avant la confirmation | fichier écrit, confirmation renvoyée au transfert suivant, même sans donnée neuve |
| App tuée pendant l'écriture | lot renvoyé ; les lignes déjà présentes sont ignorées, une ligne coupée ne compte pas |
| Pendant le remplacement d'un fichier | écrit à part, l'ancien reste en place |

Seuls les nouveaux échantillons sont envoyés. Trajets, pleins et agrégats sont recalculés sur l'Atoto
à chaque transfert, mais ne repartent que si leur empreinte a changé.

## Récupérer ce qui manque

« Vérifier et récupérer », sur demande seulement, car un fichier déplacé sur le PC ressemble à
un fichier perdu :

1. transfert normal, avec trajets, pleins et agrégats forcés ;
2. demande à l'Atoto ses plages de numéros, compare avec les fichiers, ne redemande que les manquants ;
3. remet les fichiers en ordre, sans doublon ni ligne abîmée ;
4. compare les fichiers aux échantillons comptés dans les agrégats et écrit `manques.csv` : les
   périodes dont le détail n'existe plus nulle part, leur résumé restant dans `agregats.csv`.

Limite : après une réinstallation du compagnon, Android lui cache ses anciens fichiers. Il redemande
donc ce que l'Atoto a encore et crée de nouveaux fichiers (`... (1).csv`).

## Rétention

Sans téléphone associé, rien ne change : 6 h. Avec un téléphone associé, le brut reste 7 jours après
réception pour permettre la récupération, et 30 jours au plus s'il n'a pas été reçu
(`RollupAggregator.rawCutoff`). Passé ce délai, il est agrégé et son détail à 5 s est perdu. Les vues
Stats lisent ces lignes en plus : une longue période pèse plus lourd à regrouper, hors du thread
principal ; l'effet n'a pas été mesuré sur l'Atoto.

## Vérifié, et ce qui ne l'est pas

Compagnon 0.4.0 et app 0.1.39, sur émulateur Android 16 contre Home Assistant 2026.11.1 : fichier
du jour enrichi toutes les 30 s, position reçue par `device_tracker.citroen_c2` puis suivie après
déplacement avec l'app de l'Atoto en arrière-plan, événement `cancitroen_trajet_termine` provisoire
à la fin du trajet et automatisation d'exemple déclenchée, analyse de conduite affichée, widget
appliqué par un test instrumenté. Non vérifié : un vrai lanceur avec le widget posé, la localisation
en arrière-plan sur la ROM de l'Atoto, un plein réel (événement testé en JVM seulement).

Suivi (compagnon 0.3.0), sur émulateur Android 16 contre un vrai Home Assistant 2026.11.1 : capteurs
mis à jour toutes les 30 s app en arrière-plan ; arrêt à la main et fin de trajet (port de l'Atoto
fermé) publiés comme « voiture déconnectée » en 90 s ; démarrage par la tâche, app tuée, refusé sans
exemption de batterie (journal et passe unique) puis accepté avec ; Home Assistant figé 2 min en plein
trajet : envois espacés, suivi maintenu, reprise au retour. Écrans Android Auto construits par
`androidx.car.app:app-testing` pour un hôte d'API 1 et le plus récent, chaque graphique dessiné.
Non vérifié : la diffusion Bluetooth (pas de root sur l'émulateur), un vrai Android Auto.

Sur émulateur, les deux apps sur le même appareil. Android 16 : association, code faux ou déjà
utilisé, révocation, faux serveur TLS (0 requête reçue), transfert par la tâche en arrière-plan avec
Android Auto simulé dans un build temporaire. Android 10, celui de l'Atoto, avant l'ajout de la
récupération : serveur TLS avec le magasin de clés, association, transfert, fichiers, tests Room.

Coupures réelles sur Android 16, sur 74 000 échantillons, avec `scripts/proxy_coupures.py` : téléchargement
coupé à 30 Ko, confirmation perdue avec et sans données neuves, app tuée à quatre moments du transfert,
remplacement d'`agregats.csv` coupé, ligne tronquée, fichier supprimé, détail perdu (rollup réel, 7 880
échantillons retrouvés dans `manques.csv`). Aucun doublon, aucune ligne abîmée après reprise, et plus aucun
trou après récupération de ce que l'Atoto avait encore. L'app tuée pendant l'écriture elle-même n'a été
simulée que par la ligne tronquée.

**Pas encore vérifié sur la voiture :** le démarrage par le Bluetooth de la voiture, le suivi
complet sur le vrai Wi-Fi d'Android Auto, la diffusion `CAR_CONNECTION_UPDATED`, l'écran dans
Android Auto, le magasin de clés de la ROM FYT (en cas d'échec, le réglage affiche « certificat
indisponible » et le service continue), l'effet du débit sur la projection. Le journal de l'onglet
Suivi dit ce qui s'est passé pendant un trajet.
