# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projet

Reverse-engineering du bus **CAN-Confort (125 kbit/s)** d'une Citroën C2 (PSA AEE2004, RD4 d'origine retiré, remplacé par un autoradio Android **Atoto A6PF**), via un **CANable 2.0** en firmware slcan (USB `16d0:117e`). Deux moitiés :

- `scripts/` — outillage Python côté PC (sniff, diff, isolation de boutons, émission) + `bridge.py`, l'implémentation de référence des décodeurs.
- `android/` — app Kotlin/Compose installée sur l'Atoto : lit le CANable en USB, décode, affiche des dashboards, relaie les boutons volant vers Android, pilote l'écran multifonction (EMF). Avec `android/companion` (app du téléphone) et `android/shared` (contrat entre les deux : protocole, fichiers CSV, champs du résumé — toute évolution du format passe par lui).

Tout (code, commentaires, notes, UI) est en français — garder cette convention.

**Dépôt public** : https://github.com/Wasabules/CANCitroen (licence MIT). Commits **au seul nom de Wasabules** (identité noreply configurée dans `.git/config`), **sans trailer `Co-Authored-By` ni mention de Claude**. Ne jamais versionner la keystore (`android/keystore/`), les sons « meme » (`assets/memes/engine/*.mp3`), des APK, des captures de bus ou des données personnelles (VIN, plaque, positions GPS).

## Commandes

### App Android (`android/`, JDK 17+, Gradle 9.8, AGP 9.3, Kotlin 2.4, compileSdk 37)

```bash
cd android
./gradlew :app:compileDebugKotlin            # vérif de compilation rapide
./gradlew :app:testDebugUnitTest             # tests JVM (décodeur, parseur slcan, regroupement Stats)
./gradlew :app:testDebugUnitTest --tests '*SlcanParserTest'   # une seule classe
./gradlew :app:connectedDebugAndroidTest     # migrations/rétention, ÉMULATEUR SEULEMENT : désinstalle l'app à la fin
./gradlew :app:assembleRelease               # APK à installer → app/build/outputs/apk/release/app-release.apk
./gradlew :companion:assembleRelease         # app compagnon (téléphone) → companion/build/outputs/apk/release/
./gradlew :companion:testDebugUnitTest       # tests JVM du compagnon (protocole, Home Assistant, suivi, historique, conduite)
./gradlew :shared:testDebugUnitTest          # contrat Atoto ↔ compagnon (preuve d'association figée, en-têtes)
# Écrans Android Auto du compagnon (androidx.car.app:app-testing), sur émulateur, sans désinstaller :
./gradlew :companion:assembleDebug :companion:assembleDebugAndroidTest && adb install -r companion/build/outputs/apk/debug/companion-debug.apk \
  && adb install -r companion/build/outputs/apk/androidTest/debug/companion-debug-androidTest.apk \
  && adb shell am instrument -w com.geoffrey.cancitroen.companion.test/androidx.test.runner.AndroidJUnitRunner
adb install -r app/build/outputs/apk/release/app-release.apk
```

- **Livraison** : incrémenter **ensemble** `versionCode` et `versionName` (`0.1.<versionCode>`) dans `android/app/build.gradle.kts`, puis copier l'**APK release** dans `dist/CANCitroen-v<versionName>-<YYYYMMDD-HHMM>.apk`. Release = R8 + ressources réduites, non debuggable (~3 Mo, nettement plus rapide que le debug). Signature : `android/keystore/cancitroen.keystore` si présent (sinon clé debug de la machine) — fichier **local, jamais versionné ni publié** (dépôt public ; c'est la clé debug de la machine) ; ne pas en changer, sinon il faut désinstaller sur l'Atoto (→ perte de l'historique et des réglages).
- Toolchain : AGP 9 intègre Kotlin — **ne pas appliquer** `org.jetbrains.kotlin.android` dans `app/` (il n'est déclaré `apply false` à la racine que pour fixer la version de KGP) ; options Kotlin dans `kotlin { compilerOptions {…} }`. AGP est bloqué en **9.3.x** tant qu'Android Studio est en Quail 2 (9.4+ exige Quail 4). Versions de Compose/Material3 fixées par la BOM uniquement. `compileSdk 37` est imposé par androidx.core 1.19 (API 37.0 suffit) ; `minSdk 29`/`targetSdk 36` inchangés.
- R8 : aucune réflexion dans l'app ; usb-serial-for-android en utilise mais embarque sa propre règle `-keep`. Vérifier toute nouvelle lib qui charge des classes par réflexion.
- Icônes : `ui/theme/AppIcons.kt` contient les seules icônes Material utilisées (tracés recopiés) — ne pas remettre `material-icons-extended` (~40 Mo de dex). **Pas d'émoji dans l'interface** : icônes monochromes `AppIcons`, titres `PageTitle`/`SectionTitle` (texte neutre, accent sur l'icône), cartes `GlassCard`/`SectionCard`.

### Outillage Python (`scripts/`, deps : `can-utils`, `python3-can`, `aiohttp`)

```bash
./scripts/can_up.sh 125000        # slcand → slcan0 (sudo) ; CAN_TTY / CAN_IFACE surchargeables
./scripts/bus_health.py           # premier diag une fois branché : bus vivant ? bitrate ? IDs PSA reconnus ?
./scripts/capture.sh <label> [s]  # candump -L → captures/<ts>_<label>.log (gitignored)
./scripts/diff_capture.py captures/a.log captures/b.log
./scripts/bridge.py               # HTTP/WS sur :8080, sert web/dashboard.html
./scripts/bridge.py --replay captures/foo.log --port 8088   # sans voiture
./scripts/can_down.sh
python3 -m unittest scripts/test_decoders.py   # parité des décodeurs de bridge.py
```

`_canlog.py` est le parseur partagé du format `candump -L`. Les tests sur voiture suivent `docs/terrain/field_test_playbook.md` (dans l'ordre). Détail de chaque script et des routes du bridge : `scripts/README.md`.

## Architecture de l'app Android

**Pipeline RX** : `usb/CanableSerial` (usb-serial-for-android, init slcan `C` / `S4` / `O`) → `SlcanParser` → `framesFlow` (Channel UNLIMITED, pour ne perdre aucune transition de bouton) → collecté dans `CanService` → `decode/CanDecoder.decode(state, frame)` (fonction pure, `VehicleState` immuable) → `_vehicleState` → `vehicleState` public (= combiné avec les réglages : offset T° ext, capacité du réservoir ; pleine cadence, lu par l'audio et l'historique) → `uiState` (échantillonné à 10 Hz pour l'UI : RPM/vitesse changent à chaque trame 0x0B6, 20-50 Hz).

- **`CanService`** (foreground service, `START_STICKY`) possède tout le runtime : connexion CANable (open/init/close sérialisés par un `Mutex`), watchdog de reconnexion (ERROR ou 30 s de silence en STREAMING, backoff exponentiel remis à zéro seulement sur trames reçues), wakelock tenu tant que le bus parle (rendu après 10 min de silence), `EmfController`, `SteeringWheelHandler`, historique, audio, simulateur, pilotage de `LauncherGuard`. Son scope a un `CoroutineExceptionHandler` : une exception de coroutine est journalisée au lieu de tuer le process (qui est aussi le launcher de la voiture). L'UI y accède en se bindant (`LocalBinder`) depuis `MainActivity`.
- **`App`** : singletons applicatifs accessibles via `App.get()` — `settings` (DataStore, `settings/AppSettings.kt`), `gpsTracker`, `historyDao`/`historyRepo`, moteurs audio.
- **Volant** : les trames `0x21F` déclenchent `swc/SteeringWheelHandler` (file + thread dédié, pour qu'un dispatch lent ne fige pas le collecteur CAN ; détection de front + debounce) → `SwcDispatcher`, qui exécute l'action mappée par l'utilisateur (`SwcMapping`) sur le `SwcEndpoint` choisi pour ce bouton (AudioManager, MediaSession, broadcasts SYU, injection root via `RootShell`/`RootKeyInjector`, `AUTO`, ou fan-out `COMBO_UNIVERSAL`). Un seul canal par défaut : l'ancien fan-out systématique provoquait des doubles sauts de piste sous Android Auto.
- **EMF** : `emf/EmfController` émet `0x3E5 = 00×6` toutes les 65 ms tant qu'il est actif, et injecte une seule trame avec le bit du bouton sur `pressButton` (logique PSAWifiDisplayControl, mapping dans `EmfButtons.kt`). Prérequis : télécodage BSI « RD4 présent », sinon l'EMF ignore `0x3E5`.
- **Historique** : Room `history.db` (`history/`), alimenté par `VehicleHistoryRecorder` (1 sample / 5 s) + `RollupAggregator` (rétention étagée : brut 6 h → 1 min 7 j → 30 min 30 j → 1 h 90 j → 6 h, seuils alignés sur les buckets). Les vues Stats lisent **toutes** les couches et les regroupent (`HistoryRepository.rebucket`, même fusion pondérée que le rollup). Schéma exporté dans `android/app/schemas/` (plugin Room) : **tout bump de `version` exige une `@AutoMigration`/`Migration`** ; `fallbackToDestructiveMigration()` n'est qu'un filet (sinon crash en boucle du launcher) et efface tout l'historique.
- **Pleins** : `history/RefuelDetector` (hausse ≥ 12 points tenue 60 s, référence relue en base) → `RefuelEvent(awaitingInput)` → saisie du ticket `ui/components/RefuelPrompt.kt`, **jamais en roulant** → `history/FuelCalibration` (prix = dernier ticket ; capacité = médiane sur ≥ 3 pleins non saturés, appliquée d'un geste dans Réglages ; correction de conso à l'affichage seulement, pour que l'historique garde la valeur de la voiture). Copie de la base avant migration (`history/HistoryBackup`) et export vers Téléchargements (`history/DataExport`).
- **Graphiques Stats** : calculés hors du thread principal dans `StatsViewModel.charts` (`ui/screens/stats/StatsCharts.kt`), recalculés quand la fenêtre change (direct : 30 s), jamais à chaque insert ; dessin en `drawWithCache` ; le curseur passe par `chartScrub` (appui maintenu), pour laisser le défilement et le pager aux gestes immédiats.
- **Compagnon** (`android/companion`, `link/LinkServer`) : pendant Android Auto sans fil, le téléphone récupère l'historique en TLS épinglé (port 8766, `link/TelemetrySource`, `link/TransferSecurity`) et l'écrit dans `Documents/CANCitroen` ; le brut reste 7 j avec un téléphone associé, 30 j s'il n'a pas été reçu ; « Vérifier et récupérer » redemande les manquants. Pendant le trajet, un service au premier plan (`CarSessionService`, rythme dans `SessionPlan`, lancé par le Bluetooth de la voiture, Android Auto ou une tâche de secours) interroge l'Atoto toutes les 30 s et publie dans Home Assistant via `mobile_app` ; Home Assistant et transfert sur leurs propres threads, journal dans l'onglet Suivi. Onglet Voiture : graphiques relus des fichiers (`VehicleHistory`, `ChartPainter`), détail rafraîchi toutes les 30 s par un transfert léger, analyse de conduite (`DrivingAnalysis`), conservation réglable ; écrans Android Auto (`CarStatusService`, modèles Car App, graphiques en image) ; widget (`CarWidget`) ; événements Home Assistant de fin de trajet et de plein (`TripEvents`) ; position de la voiture en option (`GpsTracker.freshFix` sur l'Atoto, GPS gardé 5 min après chaque demande, « localisation toujours autorisée » nécessaire pendant Android Auto). Désactivé par défaut côté Atoto (`docs/app/compagnon.md`, `docs/app/home_assistant.md`).
- **Son moteur** : simulation du trajet du gaz, calée sur de vrais enregistrements (`audio/EngineSoundCore`, Kotlin pur testé en JVM, écoute hors ligne via `*EngineSoundRender`, voir `docs/app/architecture.md`), configurable (`EngineConfig`, `EngineBuilderCard`), jouée par `EngineSoundSynth` sur un thread audio dédié. Pas d'échantillons.
- **Simulateur** (`sim/VehicleSimulator` + modèle de conduite `sim/DrivingModel`) : activé par le réglage `dev.simulatorEnabled`, écrit directement dans `_vehicleState` — c'est le moyen de développer l'UI sans voiture. Il n'écrit dans l'historique que si `dev.simulatorRecorded` est activé.
- **UI** : `MainActivity` = `HorizontalPager` de 7 pages, accueil à l'index 3 (0 EngineSound, 1 Récap, 2 Stats, 3 Accueil, 4 Tableau, 5 EMF, 6 Réglages). ViewModels dans `ui/vm/`. L'app est à l'écran pendant tout le trajet : lire l'état au plus bas (providers `() -> VehicleState`, `derivedStateOf`, cartes à paramètres primitifs), animer en phase de dessin (`graphicsLayer` + `drawWithCache`, `Animatable`), jamais de boucle `withFrameNanos` permanente ; 30 fps suffisent pour les animations continues (horloge).

### Contraintes Atoto / ROM SYU (ne pas casser)

- ROM FYT/SYU, Android 10, rooté Magisk. Launcher d'origine : `ro.fyt.launcher` = `com.android.launcher8`. Le ROM extrait et décompilé (services.jar, apps SYU, manifestes) est dans `~/ATOTORom/extracted/` : le consulter plutôt que supposer un comportement du ROM.
- L'app est déclarée **launcher HOME**. Au boot froid, le ROM rebascule sur son launcher si rien n'est affiché (~3 s) : `App.onCreate`, `MainActivity.onCreate` et `CanService.onCreate` doivent rester rapides. Le travail lourd est différé (`window.decorView.post { startBackgroundWork() }`, `initDeferredComponents()`, chargement audio async).
- Le hot-plug USB passe par l'`activity-alias` `.UsbAttachActivity`, pas par l'activity HOME elle-même.
- Immersif réappliqué à chaque retour de focus (les dialogs système d'Android 10 effacent les flags).
- **Launcher forcé (root)** : `system/LauncherGuard.kt`, armé/désarmé depuis `CanService` selon les réglages. Il fixe le home par défaut et `persist.lsec.launcher` (repli natif du `PackageManagerService` FYT), puis démarre un daemon root détaché `assets/root/home_guard.sh` (+ hook Magisk `assets/root/boot_hook.sh`). Ce daemon ramène l'app quand une **activité HOME concurrente exacte** passe devant (évènements logcat `am_set_resumed_activity`, repli sondage) et relance `CanService` si le process meurt, jamais pendant la veille (wakelock → batterie). Invariants : ne jamais cibler un package entier ni une app autre qu'un launcher. Dans une même commande `su -c`, les motifs `pgrep`/`pkill -f` doivent être écrits `[h]ome_guard`, sinon ils matchent et tuent le `sh -c` lui-même. Journal sur l'appareil : `logcat -s CANCitroenGuard`.

## Décodage CAN — sources de vérité

- **`CanDecoder.kt` est un port 1:1 des `decode_XXX()` de `scripts/bridge.py`.** Toute modification de décodeur se fait des deux côtés, et tout nouveau point de calibration vérifié sur la voiture s'ajoute à `fixtures/can_decode_cases.tsv` (lu par `CanDecoderParityTest` **et** `scripts/test_decoders.py`). Arrondis « au plus proche » des deux côtés (`Math.round` / `round_half_up`, pas le `round()` de Python). Les IDs et constantes véhicule sont dans `decode/CanIds.kt` (réservoir 41 L par défaut, remplacé par le réglage utilisateur dans `CanService`).
- **Référence des signaux : `docs/can/signaux_decodes.md`** (reflète le code, avec le niveau de preuve de chaque signal et les points ouverts). Les notes plus anciennes (`docs/can/c2_field_test_results.md`, 2026-04-26) sont en partie dépassées. ⚠ Formule de la T° extérieure (octet/pente) et odomètre **non validés** : trois hypothèses contradictoires, ne pas « corriger » sans mesure à deux points (procédure dans signaux_decodes.md).
- Sentinelles : `0xFF` (températures, huile comprise) et `0xFFFFFF` (odo, BSI pas encore synchronisée) → `null`, sinon valeurs aberrantes persistées dans Room. Facteur de l'odomètre (÷10) à revérifier contre le compteur : l'ancien point Lexia noté dans `bridge.py` était incohérent.
- Volant = **`0x21F`** (pas `0x131`, qui est radio → changeur CD). Sur cette C2, byte[0] : bit1 SOURCE, bit2 VOL−, bit3 VOL+, bit6 PREV, bit7 NEXT (inversé vs doc PSA générique) ; byte[1] = compteur molette signé.
- Réglage de l'horloge EMF : toutes les pistes testées ont échoué (`0x39B`, `0x276`, `0x228`, `0x122`, …) — lire `docs/can/psa_can_confort.md` §3.c et `docs/can/lexia_diagbox_findings.md` avant de proposer une nouvelle approche.
- Pour une trame non documentée, croiser les projets de référence listés dans `docs/can/psa_can_confort.md` (ludwig-v/arduino-psa-comfort-can-adapter, morcibacsi/PSAVanCanBridge et PSAWifiDisplayControl, prototux/PSA-RE, autowp).

## Matériel

Branchement derrière l'autoradio (Quadlock : pin 10 CAN-H, pin 13 CAN-L) — l'OBD PSA n'expose pas le CAN-Confort. Ne pas activer la terminaison 120 Ω du CANable (bus déjà terminé). Toute émission (TX) part sur le bus réel de la voiture.

## Documentation et arborescence

Index : `docs/README.md` (par thème, avec le statut de chaque document : à jour / historique). `docs/can/` (bus), `docs/terrain/` (sessions voiture : playbook, méthodologie, cheatsheet), `docs/app/` (`architecture.md`, `developpement.md`, `launcher_root.md` + spec d'origine historique), `docs/recherche/` (rapport « contrôle écran sans RD4 », captures Lexia). `captures/` et `dist/` ne sont pas versionnés (voir `.gitignore` unique à la racine). Toute évolution notable de l'app ou des décodeurs doit être reportée dans le document `docs/` concerné.
