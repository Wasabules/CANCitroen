# Intégration Atoto A6PF — architecture cible

> **Réflexion initiale** sur les options d'intégration. Option retenue :
> l'app Android tout-en-un avec le CANable branché sur l'Atoto (option B),
> décrite dans [architecture.md](architecture.md).

L'A6PF est une **tablette Android native** (Android 10/12 selon firmware) avec :
- USB host (2 ports USB-A sur le faisceau arrière)
- WiFi + BT (BLE compris)
- Sideload APK trivial (Settings → "Apps" → autoriser source inconnue)
- Android Auto en tant qu'**app embarquée** : quand tu sors du mode AA, tu reviens
  sur le launcher Atoto et tu peux lancer n'importe quelle app installée

→ **Toutes les options sont sur la table.** Le choix se fait sur les axes
"effort de développement" vs "propreté du résultat final".

---

## Option A — Bridge Python externe (PC dans la voiture)

```
[Voiture CAN] → CANable USB → [PC sous le siège] → WiFi → [Atoto WebView]
```

- ✅ Zéro Android, on bricole en Python
- ✅ Itération rapide (modifier le code, redémarrer le daemon)
- ❌ PC + alim 12V dans la voiture, c'est moche
- ❌ Démarrage long au boot

**Statut** : c'est ce qu'on vient de construire (`scripts/bridge.py`). **Idéal pour
la phase de développement et de découverte des trames C2 spécifiques.**

---

## Option B — App Android tout-en-un sur l'Atoto (CANable plugué dessus)

```
[Voiture CAN] → CANable USB → [Atoto USB host] → [App Android dédiée]
                                                       |
                                                       ├── usb-serial-for-android
                                                       ├── parseur slcan natif
                                                       ├── décodeur CAN
                                                       ├── WebView dashboard
                                                       └── Service Accessibility (SWC keys)
```

- ✅ Setup final propre : CANable + un câble, c'est tout
- ✅ Pas de PC, pas de WiFi à gérer
- ✅ Démarre en même temps que l'Atoto
- ❌ **Faut écrire une app Android** (Kotlin + `usb-serial-for-android`)
- ❌ Réécrire le parseur slcan en Kotlin (~150 lignes mais bon)
- ❌ Pour injecter les boutons volant comme touches système : nécessite un
  **Service d'Accessibilité** Android (permission un peu intrusive, à activer
  manuellement par l'utilisateur — pas un blocker mais c'est de l'UX en moins)

**Effort** : 2-3 jours pour quelqu'un à l'aise en Android. Plus si tu pars de 0.

---

## Option C — ESP32-S3 + petite app Android *(recommandé pour la version finale)*

```
[Voiture CAN] → SN65HVD230 → [ESP32-S3 firmware perso] → USB → [Atoto]
                                                          ╱       ╲
                                              USB HID            USB CDC
                                              (clavier)         (data)
                                                ↓                  ↓
                                           Atoto voit          Petite app
                                           VOL+/VOL-/etc       Android lit
                                           comme touches       les métriques
                                           clavier nativement  via série
```

- ✅ **Boutons volant gérés sans une seule ligne d'Android** (HID natif)
- ✅ App Android minimale : lit du JSON sur série USB, l'affiche en WebView
- ✅ Pas de Service d'Accessibilité, pas de permissions intrusives
- ✅ Composant final < 15 € (ESP32-S3 ~5 €, transceiver SN65HVD230 ~3 €, boîtier 5 €)
- ✅ Démarrage instantané, pas de question de driver
- ❌ Faut flasher un firmware ESP32 (Arduino IDE ou ESP-IDF)
- ❌ Faut quand même une petite app Android pour les métriques

**Effort** : 1 jour ESP32 + 1 jour app Android minimale. **Meilleur ratio
effort/résultat à long terme.**

---

## Option D — ESP32 standalone WiFi/BT, pas d'app Android du tout

```
[Voiture CAN] → ESP32 → WiFi AP → Atoto (navigateur Chrome plein écran)
                                       └ ouvre http://192.168.4.1
```

- ✅ Aucune app Android, juste Chrome qu'on met en favori
- ✅ Setup ultime côté propreté code
- ❌ Pour les boutons volant, il FAUT quand même un chemin USB HID, donc tu
  finis par retomber sur l'option C pour cette partie
- ❌ Chrome n'a pas de "kiosk mode" simple sur Atoto, l'UX d'ouverture est moins bonne

---

## Recommandation : trajectoire en 3 phases

### Phase 1 — Découverte (en cours, sur PC)
- CANable + PC + bridge Python
- Découvrir les IDs réels de **TA** C2 (les IDs PSA AEE2004 sont des points de départ)
- Valider tous les décodeurs avec `scripts/decode_live.py` ou le dashboard
- Corriger `scripts/bridge.py` au fur et à mesure

### Phase 2 — POC tablette (sans hardware nouveau)
- Sideloader Termux sur l'Atoto
- Y faire tourner **le même `bridge.py`** (Python via Termux + python-can en mode slcan via usb-serial)
- **Option** : ouvrir `localhost:8080/` dans Chrome sur l'Atoto
- Permet de valider que toute la pipe marche avec la voiture en environnement réel

### Phase 3 — Install final propre (option C ou B)
- Soit l'app Android dédiée (option B) — 100% Android, tout-en-un
- Soit ESP32-S3 + mini-app (option C) — plus modulaire, boutons volant via HID natif

**À ce stade, tout le travail Python phase 1 sert de spécification** : le
schéma JSON du WebSocket, les décodeurs, les payloads d'émission (0x39B, 0x21F)
sont stables et se transposent ligne à ligne.

---

## Détails techniques utiles pour l'app Android

### Lecture CANable depuis Android
- Le CANable apparaît comme `/dev/ttyACM0` (CDC-ACM) avec VID `16D0` PID `117E`
- Lib : `mik3y/usb-serial-for-android` (référence Android)
- Manifest : `<uses-feature android:name="android.hardware.usb.host" />` + intent-filter
- Le firmware slcan parle un protocole texte : `O\r` (open), `S4\r` (125k), `t1230102\r` (frame), …
- **Bibliothèque utile** : pas de port officiel slcan en Kotlin, faut le parser à la main (~150 LOC)

### Injection des boutons volant comme touches
- API officielle : `AccessibilityService` (intrusif, activation manuelle)
- API non-officielle : `Instrumentation.sendKeyDownUpSync` (bloqué hors app courante depuis Android 4.x)
- API root : `input keyevent KEYCODE_VOLUME_UP` (suppose root, l'Atoto est rootable mais
  tu perds les OTA)
- **Sur un Atoto rooté** : root + `input keyevent` → cleanest. Plein de carPC l'utilisent.
- **Sans root** : Service Accessibility, fonctionne mais paramétrage manuel

### Régler l'horloge système Android depuis le CAN
- `0x276` (BSI broadcast date/heure) → app Android lit → `AlarmManager.setTime(epoch)`
  → nécessite **permission `SET_TIME`** = système-only **OU root**
- Astuce sans root : forcer l'app à régler son propre clock interne et l'afficher,
  l'horloge système reste sur l'heure GPS/NTP

### App Android — squelette minimum
```
MyCarPCApp/
├── app/src/main/
│   ├── AndroidManifest.xml          (permissions + USB + AccessibilityService)
│   ├── java/.../MainActivity.kt     (WebView fullscreen)
│   ├── java/.../CanService.kt       (foreground service, lit CANable)
│   ├── java/.../SlcanParser.kt      (~150 LOC)
│   ├── java/.../CanDecoder.kt       (porte 1:1 du Python actuel)
│   ├── java/.../HttpServer.kt       (NanoHTTPD, sert dashboard.html)
│   └── assets/dashboard.html        (le MÊME fichier que web/dashboard.html)
└── build.gradle (deps: usb-serial-for-android, NanoHTTPD)
```

L'app minimale = ~600 LOC total. **Le fichier `dashboard.html` est mutualisé.**

---

## Réponse courte à "ce serait mieux une app Android directement"

**Oui, à terme.** Mais pas pour démarrer :

| Aspect | Bridge Python (maintenant) | App Android (final) |
|--------|---------------------------|--------------------|
| Itération sur les décodeurs | secondes | minutes |
| Découverte de nouveaux IDs C2 | trivial | pénible |
| Install voiture | sale (laptop) | propre |
| Démarrage | manuel | auto |
| Effort initial | 0 (déjà fait) | 1-3 jours |
| Code dashboard | `web/dashboard.html` | `assets/dashboard.html` (identique) |
| Logique de décodage | `scripts/bridge.py` | port direct en Kotlin |

**→ On garde le bridge Python pour la phase exploratoire (qui n'est PAS finie :
on n'a même pas encore branché sur ta C2). Quand les protocoles sont gravés
dans le marbre, on porte sur Android. Tout le travail actuel sert de
spécification exécutable.**
