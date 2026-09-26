# CANCitroen

Rétro-ingénierie du bus **CAN-Confort** d'une **Citroën C2** (PSA AEE2004)
dont l'autoradio d'origine (RD4) a été remplacé par un **Atoto A6PF** sous
Android, et app Android qui en tire parti : commandes au volant, tableau de
bord temps réel, pilotage de l'écran multifonction (EMF), historique des
trajets.

## Matériel

| | |
|---|---|
| Véhicule | Citroën C2 1.1 (TU1JP), BSI AEE2004, EMF Magneti Marelli |
| Bus | CAN-Confort, **125 kbit/s**, identifiants 11 bits |
| Accès | derrière l'autoradio, connecteur Quadlock : pin 10 CAN-H, pin 13 CAN-L (l'OBD n'expose pas ce bus) |
| Adaptateur | **CANable 2.0**, firmware slcan (USB `16d0:117e`), **sans** terminaison 120 Ω (le bus est déjà terminé) |
| Autoradio | Atoto A6PF, Android 10, ROM FYT/SYU, rooté Magisk |

> **⚠ Avertissement.** Projet personnel, sans aucune garantie. Les scripts et
> l'app **émettent des trames sur le bus CAN réel** d'un véhicule et utilisent
> des accès **root** sur l'autoradio : une mauvaise manipulation peut perturber
> des calculateurs, décharger la batterie ou rendre l'autoradio instable. À
> utiliser à vos risques, jamais en roulant pour les essais d'émission. Projet
> indépendant, sans lien avec Stellantis, Citroën, PSA ni Atoto ; les marques
> citées appartiennent à leurs propriétaires.

## Arborescence

```
CANCitroen/
├── android/        app Android (Gradle) — voir android/README.md
├── scripts/        outillage PC : interface, sniff, décodeurs de référence, bridge HTTP
├── web/            dashboard servi par scripts/bridge.py
├── fixtures/       trames de référence partagées par les tests Kotlin et Python
├── docs/           documentation (bus CAN, sessions terrain, app, recherche)
├── captures/       journaux candump des sessions (non versionnés)
├── dist/           APK livrés (non versionnés)
├── CLAUDE.md       consignes pour Claude Code
└── LICENSE         MIT
```

## Démarrage rapide

**Sur la voiture, avec un PC Linux** (détails : [scripts/README.md](scripts/README.md)) :

```bash
./scripts/can_up.sh 125000     # monte slcan0 via slcand
./scripts/bus_health.py        # le bus parle ? bon débit ? IDs PSA reconnus ?
./scripts/bridge.py            # décodage live : http://<pc>:8080
./scripts/can_down.sh
```

Pour une session complète, suivre
[docs/terrain/field_test_playbook.md](docs/terrain/field_test_playbook.md).

**App Android** (détails : [docs/app/developpement.md](docs/app/developpement.md)) :

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Tests des décodeurs** :

```bash
(cd android && ./gradlew :app:testDebugUnitTest)
python3 -m unittest scripts/test_decoders.py
```

## Documentation

Index complet : **[docs/README.md](docs/README.md)**. Les plus utiles :

- [Signaux CAN décodés](docs/can/signaux_decodes.md) — ce que contient chaque trame et à quel point c'est vérifié
- [Architecture de l'app](docs/app/architecture.md)
- [Launcher forcé (root)](docs/app/launcher_root.md)

## Licence et crédits

Code et documentation sous **licence MIT** ([LICENSE](LICENSE)), © 2026 Wasabules.

Éléments tiers :

- sons moteur `android/app/src/main/assets/engines/*.wav` : *Racing Car Engine
  Sound Loops* par domasx2 ([OpenGameArt](https://opengameart.org/content/racing-car-engine-sound-loops)),
  **CC0** ;
- tracés d'icônes `ui/theme/AppIcons.kt` : [Material Icons](https://github.com/google/material-design-icons)
  (Google), **Apache License 2.0** ;
- sons du « mode meme » : **non inclus** (droits inconnus), à télécharger soi-même
  (voir `android/app/src/main/assets/memes/engine/README.md`) ;
- structures de trames et mappings établis à partir de projets publics :
  [ludwig-v/arduino-psa-comfort-can-adapter](https://github.com/ludwig-v/arduino-psa-comfort-can-adapter),
  [morcibacsi/PSAVanCanBridge](https://github.com/morcibacsi/PSAVanCanBridge),
  [morcibacsi/PSAWifiDisplayControl](https://github.com/morcibacsi/PSAWifiDisplayControl),
  [prototux/PSA-RE](https://github.com/prototux/PSA-RE), [autowp](https://autowp.github.io/).

## État

Commandes au volant, tableau de bord, historique et pilotage de l'EMF
fonctionnent. Le réglage de l'horloge de l'EMF par CAN reste impossible à ce
jour. Plusieurs calibrations sont à confirmer sur la voiture (T° extérieure,
odomètre) : voir [docs/README.md § État du projet](docs/README.md#état-du-projet-2026-09).
