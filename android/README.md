# CANCitroen — app Android

App Kotlin / Jetpack Compose pour l'autoradio **Atoto A6PF** (Android 10) :
lit le bus CAN-Confort de la Citroën C2 via un CANable 2.0 en USB, sert
d'écran d'accueil (tableau de bord, raccourcis), relaie les commandes au
volant, pilote l'écran multifonction (EMF) et tient un historique des trajets.

## Prérequis

- Android Studio **Quail 2** (2026.1.2) ou plus récent — le projet utilise
  AGP 9.3 (AGP 9.4+ exigerait Quail 4)
- JDK 17+ ; SDK Platform **37** (compileSdk) ; Gradle 9.8 (wrapper fourni)
- Atoto A6PF (Android 10, minSdk 29) ; root Magisk pour le launcher forcé

## Construire et installer

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

L'APK est signé par `keystore/cancitroen.keystore` s'il est présent : clé
**locale, non versionnée**, identique à celle de toutes les versions déjà
installées (l'installation par-dessus conserve l'historique et les réglages).
Sans elle, la clé debug standard de la machine est utilisée — voir
[développement](../docs/app/developpement.md#livraison).

## Documentation

- [Architecture](../docs/app/architecture.md) — pipeline CAN, service, historique, UI
- [Développement](../docs/app/developpement.md) — outils, tests, livraison, migrations Room
- [Launcher forcé (root)](../docs/app/launcher_root.md)
- [Signaux CAN décodés](../docs/can/signaux_decodes.md)
