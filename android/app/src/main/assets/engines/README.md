# Samples moteur

L'app utilise 3 samples WAV pour produire un bruit de moteur réaliste avec
crossfade + pitch shift. Ils sont **inclus dans le dépôt** : ils sont dans le
domaine public (CC0).

## Fichiers (CC0 — domaine public)

Source : OpenGameArt — *Racing Car Engine Sound Loops*, par **domasx2**, licence
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)
<https://opengameart.org/content/racing-car-engine-sound-loops>

Pour les retélécharger :

| Fichier cible (à placer ici) | URL source |
|---|---|
| `4cyl_idle.wav` | <https://opengameart.org/sites/default/files/loop_0.wav> |
| `4cyl_mid.wav`  | <https://opengameart.org/sites/default/files/loop_2_0.wav> |
| `4cyl_high.wav` | <https://opengameart.org/sites/default/files/loop_5_0.wav> |

```bash
cd app/src/main/assets/engines
wget https://opengameart.org/sites/default/files/loop_0.wav -O 4cyl_idle.wav
wget https://opengameart.org/sites/default/files/loop_2_0.wav -O 4cyl_mid.wav
wget https://opengameart.org/sites/default/files/loop_5_0.wav -O 4cyl_high.wav
```

## Format attendu

L'app charge des **WAV PCM 16-bit mono ou stéréo**. Si tes samples sont en
FLAC / OGG / MP3, convertis-les avec ffmpeg :

```bash
ffmpeg -i mon_sample.flac -ac 1 -ar 22050 -c:a pcm_s16le 4cyl_idle.wav
```

## Comportement

- **Si les 3 fichiers sont présents** : l'app utilise le mode SAMPLES (qualité
  quasi-réaliste, crossfade entre idle/mid/high selon RPM, pitch shift par
  interpolation).
- **Si un fichier manque** : fallback automatique sur la synthèse procédurale
  (qualité plus rugueuse type "synthé"). Voir log `EngineSoundSynth`.

## Pitch shift et profils

Les 3 samples 4-cylindres sont utilisés pour **tous les profils** (V6, V8,
Diesel, etc.). Le pitch est ajusté en fonction du nombre de cylindres et des
RPM cibles du profil. Ce n'est pas parfaitement fidèle (un V8 rendu depuis un
4-cyl sonne plus comme un 4-cyl "grave") mais ça donne 6 timbres distincts
sans avoir à embarquer 18 samples.

Pour un rendu plus fidèle par profil, ajoute des samples spécifiques :
- `v8_idle.wav`, `v8_mid.wav`, `v8_high.wav` (CC-BY-SA disponible mais
  contamine la licence — voir GenericV8Sound sur OpenGameArt)
- `diesel_idle.wav` (CC0 truck idle disponible sur Freesound — qubodup #187564)

Et adapter `EngineSoundSynth.kt` pour mapper le profil au set de samples.
