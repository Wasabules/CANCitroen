# App Android — développement

Construire, tester, livrer et déployer l'app. Architecture :
[architecture.md](architecture.md).

## Chaîne d'outils

| Outil | Version | Remarque |
|---|---|---|
| JDK | 17+ (21 installé) | |
| Gradle | 9.8.0 | wrapper fourni (`android/gradlew`) |
| Android Gradle Plugin | 9.3.3 | **maximum ouvrable par Android Studio Quail 2** (2026.1.2) ; 9.4+ exige Quail 4 |
| Kotlin | 2.4.20 | intégré à AGP 9 (voir ci-dessous) |
| KSP | 2.3.12 | processeur Room |
| SDK | compileSdk **37** (Android 17, API 37.0), minSdk 29, targetSdk 36 | compileSdk 37 imposé par androidx.core 1.19 ; sans effet sur l'Atoto (Android 10) |
| Compose | BOM 2026.09.00 (Compose 1.12, Material3 1.4) | versions fixées **par la BOM uniquement** |

Versions centralisées dans `android/gradle/libs.versions.toml`.

**AGP 9 intègre Kotlin** : ne pas appliquer `org.jetbrains.kotlin.android` dans
`app/build.gradle.kts`. Il n'est déclaré `apply false` dans le build racine
que pour fixer la version de Kotlin. Les options Kotlin se règlent dans
`kotlin { compilerOptions { … } }`.

## Commandes

Depuis `android/` :

```bash
./gradlew :app:compileDebugKotlin                      # compilation rapide
./gradlew :app:testDebugUnitTest                       # tests JVM
./gradlew :app:testDebugUnitTest --tests '*SlcanParserTest'   # une seule classe
./gradlew :app:assembleDebug                           # APK debug (debuggable, lent)
./gradlew :app:assembleRelease                         # APK à installer (R8, ~3 Mo)
```

Sorties : `app/build/outputs/apk/{debug,release}/`.

## Tests

| Test | Ce qu'il vérifie |
|---|---|
| `decode/CanDecoderParityTest` | décode chaque trame de `fixtures/can_decode_cases.tsv` et compare à la valeur attendue |
| `usb/SlcanParserTest` | parseur slcan : trames standard/étendues, découpage entre lectures, lignes invalides, débordement, encodage |
| `history/RebucketTest` | regroupement des couches d'historique pour les vues Stats (pondération, ordre, bords de buckets) |
| `scripts/test_decoders.py` | mêmes trames de référence, côté `bridge.py` |

```bash
cd android && ./gradlew :app:testDebugUnitTest
python3 -m unittest scripts/test_decoders.py          # depuis la racine
```

**Trames de référence** (`fixtures/can_decode_cases.tsv`) : une ligne par
couple (trame, champ), avec le nom du champ côté Kotlin et côté Python, la
valeur attendue et sa source. Les valeurs attendues viennent de mesures
(Lexia, field test) ou des structures de référence, **jamais de la sortie du
code**. Tout nouveau point de calibration vérifié sur la voiture s'y ajoute :
les deux décodeurs sont alors testés contre lui. Gradle déclare le fichier comme
entrée des tests (ils sont relancés quand il change).

## Livraison

1. Incrémenter **ensemble** `versionCode` et `versionName` (`0.1.<versionCode>`)
   dans `android/app/build.gradle.kts`.
2. `./gradlew :app:testDebugUnitTest :app:assembleRelease`.
3. Copier `app/build/outputs/apk/release/app-release.apk` vers
   `dist/CANCitroen-v<versionName>-<AAAAMMJJ-HHMM>.apk` (`dist/` n'est pas
   versionné).

**Signature** : si `android/keystore/cancitroen.keystore` existe, debug et
release sont signés avec ; sinon, avec la clé debug standard de la machine.
Ce fichier est **local et jamais versionné** (`.gitignore`) : c'est une copie
de la clé debug qui a signé toutes les versions installées sur l'Atoto
(vérifié de la 0.1.0 à la 0.1.25 ; mot de passe `android`, alias
`androiddebugkey`), et c'est aussi la clé de tous les builds debug de la
machine — la publier permettrait de signer des APK à sa place.

Mise en place sur une nouvelle machine : copier cette clé depuis l'ancienne
(ou sa sauvegarde) vers `android/keystore/cancitroen.keystore`. Ne jamais en
changer : une autre clé oblige à désinstaller l'app sur l'Atoto, ce qui efface
l'historique et les réglages. Un clone sans cette clé construit un APK
installable seulement à la place d'une app signée avec la même clé debug.

**R8** : l'app n'utilise aucune réflexion. usb-serial-for-android en utilise
(chargement des pilotes) mais embarque sa règle `-keep`. Pour vérifier qu'un
build release garde les pilotes :

```bash
mkdir -p /tmp/apk && unzip -o -q app/build/outputs/apk/release/app-release.apk 'classes*.dex' -d /tmp/apk
for d in /tmp/apk/classes*.dex; do ~/Android/Sdk/build-tools/36.1.0/dexdump "$d"; done \
  | grep -c "Class descriptor.*Lcom/hoho/android/usbserial/driver/"    # > 0 attendu (28 en 0.1.25)
```

## Déploiement sur l'Atoto

```bash
adb connect <IP-Atoto>:5555          # ADB Wi-Fi (activé par le kit root, cf. ~/ATOTORom/root_kit)
adb install -r dist/CANCitroen-v0.1.x-….apk
adb logcat -s CanService CanableSerial SteeringWheelHandler LauncherGuard CANCitroenGuard HistoryRecorder Rollup
```

`install -r` conserve la base et les réglages (même signature). Un APK debug
peut remplacer une release et inversement, tant que le `versionCode` n'est pas
inférieur.

## Émulateur

AVD `Medium_Phone_API_36.1` (Android 16, image Play Store, sans root) :

```bash
~/Android/Sdk/emulator/emulator -avd Medium_Phone_API_36.1 -read-only -no-snapshot
```

`-read-only` n'enregistre rien dans l'AVD. Puis Réglages → Dev → « Mode
simulateur » pour avoir des données. Limites : pas de CANable, pas de root
(le launcher forcé affiche « Root indisponible »), rendu graphique logiciel
(les mesures de performance ne valent qu'en relatif), simulateur à 2 Hz.

## Base Room : migrations

La base est versionnée (`@Database(version = N)` dans `HistoryDatabase.kt`) et
son schéma exporté dans `android/app/schemas/` par le plugin Room.

Pour toute modification d'une entité :

1. incrémenter `version` ;
2. ajouter `autoMigrations = [AutoMigration(from = N, to = N + 1)]` (ou une
   `Migration` manuelle si Room ne sait pas déduire le changement) ;
3. construire : Room génère `N+1.json` à côté de `N.json` — versionner les deux.

`fallbackToDestructiveMigration(dropAllTables = true)` reste en place comme
**filet** : si une migration manque, l'historique est effacé plutôt que de
faire planter en boucle le launcher. Ce n'est pas un mode de fonctionnement
normal.

## Pièges connus

- **Démarrage à froid** : ne rien ajouter de lent dans `App.onCreate`,
  `MainActivity.onCreate` ou `CanService.onCreate` (voir
  [architecture](architecture.md#démarrage-à-froid-atoto)).
- **Compose** : pas de `try`/`runCatching` autour d'un appel `@Composable`
  (erreur du compilateur Compose) ; protéger seulement l'accès non composable.
- **Performance UI** : l'état brut change 20 à 50 fois par seconde ; l'UI doit
  consommer `uiState` (10 Hz) et lire l'état au plus bas.
- **Launcher forcé** : dans une commande `su -c`, écrire les motifs
  `pgrep`/`pkill -f` avec un crochet (`[h]ome_guard`), sinon ils tuent le
  `sh -c` lui-même (voir [launcher_root.md](launcher_root.md)).
