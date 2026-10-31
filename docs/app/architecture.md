# App Android — architecture

App Kotlin / Jetpack Compose (`com.geoffrey.cancitroen`) installée sur
l'autoradio **Atoto A6PF** (Android 10, ROM FYT/SYU, rooté Magisk). Elle lit le
bus CAN-Confort via un **CANable 2.0** branché en USB, et fait office d'**écran
d'accueil (launcher)** de la voiture : elle reste au premier plan pendant tout
le trajet.

Rôles :

- tableau de bord temps réel (vitesse, régime, températures, carburant, feux,
  ouvrants, témoins) ;
- commandes au volant relayées vers Android (volume, média, Android Auto) ;
- pilotage de l'écran multifonction (EMF) de la C2 en émulant l'autoradio RD4 ;
- historique local (trajets, pleins, statistiques) ;
- son moteur synthétique (optionnel).

Pour construire, tester et livrer : [développement](developpement.md).

## Vue d'ensemble

```
CANable (USB, slcan ASCII)
   │  usb/CanableSerial       ouverture, init `C` `S4` `O`, boucle de lecture (thread IO)
   │  usb/SlcanParser         octets → CanFrame (sans allocation par caractère)
   ▼
Channel<CanFrame> (UNLIMITED : aucune trame de bouton perdue)
   │  CanService, collecteur unique
   ▼
decode/CanDecoder.decode(state, frame)      fonction pure → nouveau VehicleState
   │
   ├─► _vehicleState ──► vehicleState   (+ calibration utilisateur : offset T° ext,
   │                        │             capacité du réservoir ; pleine cadence)
   │                        ├─► audio : EngineSoundSynth / MemeEngineRunner (RPM trame par trame)
   │                        ├─► history/VehicleHistoryRecorder (1 échantillon / 5 s)
   │                        └─► uiState   (échantillonné à 10 Hz) ──► UI Compose
   │
   └─► trames 0x21F ──► swc/SteeringWheelHandler (file + thread dédié) ──► SwcDispatcher
```

Émission : `emf/EmfController` → `CanableSerial.send()` (trame `0x3E5`).

## Packages

| Package | Contenu |
|---|---|
| (racine) | `App` (singletons), `CanService` (runtime), `MainActivity` (UI), `BootReceiver` |
| `usb/` | `CanableSerial`, `SlcanParser`, `CanFrame` |
| `decode/` | `CanDecoder`, `VehicleState`, `CanIds` (IDs + constantes véhicule) |
| `swc/` | commandes au volant : `SteeringWheelHandler`, `SwcDispatcher`, `SwcMapping`, `RootShell`, `RootKeyInjector`, `SwcNotificationListenerService` |
| `emf/` | `EmfController`, `EmfButtons` (mapping `0x3E5`) |
| `history/` | Room : `HistoryDatabase`, `dao/`, `entities/`, `VehicleHistoryRecorder`, `RollupAggregator`, `HistoryRepository` |
| `audio/` | synthèse moteur (`EngineSoundCore`, `EngineSoundSynth`, `EngineProfile`), mode « meme » (`MemeEngineRunner`, `MemeEngineCatalog`) |
| `system/` | `LauncherGuard` (launcher forcé root), `GpsTracker`, `AppShortcuts`, `PermissionsHelper` |
| `settings/` | `AppSettings` + `AppSettingsRepository` (DataStore) |
| `sim/` | `VehicleSimulator` (données factices) |
| `ui/` | `screens/` (pages), `components/` (jauges, graphiques, cartes), `vm/` (ViewModels), `theme/` (couleurs, `AppIcons`) |

Assets : `memes/engine/` (sons du
mode meme), `root/` (scripts du garde launcher, voir [launcher_root.md](launcher_root.md)).

## `App` : singletons

`App.get()` donne accès à `settings` (DataStore), `gpsTracker`, `historyDao` /
`historyRepo`, `engineSynth`, `memeEngineRunner` et à un `audioScope`. Pas
d'injection de dépendances : le projet est petit, un seul module.

`App.onCreate` doit rester très court (chargement audio différé) : voir
[démarrage à froid](#démarrage-à-froid-atoto).

## `CanService`

Service de premier plan (`START_STICKY`, relancé aussi par `onTaskRemoved` et
par le keepalive root). L'UI s'y lie (`LocalBinder`) depuis `MainActivity`.

Il possède tout le runtime :

- **Connexion CANable** : `tryConnectAndStream()` (ouverture → `initBus()` →
  boucle de lecture), sérialisée par `connectMutex` avec la fermeture au
  débranchement. Déclenchée au démarrage, au branchement USB
  (`USB_DEVICE_ATTACHED`), à l'octroi de la permission USB, et par le watchdog.
- **Watchdog** (toutes les 5 s) : reconnecte si la connexion est en `ERROR`,
  ou en `STREAMING` sans trame depuis 30 s (firmware figé, faux contact).
  Backoff exponentiel jusqu'à 30 s, remis à zéro seulement quand de vraies
  trames sont reçues.
- **Wakelock partiel** : tenu tant que le bus parle, rendu après 10 min de
  silence (contact coupé) pour laisser l'autoradio se mettre en veille,
  réarmé à la première trame. N'affecte pas le premier plan : l'activité garde
  l'écran allumé elle-même.
- **Robustesse** : le scope a un `CoroutineExceptionHandler`. Une exception
  (USB, Room…) est journalisée, pas fatale : le process est aussi le launcher.
- **Initialisation différée** (`initDeferredComponents`) : historique, rollup,
  audio, simulateur, réconciliation du launcher forcé.

États de connexion (`ConnectionState`) : `DISCONNECTED`, `AWAITING_PERMISSION`,
`OPEN`, `STREAMING`, `ERROR`.

## État véhicule

`VehicleState` est une `data class` immuable (primitives, enums, sous-objets
`Lights` / `Doors` / `Warnings`). `CanDecoder` renvoie un nouvel état par trame
décodée ; une trame inconnue ou trop courte renvoie l'état inchangé.

Trois flux, à ne pas confondre :

| Flux | Cadence | Consommateurs |
|---|---|---|
| `_vehicleState` (privé) | chaque trame | écrit par le collecteur (`updateAndGet`), le simulateur, le reset au débranchement |
| `vehicleState` | chaque changement | audio, historique ; calibration utilisateur appliquée |
| `uiState` | ≤ 10 Hz (`sample`) | toute l'UI |

Le détail des signaux et leur niveau de validation :
[signaux_decodes.md](../can/signaux_decodes.md).

## Commandes au volant

`SteeringWheelHandler` reçoit l'état à chaque trame `0x21F`, dans une file
traitée par un thread dédié (un dispatch lent, comme la première sonde `su`,
ne fige pas le collecteur CAN) :

1. molette : delta signé → N × VOLUME_UP/DOWN (plafonné à 5 crans), ignoré si
   VOL+/VOL− est déjà appuyé ;
2. boutons : front montant sur l'octet brut, anti-rebond 200 ms ;
3. action et canal pris dans `SwcMapping` (réglable dans Réglages → Volant) ;
4. `SwcDispatcher` exécute l'action sur le canal choisi (`SwcEndpoint`) :
   `AudioManager`, `MediaSession`, `dispatchMediaKeyEvent`, broadcasts des
   apps SYU (`com.syu.music`, `com.syu.bt`, `com.syu.radio`), injection root
   (`RootKeyInjector` : `sendevent` direct sur le périphérique d'entrée,
   repli `input keyevent`), `AUTO` (choix du canal le plus universel), ou
   `COMBO_UNIVERSAL` (plusieurs canaux à la fois).

Un seul canal par bouton par défaut : l'ancien envoi systématique sur
plusieurs canaux provoquait des doubles sauts de piste sous Android Auto.
`SwcNotificationListenerService` n'existe que pour obtenir l'accès aux
sessions média actives.

## Écran multifonction (EMF)

`EmfController` reproduit PSAWifiDisplayControl : tant que le mode est actif,
il émet `0x3E5 = 00×6` toutes les 65 ms, et un appui injecte une seule trame
avec le bit du bouton (relâché au tick suivant). Activé à la main depuis la
page EMF de l'UI (pas d'activation automatique : l'app n'émet sur le bus que
sur demande). Prérequis : BSI télécodée « RD4 présent ».

## Historique (Room)

Base `history.db`, schéma versionné dans `android/app/schemas/`.

| Table | Contenu |
|---|---|
| `vehicle_sample` | échantillon brut toutes les 5 s, contact mis |
| `aggregated_sample` | agrégats (moyennes pondérées, max, dernières valeurs) par taille de bucket |
| `trip` | trajets (début > 5 km/h, fin après 60 s à l'arrêt ou contact coupé) |
| `refuel_event` | pleins détectés, avec le ticket saisi par l'utilisateur (voir ci-dessous) |

**Rétention étagée** (`RollupAggregator`, toutes les 30 min, en transaction,
seuils alignés sur les buckets) :

| Âge | Stockage |
|---|---|
| < 6 h | brut 5 s |
| 6 h – 7 j | buckets 1 min |
| 7 j – 30 j | buckets 30 min |
| 30 j – 90 j | buckets 1 h |
| > 90 j | buckets 6 h |

Les écrans Stats lisent **toutes** les couches qui recouvrent la fenêtre
affichée et les regroupent au pas voulu (`HistoryRepository.rebucket`, même
fusion pondérée que le rollup) : sans ça, la vue 24 h ignorait les 6 dernières
heures. Le détail d'un trajet utilise le brut, ou les buckets 1 min au-delà de
6 h (courbes disponibles 7 jours).

Migrations : voir [développement](developpement.md#base-room--migrations).

### Graphiques de la page Stats

- **Données** : `StatsViewModel.charts` relit la fenêtre et calcule tous les
  graphiques hors du thread principal (`ui/screens/stats/StatsCharts.kt`), à
  chaque changement de fenêtre. En direct, la fenêtre avance toutes les 30 s et
  à l'ouverture de la page ; il n'y a pas de requête à chaque insert en base
  (toutes les 5 s). `StatsCharts` se compare par valeur : sans donnée nouvelle,
  rien n'est recomposé.
- **Page** : `LazyColumn`, seules les sections visibles sont composées.
- **Dessin** (`MiniLineChart`, `MiniBarChart`, `HeatmapGrid`) : `drawWithCache`,
  courbes et libellés préparés une fois par taille et jeu de données ; le curseur
  ne redessine que l'infobulle et ne recompose rien. Courbes limitées à 200 points
  (400 dans le détail d'un trajet).
- **Gestes** (`ChartSupport.kt`) : appui maintenu 150 ms puis glissement pour le
  curseur. Un geste immédiat n'est pas consommé : il fait défiler la page ou
  change de page.

### Pleins et ticket

**Détection** (`history/RefuelDetector`, fonction pure) : hausse d'au moins
12 points de jauge par rapport au dernier niveau connu, sans nouveau maximum
pendant 60 s. Il n'y a pas de limite de durée : le niveau de référence est relu
en base au démarrage, donc un plein fait contact coupé est vu même si l'app a
été tuée pendant la veille. Une jauge qui monte par paliers ne donne qu'un
plein ; 0 et 0xFF (BSI au réveil) sont ignorés ; une baisse n'est retenue
qu'après deux mesures de suite. Le seuil de 12 points n'a pas été mesuré sur la
voiture : à ajuster si le ballottement crée de faux pleins.

**Ticket** (`ui/components/RefuelPrompt.kt`, `ui/vm/RefuelViewModel`) : le plein
est enregistré avec une estimation (`awaitingInput = 1`), puis la quantité et le
montant sont demandés. La saisie apparaît après 10 s à l'arrêt, disparaît dès
3 km/h et revient au prochain arrêt, valeurs conservées. C'est une surcouche
dans l'arbre Compose avec son propre pavé numérique : ni fenêtre ni clavier
système, pour rester en mode immersif. « Plus tard » ne repose plus la question
avant le prochain démarrage de l'app ; toucher un plein dans Stats permet de
saisir ou de corriger son ticket.

Le bouton « + Ajouter » de Stats saisit un plein non détecté (appoint de moins de
12 points). Sa jauge avant est déduite des litres, donc il ne compte pas dans le
calcul de capacité (`manual`). Si le détecteur voit ce même plein dans les 2 h,
il complète la saisie avec la jauge mesurée au lieu de créer un doublon.

**Calibration** (`history/FuelCalibration`, fonctions pures) :

- le prix au litre du réglage suit le **dernier** ticket (corriger un ancien
  ticket ne le change pas) ;
- la capacité du réservoir est la médiane de `litres / Δjauge × 100` sur les
  10 derniers tickets exploitables, à partir de 3 : hausse d'au moins 20 points
  et jauge sous 99 % à l'arrivée, car une jauge saturée à 100 % fausse le calcul.
  Elle est proposée dans Réglages et ne s'applique que d'un geste (« Appliquer ») ;
- la conso réelle entre deux pleins vient du ticket si les deux finissent au même
  niveau (à 2 points près), sinon de la baisse de jauge, car un appoint ne remplace
  pas ce qui a été brûlé. Elle dépend de l'odomètre, dont le facteur n'est pas
  validé ([points ouverts](../can/signaux_decodes.md#points-ouverts)) ;
- la correction de conso est la médiane de `réelle / indiquée` sur les trajets
  enregistrés entre deux pleins, à partir de 3 mesures couvrant au moins 70 % de la
  distance. Appliquée d'un geste, elle agit sur `uiState` (affichage en direct) et
  sur le coût des trajets. L'historique garde la valeur de la voiture, sinon la
  comparaison se corrigerait elle-même ;
- le coût d'un trajet est `conso × distance × correction × prix du dernier ticket
  connu à cette date` (à défaut, le prix réglé).

### Sauvegarde et export

- **Avant migration** (`history/HistoryBackup`) : copie dans
  `databases/backup/history-v<N>-<ms>.db` (2 copies gardées) dès que la version de
  la base diffère de celle attendue, destructif compris. La copie se fait à la
  première ouverture de la base, hors du thread principal.
- **Export** (Réglages → Capteurs et carburant → Données, `history/DataExport`) : CSV des
  pleins et des trajets (`;` et virgule décimale) et copie complète de la base dans
  `Téléchargements/CANCitroen`. Ces fichiers survivent à une désinstallation.

### Simulateur et historique

Le simulateur n'écrit pas dans l'historique, sauf si le réglage Dev « Enregistrer
le simulateur dans l'historique » est activé (pour tester Stats et les pleins sans
voiture). Sans ça, ses valeurs faussent le prix, la capacité et les trajets, et
son arrêt peut déclencher un faux plein.

### Alertes

`decode/VehicleAlerts` + `ui/components/VehicleAlertBanner` : bandeau sur toutes les
pages de l'app (pas par-dessus une autre app), après 3 s de confirmation, fermable.
Pression d'huile (moteur tournant), T° d'eau ≥ 118 °C, niveaux d'eau et d'huile.
Les bits d'alerte de `0x168` viennent de la documentation et la pente de T° d'eau
n'a qu'un point de mesure : à vérifier sur la voiture.

## UI

`MainActivity` : un `HorizontalPager` de 7 pages, ouverture sur l'accueil.

Style commun : pas d'émoji, icônes monochromes `ui/theme/AppIcons`, titres
`PageTitle`/`SectionTitle` (texte neutre, accent sur l'icône), états vides
`EmptyHint`, cartes `GlassCard`/`SectionCard` (arrondi `CardCorner`).

| Page | Écran | Contenu |
|---|---|---|
| 0 | `EngineSoundScreen` | son moteur : profil, volume, démo |
| 1 | `SummaryScreen` | récapitulatif des trajets |
| 2 | `StatsScreen` | statistiques (carburant, températures, conso, trajets…) |
| **3** | `HomeScreen` | **accueil** : barre d'état, vitesse, horloge, régime, raccourcis d'apps |
| 4 | `DashboardScreen` | tableau technique de tous les signaux |
| 5 | `EmfControlScreen` | télécommande de l'EMF |
| 6 | `SettingsRoute` | réglages (volant, thème, comportement, capteurs, permissions, dev) |

Règles de performance (l'app est à l'écran pendant tout le trajet) :

- lire l'état au plus bas : l'accueil reçoit un fournisseur `() -> VehicleState`,
  la barre d'état un sous-ensemble via `derivedStateOf`, les cartes du Tableau
  uniquement leurs champs ;
- animer en phase de dessin : jauges et horloge lisent leur valeur dans
  `drawWithCache` / `graphicsLayer`, sur un calque séparé du cadran statique ;
  lissage des jauges par ressort (`Animatable`) ;
- pas de boucle `withFrameNanos` permanente : l'horloge tourne à 30 fps
  (1 Hz en mode nuit) ;
- calculs lourds (Stats) mémorisés (`remember`) ou hors du thread principal.

**Touche HOME** : tout intent HOME (touche HOME, bouton volant mappé sur
« Accueil », retour forcé par le garde root) ramène à la page d'accueil et
referme le sélecteur d'apps — animé si l'app était déjà à l'écran, instantané
si elle revient d'arrière-plan (même règle que Launcher3). Les autres intents
(branchement USB via `.UsbAttachActivity`) ne changent pas de page.

Mode inactif : après 4 s sans toucher, l'indicateur de pages s'estompe.

## Réglages (DataStore)

`AppSettings` regroupe : `display` (formats d'affichage), `behavior`
(launcher standard, launcher forcé root, mode agressif), `dev`
(simulateur), `fuel` (prix, capacité du réservoir), `calibration` (décalage
T° ext), `engineSound`, `theme` (couleur d'accent), `shortcutOverrides`
(raccourcis de l'accueil), `swcMapping` (volant).

## Son moteur

`EngineSoundCore` (Kotlin pur, testé en JVM) simule le trajet du gaz, car un vrai
moteur n'a rien de périodique. Chaque cylindre garde sa pression, différente à
chaque cycle, qui se vide à l'ouverture de sa soupape (durée fixée par la course),
remonte son collecteur (longueur selon l'architecture), rejoint le tube (une part de
l'onde revient), puis le silencieux, dont les chambres la dispersent. S'y ajoutent
les turbulences, l'admission, un grondement sans rythme, le souffle mécanique, les
claquements de soupapes et l'écho de l'habitacle. Un niveau automatique très lent
harmonise toutes les configurations sans gommer les accélérations. La charge se
déduit de la montée du régime, seule donnée disponible sur le bus. Tout évolue
échantillon par échantillon : le résultat ne dépend pas de la taille des blocs. Le
limiteur agit sur le gain et ne déforme pas le signal.

Le lever de pied a ses propres événements. L'injection se coupe (pression d'échappement
réduite) ; du carburant imbrûlé peut détoner dans le tube (`PopLevel`, surtout juste
après le lever de pied, jamais au ralenti, sous charge ni sur un diesel). Une détonation
est une onde qui fait résonner le tube selon sa longueur et la cylindrée, avec une force,
une brillance et une durée propres à l'échappement (`ExhaustType`) ; un premier essai en
claquements de bruit blanc sonnait comme « un caillou sur le fond de caisse » ; le turbo a une pression qui
monte avec retard et une turbine qui garde son élan, un sifflement de wastegate à
pleine pression et une décharge au choix (`TurboRelease`). Ces bruits passent par un
bus à part, non affecté par le niveau automatique. Limite : sur la voiture, le lever de
pied se déduit de la baisse du régime ; en 5e, très progressive, il peut passer inaperçu.

Modèles écartés à l'oreille : impulsions dans un tube résonant (« un pet dans une
canette »), puis somme de raies pures (« un moustique », trop périodique et trop
propre).

Le réglage est calé sur six enregistrements de Wikimedia Commons (DS3, CR-X, Kia
Ceed, Audi 100, Abarth 500, BMW E46, série « engine and exhaust sound », CC BY 3.0),
utilisés pour la mesure seule et non inclus au dépôt. Moyennes sur les fenêtres les
plus fortes, en dB par octave :

| | 63 Hz | 125 | 250 | 500 | 1 k | 2 k | 4 k | 8 k | Tonal > 500 Hz | Platitude |
|---|---|---|---|---|---|---|---|---|---|---|
| Réel | −9 | −7 | −15 | −16 | −17 | −23 | −30 | −39 | 0,27 | 0,24 |
| Modèle à raies | −27 | −12 | −7 | −7 | −12 | −21 | −35 | −52 | 0,51 | 0,11 |
| Simulation actuelle | −9 | −4 | −10 | −15 | −19 | −23 | −29 | −33 | 0,33 | 0,33 |

Ces mesures disent si le spectre est juste, pas si le son plaît : seule l'écoute
tranche. `doesNotWhineLikeAMosquito` et `keepsItsCrispInTheHighs` protègent ces cibles.

`EngineConfig` décrit le moteur tel qu'on le construirait (cylindres, architecture,
cylindrée, alésage, régime maximal, échappement, turbo, carburant) et produit un
`EngineProfile`. Les profils de `EngineProfiles` sont des préréglages ; les modifier
dans `EngineBuilderCard` crée « Mon moteur », enregistré dans les réglages. Le régime
de la voiture passe par un multiplicateur réglable (`EngineProfile.fromCarRpm`) :
au-dessus du ralenti le régime est multiplié, puis la courbe se couche à l'approche
du régime maximal, sans butée ni cassure.

`EngineSoundSynth` joue ce signal sur un thread dédié en priorité audio, au taux
natif de la sortie, et journalise tout vidage de piste (`logcat -s EngineSoundSynth`),
qui s'entendrait comme un grésillement. Moteur arrêté (RPM < 100) ou volume nul
depuis 1 s : l'`AudioTrack` est mise en pause, pour laisser la sortie audio se
mettre en veille.

`MemeEngineRunner` joue un son court à une cadence liée au régime.

Pour écouter sans voiture ni émulateur (tour de simulateur et régimes stables,
un fichier par profil) :

```bash
cd android
ENGINE_SOUND_RENDER_DIR=/tmp/son ./gradlew :app:testDebugUnitTest --tests '*EngineSoundRender' --rerun-tasks
```

## Simulateur

`sim/VehicleSimulator` publie un `VehicleState` toutes les 50 ms, au rythme du
régime sur le vrai bus. `sim/DrivingModel` conduit : pédale, embrayage, cinq
rapports et une boucle de 120 s (ville, feu, voie rapide, dépassement, arrêt).
Régime et vitesse découlent de la physique, ils ne sont jamais imposés. Activé
par le réglage Développeur « Mode simulateur ».

## Démarrage à froid (Atoto)

L'app est déclarée **HOME**. Au boot, le ROM revient à son propre launcher si
rien n'est affiché dans les ~3 s. D'où :

- `setContent` en premier dans `MainActivity.onCreate`, le reste posté après
  le premier rendu (`window.decorView.post { startBackgroundWork() }`) ;
- `CanService.onCreate` ne fait que le strict nécessaire (premier plan,
  wakelock, receivers), le reste est lancé en coroutine ;
- le branchement USB passe par l'`activity-alias` `.UsbAttachActivity`, pour
  que l'activité HOME ne « bouge » pas sur les évènements USB ;
- mode immersif réappliqué à chaque retour de focus (les dialogues système
  d'Android 10 l'effacent).

Pour forcer l'app comme launcher contre le ROM : [launcher_root.md](launcher_root.md).

## Pistes non réalisées

- Mode replay d'une capture `.log` dans l'app (le bridge PC le fait déjà).
- Notification permanente avec actions rapides (activer l'EMF…).
- Décodage de `0x2A1` (trajet 2).
