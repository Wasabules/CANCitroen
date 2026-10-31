# App compagnon (téléphone)

Pendant une session Android Auto **sans fil**, le téléphone récupère l'historique de la
voiture sur le Wi-Fi qui le relie déjà à l'Atoto, l'écrit dans `Documents/CANCitroen` et
peut le publier dans [Home Assistant](home_assistant.md). Android Auto n'offre pas de canal
de données aux apps tierces : le transfert passe à côté, sans lui.

Les deux réglages sont désactivés par défaut : Réglages → Développeur → App compagnon.

## Mise en route

1. Installer `CANCitroen` sur l'Atoto et `CANCitroen Compagnon` sur le téléphone (`dist/`).
2. À l'arrêt, Android Auto lancé : activer « Transfert vers le téléphone ». L'Atoto affiche un code.
3. Sur le téléphone : saisir ce code, « Associer », puis « Transférer maintenant ».

Ensuite, c'est automatique tant que « Automatique pendant Android Auto » reste activé.

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

## Quand ça se déclenche

- **Côté téléphone :** une tâche périodique (15 min, persistante) lit `CarConnection` et ne
  transfère que si Android Auto projette. La diffusion `CAR_CONNECTION_UPDATED` la devance si
  elle atteint l'app. Le bouton « Transférer maintenant » ne vérifie pas Android Auto.
- **Côté Atoto :** le port reste ouvert tant que le réglage est actif, mais ne sert que le
  téléphone associé, sur le point d'accès.

Pas de réveil sur l'arrivée du Wi-Fi : un `registerNetworkCallback` à `PendingIntent` a été
retiré par le système 5 s après son premier envoi sur Android 16.

## Protocole

HTTP/1.0 dans TLS, une connexion par requête, jeton en `Authorization: Bearer`.

| Requête | Réponse |
|---|---|
| `POST /telemetry/pair?proof=` | jeton, si la preuve est bonne et qu'aucun téléphone n'est associé |
| `GET /telemetry/status` | `lastId`, `ackId`, `pending` |
| `GET /telemetry/samples?after=&limit=&upto=` | CSV gzip (5 000 lignes au plus), `X-Count`, `X-Last-Id` |
| `POST /telemetry/ack?id=` | confirme la réception |
| `GET /telemetry/inventory` | numéros encore en base, en plages : `12-500,520-900` |
| `GET /telemetry/trips`, `refuels`, `aggregates` | CSV gzip complet et `ETag` ; `304` si `If-None-Match` correspond |
| `GET /telemetry/summary` | état de la voiture en JSON, pour Home Assistant |

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

**Pas encore vérifié sur la voiture :** le transfert complet, l'accès réseau de la tâche en
arrière-plan sur le vrai Wi-Fi d'Android Auto, la diffusion `CAR_CONNECTION_UPDATED`, le magasin
de clés de la ROM FYT (en cas d'échec, le réglage affiche « certificat indisponible » et le
service continue), l'effet du débit sur la projection. L'écran du compagnon affiche la dernière
vérification automatique et son résultat, pour en juger.
