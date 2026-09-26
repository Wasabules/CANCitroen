# Memes-moteur — sons courts joués par cycle de combustion

> **Les fichiers `.mp3` ne sont pas versionnés** : ils viennent de myinstants,
> sans licence claire, et certains sont des sons de marques (Apple Pay,
> Discord). Chacun les télécharge pour son usage personnel avec les commandes
> ci-dessous. Sans eux, l'app masque simplement le mode meme. Ne pas publier
> un APK qui les contient.

Ce dossier est différent de `assets/memes/` :
- `assets/memes/` → soundboard, joué à la demande au tap
- `assets/memes/engine/` → utilisé comme **moteur de simulation**, joué
  à chaque cycle de combustion (RPM/60 × cyl/2), capé à 30 Hz max

## Sons recommandés (bruts, courts, % impactants)

L'idée est d'avoir des **sons très courts** (50–200 ms idéalement) pour que
le rythme reste lisible au ralenti et pas trop chaotique en haute RPM.

| Slug | URL myinstants |
|---|---|
| `fart` | <https://www.myinstants.com/en/instant/fart/> |
| `apple-pay` | <https://www.myinstants.com/en/instant/apple-pay-45496/> |
| `fahhh` | <https://www.myinstants.com/en/instant/fahhh-42300/> |
| `buzzer` | <https://www.myinstants.com/en/instant/buzzer-89244/> |
| `discord-notification` | <https://www.myinstants.com/en/instant/discord-notification-38119/> |
| `shocked` | <https://www.myinstants.com/en/instant/shocked-sound-37548/> |

## Commandes

```bash
cd app/src/main/assets/memes/engine

# Sur la page myinstants, le bouton orange "Download MP3" ; clic-droit → copier le lien
# OU inspecter et trouver data-url-mp3
# Exemples (URLs à vérifier — myinstants change parfois ses chemins) :
wget "https://www.myinstants.com/media/sounds/fart.mp3" -O fart.mp3
wget "https://www.myinstants.com/media/sounds/apple-pay.mp3" -O apple-pay.mp3
wget "https://www.myinstants.com/media/sounds/fahhh.mp3" -O fahhh.mp3
wget "https://www.myinstants.com/media/sounds/buzzer_lh4MwO0.mp3" -O buzzer.mp3
wget "https://www.myinstants.com/media/sounds/discord-notification.mp3" -O discord-notification.mp3
wget "https://www.myinstants.com/media/sounds/shocked-sound-effect.mp3" -O shocked.mp3
```

Si une URL ne marche pas, ouvre la page myinstants, trouve l'URL exacte du
mp3 (souvent affichée dans le bouton "Download" ou dans `<source src="...">`).

## Comportement

- Sélectionne un meme dans la page **🔊 Engine Sound** → section "🤡 Mode meme"
- Le son joue à chaque cycle moteur :
  - Idle 800 RPM (4 cyl) → 13 trig/sec (rythme rapide mais lisible)
  - 3000 RPM → 50 → cap à 30 → bourdonnement de memes
  - 5000+ RPM → bourdon constant, le pitch monte légèrement
- Volume monte avec les RPM
- Tu peux re-tap "Choisir une motorisation" (un profil normal) pour repasser
  en mode synth standard, OU re-tap le meme actif pour le désactiver

## Conseils

- **Sons très courts** (< 150 ms) sont plus lisibles à haute RPM
- **Sons d'impulsion** (boom, click, fart, buzz) marchent mieux que des
  sons mélodiques (qui s'overlapent et créent du bruit)
- **Discord notification** ou **Shocked** : drôles aux changements de régime
- **Fart** : indispensable
