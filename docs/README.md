# Documentation

## Par où commencer

| Besoin | Document |
|---|---|
| Savoir ce que chaque trame CAN contient, et à quel point c'est vérifié | [can/signaux_decodes.md](can/signaux_decodes.md) |
| Préparer une session sur la voiture | [terrain/field_test_playbook.md](terrain/field_test_playbook.md) |
| Comprendre l'app Android | [app/architecture.md](app/architecture.md) |
| Construire, tester, livrer l'app | [app/developpement.md](app/developpement.md) |
| Le launcher forcé par root | [app/launcher_root.md](app/launcher_root.md) |
| Utiliser les scripts PC | [../scripts/README.md](../scripts/README.md) |

## Contenu

### `can/` — le bus CAN-Confort

| Fichier | Contenu | Statut |
|---|---|---|
| [signaux_decodes.md](can/signaux_decodes.md) | référence des trames décodées / émises, niveaux de preuve, points ouverts | **à jour** (reflète le code) |
| [psa_can_confort.md](can/psa_can_confort.md) | IDs PSA AEE2004 connus, projets de référence, tentatives de réglage de l'horloge | référence de fond |
| [lexia_diagbox_findings.md](can/lexia_diagbox_findings.md) | session Lexia/Diagbox du 2026-05-03 : identification des calculateurs, valeurs de vérité, télécodage RD4 | journal de session |
| [c2_field_test_results.md](can/c2_field_test_results.md) | résultats du premier field test (2026-04-26) | **historique** : en partie dépassé, voir signaux_decodes.md |

### `terrain/` — sessions sur la voiture

| Fichier | Contenu |
|---|---|
| [field_test_playbook.md](terrain/field_test_playbook.md) | check-list pas à pas, du branchement à la validation |
| [methodologie.md](terrain/methodologie.md) | méthodes pour isoler une commande (diff, isolation interactive, sniff live) |
| [cheatsheet.md](terrain/cheatsheet.md) | commandes can-utils du quotidien |

### `app/` — l'app Android

| Fichier | Contenu | Statut |
|---|---|---|
| [architecture.md](app/architecture.md) | pipeline CAN, service, état, volant, EMF, historique, UI | **à jour** |
| [developpement.md](app/developpement.md) | chaîne d'outils, commandes, tests, livraison, déploiement, migrations Room | **à jour** |
| [launcher_root.md](app/launcher_root.md) | launcher forcé : analyse du ROM FYT, mécanisme, fichiers, diagnostic | **à jour** |
| [atoto_a6pf_integration.md](app/atoto_a6pf_integration.md) | options d'architecture envisagées (bridge PC, app, ESP32) | réflexion initiale |
| [android_app_spec.md](app/android_app_spec.md) | spécification d'origine de l'app | **historique** : l'app a évolué, voir architecture.md |

### `recherche/`

| Fichier | Contenu |
|---|---|
| [controle_ecran_sans_rd4.md](recherche/controle_ecran_sans_rd4.md) | rapport de recherche : contrôler l'EMF sans autoradio RD4 (Quadlock, trames, horloge) |
| [lexia/](recherche/lexia/) | captures d'écran Lexia/Diagbox de la session du 2026-05-03 |

## Sources de vérité

1. **Le code** : `CanDecoder.kt` et `scripts/bridge.py`, tenus alignés par
   `fixtures/can_decode_cases.tsv`. En cas de désaccord avec une note, le code
   et [signaux_decodes.md](can/signaux_decodes.md) priment.
2. Pour une trame non documentée : croiser les projets listés dans
   [psa_can_confort.md](can/psa_can_confort.md) (ludwig-v en premier).
3. Pour le comportement du ROM de l'Atoto : la ROM extraite et décompilée dans
   `~/ATOTORom/extracted/` (hors de ce dépôt).

## État du projet (2026-09)

- ✅ Commandes au volant décodées et relayées vers Android.
- ✅ Tableau de bord temps réel, historique, statistiques.
- ✅ Pilotage de l'EMF par émulation RD4 (`0x3E5`), BSI télécodée « RD4 présent ».
- ❌ Réglage de l'horloge de l'EMF par CAN : aucune piste n'a fonctionné.
- ⚠ À valider sur la voiture : formule de la T° extérieure, odomètre, pentes
  des températures, vitesse en roulant — voir
  [signaux_decodes.md § Points ouverts](can/signaux_decodes.md#points-ouverts).
- ⚠ À valider sur l'Atoto : launcher forcé root, app 0.1.25 (toolchain à jour).
