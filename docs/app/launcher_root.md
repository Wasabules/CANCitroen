# Launcher forcé (root)

Faire de CANCitroen l'écran d'accueil de l'autoradio **sans jamais gêner les
autres apps** : Android Auto, Maps, les Réglages ou n'importe quelle app
s'ouvrent normalement ; seul le launcher d'origine est remplacé.

Code : `android/app/src/main/java/com/geoffrey/cancitroen/system/LauncherGuard.kt`
et `android/app/src/main/assets/root/` (`home_guard.sh`, `boot_hook.sh`).
Réglage : Réglages → Comportement → « Forcer CANCitroen comme launcher ».
Nécessite l'accès superuser accordé à l'app dans Magisk.

## Ce que fait le ROM (analyse de `~/ATOTORom/extracted/`)

ROM FYT/SYU, Android 10 (`QP1A.190711.020`). Launcher d'origine :
`ro.fyt.launcher` = `com.android.launcher8` (autres homes : `launcher6`,
`launcher3`).

- **Résolution de HOME** (`services.jar`, `PackageManagerService.chooseBestActivity`,
  modifié par FYT) : Android prend d'abord l'activité **préférée**
  (celle que fixe `cmd package set-home-activity`). Sans préférence, le ROM
  choisit le package de `persist.lsec.launcher`, à défaut `ro.fyt.launcher`.
  Or Android invalide la préférence HOME dès qu'un nouveau home apparaît
  (installation ou mise à jour d'app) : le ROM retombe alors sur launcher8.
- **`com.syu.us`** (service système) relance HOME par un intent **implicite** :
  il va donc au home préféré, pas à un composant fixe.
- **`ActivityManagerService.lsecStartAllow`** : les démarrages en arrière-plan
  des packages `com.android.launcher*` (sauf `launcher3`) ne sont autorisés
  que pour le launcher configuré (`persist.lsec.launcher`, à défaut
  `ro.fyt.launcher`).
- **Homes système à ne jamais toucher**, relevés dans la ROM : FallbackHome
  (Réglages, priorité −1000), `com.android.provision`, `managedprovisioning`,
  `com.google.android.gms` (écrans Family Link), appli Google (alias GEL),
  `com.google.android.apps.restore`, `com.sprd.powersavemodelauncher`.

## Mécanisme

`LauncherGuard.arm()` (rejoué à chaque démarrage de `CanService`, idempotent) :

1. **Mode agressif** (option) : `pm disable` de l'**activité** HOME des
   launchers concurrents — jamais d'un package entier. Réversible.
2. **Home par défaut** : `cmd package set-home-activity` → CANCitroen (après
   les désactivations : Android mémorise l'ensemble des homes avec la
   préférence).
3. **Launcher natif FYT** : `setprop persist.lsec.launcher com.geoffrey.cancitroen`.
   Quand la préférence saute, le ROM retombe sur CANCitroen et non plus sur
   launcher8. La valeur d'origine est sauvegardée.
4. **Garde root** : daemon `home_guard.sh` lancé via `setsid` (détaché, survit
   à la mort de l'app), redémarré seulement si son script ou sa config ont
   changé :
   - surveille les reprises d'activité par évènements logcat
     (`am_set_resumed_activity` sur Android 10, `wm_set_resumed_activity`
     ensuite) ; repli par sondage `dumpsys` (1 s) si le ROM n'émet pas ces
     évènements ;
   - si l'activité au premier plan est **exactement** un composant HOME
     concurrent (confirmé par `dumpsys`), ramène CANCitroen (intent HOME) ;
   - anti ping-pong : au plus 5 retours par fenêtre de 20 s, puis pause de 30 s
     (signalée dans l'UI) ;
   - **keepalive** : si le process de l'app a disparu et que l'unité est
     **éveillée**, relance `CanService` (tentatives espacées jusqu'à 5 min en
     cas d'échec répété). Jamais pendant la veille : le wakelock de
     `CanService` empêcherait l'autoradio de s'endormir (batterie) ;
   - si l'app est désinstallée sans désarmement : réactive ce qui avait été
     désactivé, restaure `persist.lsec.launcher`, supprime le hook et s'arrête.
5. **Démarrage au boot** : hook `/data/adb/service.d/cancitroen_guard.sh`
   (Magisk/KernelSU), qui lance le garde indépendamment de l'app.

`disarm()` (option décochée) : arrête le garde, supprime le hook, réactive les
composants désactivés, restaure `persist.lsec.launcher`, rend le home au
launcher du ROM. Aucun appel `su` si le garde n'a jamais été armé (marqueur
`launcher_guard_armed` dans les fichiers de l'app).

## Fichiers sur l'appareil

| Chemin | Contenu |
|---|---|
| `/data/local/tmp/cancitroen/home_guard.sh` | script du garde (réécrit à chaque armement) |
| `/data/local/tmp/cancitroen/guard.conf` | config générée : app, composants concurrents (formes courte et longue), intervalles |
| `/data/local/tmp/cancitroen/enabled` | présent = garde armé (la boucle s'arrête s'il disparaît) |
| `/data/local/tmp/cancitroen/version` | empreinte script + config |
| `/data/local/tmp/cancitroen/disabled` | composants désactivés par le mode agressif |
| `/data/local/tmp/cancitroen/lsec_launcher.orig` | valeur d'origine de `persist.lsec.launcher` |
| `/data/local/tmp/cancitroen/run/` | état lu par l'UI : `mode`, `since`, `catches`, `last_catch`, `revives`, `last_revive`, `last_conflict` |
| `/data/adb/service.d/cancitroen_guard.sh` | hook de boot |

Le dossier est en `0700` (root). Les fichiers de l'ancien garde (≤ 0.1.22,
`/data/local/tmp/cancitroen_home_guard.*`) sont migrés puis supprimés
automatiquement.

## Diagnostic

```bash
adb logcat -s CANCitroenGuard LauncherGuard           # actions du garde et de l'app
adb shell su -c 'cat /data/local/tmp/cancitroen/guard.conf'
adb shell su -c 'for f in /data/local/tmp/cancitroen/run/*; do echo "${f##*/}=$(cat $f)"; done'
adb shell su -c "pgrep -fl '/data/local/tmp/cancitroen/[h]ome_guard'"
adb shell getprop persist.lsec.launcher               # com.geoffrey.cancitroen attendu
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
```

L'écran Réglages → Comportement affiche : root, état du garde (détection
instantanée ou sondage), home par défaut, launcher du ROM, hook de boot,
nombre de retours forcés et de relances, alerte de conflit.

Arrêt : décocher l'option. Un arrêt forcé de l'app ne suffit pas (le keepalive
la relance).

## Invariants à respecter

- Ne cibler que des **composants HOME exacts**, jamais un package entier, et
  jamais un package de la liste `PROTECTED_PKGS`.
- Dans une même commande `su -c`, écrire les motifs `pgrep`/`pkill -f` avec un
  crochet (`[h]ome_guard`, `[s]et_resumed_activity`) : sinon le motif, présent
  dans la ligne de commande du `sh -c`, le fait se tuer lui-même. C'est ce qui
  empêchait l'ancien garde (≤ 0.1.22) de démarrer.
- Ne rien relancer pendant la veille de l'autoradio.
- Tout changement doit laisser l'app au premier plan pendant un trajet.

## Validation

Testé sur émulateur Android 16 (sans root, en shell) : rattrapage du launcher
concurrent en moins de 1,5 s ; Réglages, Maps, Chrome, YouTube et dialogues
de permission jamais interceptés ; anti ping-pong ; mode sondage ; keepalive
(rien pendant la veille, backoff) ; auto-nettoyage à la désinstallation ; arrêt
propre. **Pas encore validé sur l'Atoto** : `set-home-activity`,
`persist.lsec.launcher`, `pm disable`, hook Magisk et relance de `CanService`
en root.
