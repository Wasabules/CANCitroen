# App compagnon (téléphone)

Objectif : pendant une session Android Auto **sans fil**, transférer la télémétrie
de l'Atoto vers le téléphone, pour la consulter ensuite sur PC. Android Auto n'offre
pas de canal de données aux apps tierces : le transfert passe à côté, sur le Wi-Fi
qui relie déjà le téléphone à l'Atoto.

**État : test de liaison uniquement.** Aucune donnée du véhicule n'est transférée.
Le module `android/companion` et `app/.../link/LinkProbeServer.kt` servent à savoir
si, et comment, le téléphone atteint l'Atoto. Le protocole de transfert sera choisi
d'après ce résultat.

## Le test

| Côté | Fichier | Rôle |
|---|---|---|
| Atoto | `link/LinkProbeServer.kt` | Port 8765, réglage Dev désactivé par défaut. `/ping` : identité et version. `/blob?kb=N` : N Ko de zéros (4096 maximum). Rien d'autre. |
| Téléphone | `companion/.../LinkTest.kt` | Liste les réseaux, les adresses possibles de l'Atoto, puis essaie chaque voie. |

Voies essayées, dans l'ordre, pour chaque adresse :

1. socket de l'app sur le réseau par défaut ;
2. socket de l'app liée à chaque réseau Wi-Fi visible (`Network.socketFactory`) ;
3. root : `toybox nc`, hors du cadre des apps ;
4. root, si rien n'a répondu : règle `ip rule` temporaire vers la seule adresse de
   l'Atoto, retirée juste après l'essai ;
5. root, si rien n'a répondu : `ping -I <interface>` pour distinguer « Atoto hors
   d'atteinte » de « port fermé ».

Le rapport ne contient ni adresse matérielle ni donnée du véhicule : on peut le partager.

## Procédure sur la voiture

À l'arrêt, contact mis :

1. Installer `CANCitroen` sur l'Atoto et `CANCitroen Compagnon` sur le téléphone
   (`dist/`).
2. Lancer Android Auto sans fil et vérifier que la projection fonctionne.
3. Dans CANCitroen : Réglages → Développeur → App compagnon → activer le test, puis noter
   les adresses affichées.
4. Sur le téléphone : ouvrir le compagnon, lancer le test, accepter la demande de
   Magisk. Si aucune adresse n'est trouvée, saisir celle notée à l'étape 3.
5. Pendant le test, regarder si la projection saccade (1 Mo envoyé sans limite de
   débit par voie qui répond).
6. Partager le rapport et désactiver le test dans CANCitroen.

Côté Atoto : `adb logcat -s LinkProbe` et le compteur de requêtes du réglage montrent
ce qui est arrivé, même si le téléphone n'a rien reçu en retour.

## Lire le résultat

| Résultat | Conséquence |
|---|---|
| Voie 1 ou 2 | Transfert possible sans root. |
| Voie 3 ou 4 | Android réserve ce réseau, le root le contourne. Le transfert passera par cette voie. |
| Ping OK, HTTP KO | L'Atoto filtre le port : à traiter côté Atoto, en root. |
| Rien | Le Wi-Fi d'Android Auto est isolé. Essayer le partage de connexion du téléphone, si le module Wi-Fi de l'Atoto le permet pendant Android Auto. |

## Non vérifié

Testé sur émulateur (Android 16, sans root ni Android Auto) : voies 1 et 2, adresse
fausse ou invalide, serveur coupé. Les voies root n'ont été vérifiées que partiellement :
la commande `nc` depuis `adb shell`, pas à travers `su`. La détection d'Android Auto
(`CarConnection`) n'a été vue qu'à l'état « non connecté ».
