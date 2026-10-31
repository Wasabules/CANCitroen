# Home Assistant

Le compagnon publie l'état de la voiture dans Home Assistant : un appareil « Citroën C2 »
et ses capteurs, sans YAML ni module complémentaire. Il passe par l'intégration `mobile_app`,
celle de l'app officielle, présente avec `default_config`.

## Mise en route

1. Dans Home Assistant : profil → Sécurité → créer un jeton longue durée.
2. Dans le compagnon, section Home Assistant : saisir l'adresse et le jeton, puis « Connecter ».
3. Facultatif : [`tableau_de_bord.yaml`](home_assistant/tableau_de_bord.yaml) et
   [`automatisations.yaml`](home_assistant/automatisations.yaml), mode d'emploi en tête de fichier.

Le jeton ne sert qu'à l'inscription et n'est pas conservé. Le webhook reçu en échange ne permet
que d'écrire les capteurs de cet appareil. L'adresse doit être en `https`, sauf sur le réseau
local (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `.local`). Un certificat auto-signé n'est pas accepté.

## Tester l'accès

« Tester l'accès » essaie la connexion sans rien modifier et dit qui refuse : réseau, certificat,
proxy, Cloudflare ou Home Assistant. Avant la connexion, il utilise l'adresse et le jeton saisis ;
ensuite, chaque voie du webhook, car le jeton n'est plus conservé.

## En-têtes personnalisés, Cloudflare Access

Le champ « En-têtes personnalisés » accepte un en-tête par ligne, au format `Nom: valeur`. Ils
accompagnent toutes les requêtes vers l'adresse saisie, inscription et publications, et ne partent
jamais vers une autre adresse, comme l'accès distant ou le webhook de Nabu Casa. Ils restent dans
les préférences privées de l'app. Un secret renouvelé se change sans ressaisir le jeton Home Assistant.

Pour une application protégée par Cloudflare Access :

1. Zero Trust → Access controls → Service credentials → Service Tokens : créer un jeton de service
   et copier son secret, affiché une seule fois.
2. Dans l'application Access de Home Assistant, ajouter une politique dont l'action est **Service
   Auth** et qui inclut ce jeton. Avec une autre action, Access demande une connexion à une page web.
3. Dans le compagnon, saisir :

   ```
   CF-Access-Client-Id: <identifiant>.access
   CF-Access-Client-Secret: <secret>
   ```

4. Si le test indique un refus du proxy, ajouter à `configuration.yaml`, avec l'adresse du connecteur
   Cloudflare Tunnel ou du proxy :

   ```yaml
   http:
     use_x_forwarded_for: true
     trusted_proxies:
       - 172.30.33.0/24
   ```

Le jeton de service a une date d'expiration : à ce moment, les publications s'arrêtent et l'écran
du compagnon indique le refus de Cloudflare.

## Capteurs

| Entité (`sensor.citroen_c2_...`) | Contenu |
|---|---|
| `jauge`, `carburant`, `autonomie` | %, L, km |
| `odometre` | km |
| `temperature_eau`, `temperature_huile`, `temperature_exterieure` | °C |
| `niveau_huile` | % |
| `dernier_releve` | date du dernier échantillon |
| `dernier_trajet` | km ; attributs : départ, arrivée, durée, vitesses |
| `conso_dernier_trajet` | L/100 km, corrigée par les tickets comme à l'écran |
| `dernier_plein` | date ; attributs : litres, montant, prix, estimation |
| `prix_du_litre` | €/L |
| `distance_du_mois`, `trajets_du_mois`, `conso_du_mois`, `carburant_du_mois`, `depense_du_mois` | mois en cours |

Et `binary_sensor.citroen_c2_android_auto`, `binary_sensor.citroen_c2_trajet_en_cours`.

`device_tracker.citroen_c2` reste « inconnu » : `mobile_app` le crée d'office, et aucune position
n'est envoyée. On peut le désactiver. La précision d'affichage se règle dans chaque entité.

## Quand les valeurs changent

Le compagnon publie à la fin de chaque transfert et quand Android Auto change d'état, vu par sa
tâche toutes les 15 min. Serveur injoignable : le dernier état attend et part à la prochaine
tentative qui l'atteint.

- **Sur la route**, il faut un serveur joignable depuis Internet (Nabu Casa, accès distant).
  L'Atoto n'a pas Internet, c'est le réseau mobile du téléphone qui sert. Avec une adresse
  locale seule, tout part au retour à la maison.
- **Le dernier trajet arrive avec du retard.** Il se ferme à l'arrêt du moteur, quand le Wi-Fi
  d'Android Auto disparaît : ses chiffres définitifs partent à la session suivante.
- **Ce n'est pas du temps réel** : pas de vitesse ni de régime, inutiles à 15 min d'intervalle,
  et pas d'automatisation qui exige une réaction immédiate, comme ouvrir un portail.

## Fiabilité des valeurs

La formule de la température extérieure et le facteur de l'odomètre **ne sont pas validés** sur
la voiture (`docs/can/signaux_decodes.md`) : ne pas fonder d'automatisation dessus. L'échelle du
niveau d'huile non plus : le seuil de l'automatisation fournie est à ajuster.

## Réinstallation et suppression

L'identifiant d'appareil est fixe : se reconnecter ou réinstaller le compagnon remplace
l'inscription et garde les mêmes entités. Si l'intégration est supprimée dans Home Assistant, le
compagnon le constate après trois essais (le serveur répond alors comme pendant un démarrage)
et redemande le jeton. « Déconnecter » n'agit que sur le téléphone : l'appareil se supprime dans
les réglages de Home Assistant.

## Vérifié

Contre un vrai Home Assistant 2026.10.4 en conteneur. Depuis Android 16 : les 20 capteurs et leurs
unités, faux jeton, `http` public refusé, panne puis reprise par la tâche, redémarrage du serveur
(valeurs conservées), intégration supprimée. Depuis Android 10 : connexion et publication. En appels
directs à l'API : réinscription du même appareil, sans entités `_2`. Les quatre automatisations
déclenchées, et le tableau de bord affiché sur ordinateur et sur téléphone.

Le test d'accès et les en-têtes, contre le même Home Assistant derrière une passerelle qui imite
Cloudflare Access : redirection et refus 403, secret absent ou expiré, faux jeton, proxy non déclaré,
tunnel coupé, port fermé, nom inconnu, connexion, publication et renouvellement du secret.

**Pas vérifié :** le vrai Cloudflare Access, dont les réponses exactes n'ont été que reproduites
d'après sa documentation ; Nabu Casa et l'accès distant, faute de compte ; les deux graphiques de
statistiques, restés vides car Home Assistant les calcule après une heure de données ; une publication
depuis la voiture.
