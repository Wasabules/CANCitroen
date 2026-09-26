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
| `audio/` | synthèse moteur (`EngineSoundSynth`, `LoopedSamplePlayer`, `PitchEstimator`, `WavLoader`), mode « meme » (`MemeEngineRunner`, `MemeEngineCatalog`) |
| `system/` | `LauncherGuard` (launcher forcé root), `GpsTracker`, `AppShortcuts`, `PermissionsHelper` |
| `settings/` | `AppSettings` + `AppSettingsRepository` (DataStore) |
| `sim/` | `VehicleSimulator` (données factices) |
| `ui/` | `screens/` (pages), `components/` (jauges, graphiques, cartes), `vm/` (ViewModels), `theme/` (couleurs, `AppIcons`) |

Assets : `engines/` (échantillons WAV du son moteur), `memes/engine/` (sons du
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
avec le bit du bouton (relâché au tick suivant). Activé depuis la page EMF de
l'UI. Prérequis : BSI télécodée « RD4 présent ».

⚠ Le réglage « Activer le mode EMF au démarrage » (`behavior.emfModeAutostart`)
est enregistré mais **n'est lu nulle part** : il n'a aucun effet pour l'instant.

## Historique (Room)

Base `history.db`, schéma versionné dans `android/app/schemas/`.

| Table | Contenu |
|---|---|
| `vehicle_sample` | échantillon brut toutes les 5 s, contact mis |
| `aggregated_sample` | agrégats (moyennes pondérées, max, dernières valeurs) par taille de bucket |
| `trip` | trajets (début > 5 km/h, fin après 60 s à l'arrêt ou contact coupé) |
| `refuel_event` | pleins (hausse de carburant ≥ 20 points en moins de 10 min) |

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

## UI

`MainActivity` : un `HorizontalPager` de 7 pages, ouverture sur l'accueil.

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

Mode inactif : après 4 s sans toucher, l'indicateur de pages s'estompe.

## Réglages (DataStore)

`AppSettings` regroupe : `display` (formats d'affichage), `behavior`
(démarrage EMF, launcher standard, launcher forcé root, mode agressif), `dev`
(simulateur), `fuel` (prix, capacité du réservoir), `calibration` (décalage
T° ext), `engineSound`, `theme` (couleur d'accent), `shortcutOverrides`
(raccourcis de l'accueil), `swcMapping` (volant).

## Son moteur

`EngineSoundSynth` mélange trois échantillons (ralenti / milieu / haut) selon
le RPM, ou génère un son procédural. `MemeEngineRunner` joue un son court à
une cadence liée au régime. Moteur arrêté (RPM < 100) ou volume nul depuis
1 s : l'`AudioTrack` est mise en pause, pour laisser la sortie audio se mettre
en veille.

## Simulateur

Réglages → Dev → « Mode simulateur » : `VehicleSimulator` écrit un cycle de
conduite de 2 min dans `_vehicleState` (mise à jour toutes les 500 ms). Sert
à développer l'UI et l'historique sans voiture. Attention : 2 Hz, loin des
20-50 Hz du vrai bus.

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
