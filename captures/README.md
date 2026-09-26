# Captures (`captures/`)

Journaux de bus et de sessions produits par les scripts. **Contenu non
versionné** (seul ce fichier l'est) : ce sont des données brutes de la
voiture, volumineuses et propres à chaque session.

| Fichier | Produit par | Format |
|---|---|---|
| `<AAAAMMJJ_HHMMSS>_<label>.log` | `scripts/capture.sh` | `candump -L` : `(horodatage) slcan0 ID#DONNÉES`, rejouable avec `canplayer` ou `scripts/bridge.py --replay` |
| `rd4_tests_<AAAAMMJJ_HHMMSS>.log` | `scripts/rd4_test_menu.py` | résultats des tests d'émulation RD4 (texte) |

Bonnes pratiques :

- nommer le label d'après l'action (`reference`, `vol_plus`, `ext_temp_froid`) ;
- noter à côté la valeur de vérité (affichage EMF, compteur, Lexia) : c'est
  ce qui permet d'ajouter un point de calibration dans
  `fixtures/can_decode_cases.tsv` ;
- garder au moins une capture « trajet complet » par session : elle sert de
  jeu de rejeu pour le bridge et de référence pour les décodeurs.
