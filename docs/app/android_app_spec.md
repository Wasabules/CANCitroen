# Spécification — App Android pour Atoto A6PF (Citroën C2)

> **Spécification d'origine** (mai 2026), conservée pour mémoire. L'app a
> évolué depuis (UI Compose, historique Room, launcher forcé root…) :
> voir [architecture.md](architecture.md).

Tout ce qu'il faut savoir pour porter le bridge Python en application Android
qui tournera sur l'autoradio Atoto à demeure dans la voiture.

> Le code Python actuel (`scripts/bridge.py` + `web/dashboard.html`) est la
> **spécification exécutable**. Tout ce qui marche en Python doit fonctionner
> à l'identique en Kotlin/Android. Les structures de données et les formules
> de décodage sont validées sur la voiture du user (2026-04-26 → 2026-05-09).

---

## 1. Architecture cible

```
┌────────────────────────────┐
│   Citroën C2 — bus CONF   │
│   125 kbit/s, slcan        │
└──────────┬─────────────────┘
           │ CAN-H, CAN-L (T-tap derrière façade autoradio)
           │
┌──────────▼─────────────────┐
│   CANable v2 (USB-C)       │ — déjà en place
│   firmware slcan            │
└──────────┬─────────────────┘
           │ USB-C
           │
┌──────────▼─────────────────────────────────────┐
│   ATOTO A6PF (Android natif)                   │
│   ┌─────────────────────────────────────────┐  │
│   │ App "CANCitroen" (Kotlin / Java)        │  │
│   │  ├─ ForegroundService (persistant)      │  │
│   │  ├─ USB-Serial driver (slcan parser)    │  │
│   │  ├─ CAN decoder (port direct de bridge) │  │
│   │  ├─ EMF release-loop (15 Hz)            │  │
│   │  ├─ HTTP/WS server local (NanoHTTPD)    │  │
│   │  └─ UI : WebView vers dashboard.html    │  │
│   └─────────────────────────────────────────┘  │
│   + AccessibilityService (optionnel)            │
│     pour injection touches volant → keycodes    │
└─────────────────────────────────────────────────┘
```

**Pas d'ESP32, pas de Pi externe.** L'A6PF se débrouille avec son CPU et le
CANable USB-C plugué dessus.

---

## 2. Connectivité CANable sur Atoto

### Détection USB
- **VID/PID** : `16D0:117E` (MCS CANable2 firmware slcan)
- Apparaît comme **CDC-ACM** → device serial sur Android (`/dev/ttyACM0` côté Linux,
  géré par `usb-serial-for-android` côté Android)
- **Permission requise** dans `AndroidManifest.xml` :

  ```xml
  <uses-feature android:name="android.hardware.usb.host" />
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
  ```

  Et un intent-filter pour le device :

  ```xml
  <activity ...>
    <intent-filter>
      <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
               android:resource="@xml/usb_device_filter" />
  </activity>
  ```

  `res/xml/usb_device_filter.xml` :
  ```xml
  <resources>
    <usb-device vendor-id="5840" product-id="4478" />
    <!-- 5840 = 0x16D0, 4478 = 0x117E -->
  </resources>
  ```

### Bibliothèque recommandée
- **[mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)**
  (Apache 2.0, mature, supporte CDC-ACM nativement)
- Gradle :
  ```kotlin
  implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
  ```

---

## 3. Protocole slcan (à parser en Kotlin)

Le firmware du CANable parle ASCII sur le port série. Commandes essentielles :

| Commande | Effet |
|----------|-------|
| `S4\r` | Bitrate 125 kbit/s (notre cas C2) |
| `O\r` | Open channel (start RX/TX) |
| `C\r` | Close channel |
| `tIIIDDDDDDDDDDDDDDDD\r` | TX standard frame (ex: `t1A02AB\r` = ID 0x1A0, DLC 2, data 0xAB) |
| `TIIIIIIIIDDDDDDDDDDDDDDDD\r` | TX extended frame (29-bit) |
| **RX** : `tIIIDDDDDDDDDDDDDDDD\r` | Frame reçue (même format que TX) |

**Init sequence** :
```kotlin
serialPort.open()
serialPort.setParameters(3_000_000, 8, 1, NONE)
serialPort.write("\rC\r".toByteArray(), 100)  // close au cas où
Thread.sleep(50)
serialPort.write("S4\r".toByteArray(), 100)   // bitrate 125k
Thread.sleep(50)
serialPort.write("O\r".toByteArray(), 100)    // open
```

**Parser RX** : lire ligne par ligne (terminator `\r`), regex
`^t([0-9A-Fa-f]{3})([0-8])([0-9A-Fa-f]*)$` pour parser ID/DLC/data.

---

## 4. IDs CAN à décoder (résumé)

Tous calibrés et validés sur la C2 du user.

### Lecture (RX)

| ID | DLC | Décodage (résumé) |
|----|-----|-------------------|
| **0x036** | 8 | BSI status : ignition_mode (b4 bits 0-2), economy (b2 b7), brightness (b3 bits 0-3), night_mode (b3 b5), black_panel (b3 b4) |
| **0x0B6** | 8 | RPM=BE(b0,b1)/8 ; Speed=BE(b2,b3)/100 ; trip_dist_cmb=BE(b4,b5) |
| **0x0F6** | 8 | T°coolant=b1−53 ; odo=BE(b2,b3,b4)/10 km ; T°ext=b6−102 ; b7 bits = clignos/marche AR/essuie-glace |
| **0x128** | 8 | Témoins combiné ; b0 bit5=frein à main, b0 bit4=fuel_low, b1 bit1=ABS, b2 bit3=ESP en cours, b2 bit1=warnings ON, b4 bits 1-7=feux (cligno/route/croisement/position/antibr) |
| **0x161** | 7 | b2=T°huile−64 ; **b3=fuel_pct (%)** ; **b6=oil_level_pct (%)** |
| **0x167** | 8 | Page EMF (b0 bits 0-2) : 0=NONE,1=GENERAL,2=TRIP1,4=TRIP2 ; trip_dist_total=BE(b2,b3) |
| **0x168** | 3+ | Alertes critiques : oil_pressure, oil_level, coolant_level, T°max, FAP, pneus |
| **0x21F** | 3 | Boutons volant — b0 : bit1=SOURCE, bit2=VOL−, bit3=VOL+, bit6=PRECEDANT, bit7=SUIVANT ; b1=scroll position |
| **0x220** | 2 | Ouvrants — b0 : bit7=AVG, bit6=AVD, bit3=coffre, bit2=capot, bit1=vitre AR, bit0=trappe carb. |
| **0x221** | 7 | Conso instant=BE(b1,b2)/10 (FFFF=invalid) ; Autonomie=BE(b3,b4) |
| **0x261** | 7 | Trip slot 1 — b0=vit_moy_kmh ; BE(b2,b3)/10=trip_dist ; b4/10=conso_moy |
| **0x276** | 7 | Date BSI (rare sur C2) — b0=année−1872, b1=mois, b2=jour, b3=heure, b4=minute |
| **0x3A7** | 8 | Maintenance — b0 b7=due ; BE(b3,b4)=km restants ; BE(b5,b6)=jours restants |

Constantes de calibration véhicule :
- Réservoir = **40 L** (Citroën C2 essence)
- Capacité huile TU1JP = **3.0 L** avec filtre
- Note : tank=41L était la valeur Lexia mais le user confirme 40L à l'oeil

### Émission (TX)

| ID | Format | Effet |
|----|--------|-------|
| **0x21F** | 3 bytes | Bouton volant émulé pour autoradio (vol+/-, source, suivant/précédant) |
| **0x39B** | 5 bytes | Set heure BSI (année−1872, mois, jour, heure, minute) — n'a PAS d'effet sur EMF C2 mais à inclure |
| **0x3E5** | 6 bytes | **Boutons EMF (PSAWifiDisplayControl mapping)** — voir §5 |

---

## 5. Mode EMF (CRITIQUE — le truc qui marche !)

Reproduit `scripts/emf_keyboard_nav.py` + `web/dashboard.html` section EMF.

### Logique

1. Démarrer un **timer périodique 15 Hz** (toutes les 65 ms)
2. À chaque tick : envoyer `0x3E5 = 00 00 00 00 00 00` (release/idle)
3. Quand l'utilisateur clique un bouton : injecter UNE SEULE trame avec le bit du bouton set
4. Le tick suivant remettra à 00 (auto-release ~65 ms après)

### Mapping boutons (DLC=6)

```kotlin
val EMF_BUTTONS = mapOf(
    "MENU"   to byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x00, 0x00),  // b[0] bit 6
    "MODE"   to byteArrayOf(0x00, 0x10, 0x00, 0x00, 0x00, 0x00),  // b[1] bit 4
    "TRIP"   to byteArrayOf(0x00, 0x40, 0x00, 0x00, 0x00, 0x00),  // b[1] bit 6
    "OK"     to byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0x00),  // b[2] bit 6
    "ESC"    to byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x00, 0x00),  // b[2] bit 4
    "UP"     to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x40),  // b[5] bit 6
    "DOWN"   to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x10),  // b[5] bit 4
    "LEFT"   to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01),  // b[5] bit 0
    "RIGHT"  to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x04),  // b[5] bit 2
    "PHONE"  to byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x00, 0x00),
    "AIRCON" to byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00),
    "DARK"   to byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x00, 0x00),
)
```

### Pré-requis CRITIQUE
**Le télécodage BSI doit déclarer `RD4 présent`** (via Lexia/Diagbox une fois).
Sans ça, l'EMF ignore tous nos `0x3E5`.

---

## 6. Volant → touches Atoto (steering wheel pass-through)

L'objectif numéro un du projet : que les boutons **VOL+/VOL-/SUIVANT/PRECEDANT/SOURCE**
du volant pilotent l'app média de l'Atoto (Spotify, Android Auto, lecteur intégré).

### 3 stratégies possibles

**A — Accessibility Service (option recommandée)** :
- Service Android qui simule des `KeyEvent` système
- Mappe les bits de `0x21F` byte[0] sur :
  - VOL+ → `KeyEvent.KEYCODE_VOLUME_UP`
  - VOL- → `KeyEvent.KEYCODE_VOLUME_DOWN`
  - SUIVANT → `KeyEvent.KEYCODE_MEDIA_NEXT`
  - PRECEDANT → `KeyEvent.KEYCODE_MEDIA_PREVIOUS`
  - SOURCE → custom (ouvrir picker source ?)
  - MUTE → `KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE`
- L'utilisateur active le service manuellement la 1ʳᵉ fois
- Limitation : restrictions Android sur `KeyEvent.dispatch` hors app courante

**B — Intent Broadcast vers app média** :
- Diffuser des intents `android.intent.action.MEDIA_BUTTON`
- Spotify, Android Auto, etc. les écoutent
- Plus universel, pas besoin d'accessibility

**C — Atoto SDK propriétaire** :
- Atoto expose peut-être une API pour injecter les "Steering Wheel Control" (SWC)
- À investiguer dans la doc OEM Atoto si elle existe

→ **Commencer par B (Intent broadcast)**, fallback A si ça suffit pas.

---

## 7. UI : WebView ou Compose ?

### Option WebView (RECOMMANDÉ pour démarrer rapidement)

- Charge `assets/dashboard.html` dans une WebView fullscreen
- Le HTML pointe sur `http://127.0.0.1:8080/` (NanoHTTPD interne) OU directement
  sur des fonctions JS bridge (interface JavascriptInterface)
- **Réutilise 100% du dashboard.html actuel** → 0 effort de portage UI

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(CanBridgeJsInterface(canService), "AndroidBridge")
            loadUrl("file:///android_asset/dashboard.html")
            // ou : loadUrl("http://127.0.0.1:8080/")  si NanoHTTPD interne
        }
        setContentView(webView)
    }
}
```

### Option Compose (long-terme, plus polish)

- UI native Material You / Compose
- Animations fluides, perfs supérieures
- ~600-1000 LOC de plus pour reproduire la dashboard
- À envisager après que la version WebView fonctionne

---

## 8. Squelette projet

```
CANCitroen-Android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/geoffrey/cancitroen/
│       │   ├── MainActivity.kt              ← WebView fullscreen
│       │   ├── CanService.kt                ← ForegroundService
│       │   ├── usb/
│       │   │   ├── CanableSerial.kt         ← USB-serial wrapper
│       │   │   └── SlcanParser.kt           ← parser slcan ASCII
│       │   ├── decode/
│       │   │   ├── CanDecoder.kt            ← port direct de bridge.py decoders
│       │   │   ├── VehicleState.kt          ← data class équivalent STATE
│       │   │   └── CanIds.kt                ← constantes
│       │   ├── emf/
│       │   │   ├── EmfController.kt         ← release-loop + button send
│       │   │   └── EmfButtons.kt            ← mapping
│       │   ├── http/
│       │   │   └── LocalServer.kt           ← NanoHTTPD + dashboard endpoints
│       │   ├── swc/
│       │   │   └── SteeringWheelHandler.kt  ← 0x21F → MediaButton intents
│       │   └── ui/
│       │       └── WebViewBridge.kt         ← @JavascriptInterface
│       ├── assets/
│       │   ├── dashboard.html               ← copie depuis web/dashboard.html
│       │   └── ...
│       └── res/
│           ├── xml/usb_device_filter.xml
│           └── values/strings.xml
└── build.gradle.kts
```

---

## 9. Étapes d'implémentation (ordre recommandé)

1. **Setup Android Studio** — projet vide Kotlin, target API 31+
2. **Détection USB CANable** — recevoir l'event ATTACHED, demander permission, ouvrir port
3. **Parser slcan + lecture RX** — confirmer qu'on reçoit les frames sur le bus
4. **Port des decoders** — copier la logique de `bridge.py` decode_XXX en Kotlin pur
5. **ForegroundService** — pour persister la lecture quand WebView est en arrière-plan
6. **WebView dashboard** — afficher dashboard.html, alimenter les valeurs via JsInterface
7. **EMF release-loop + boutons** — port direct du Python, exposer en JS via interface
8. **Steering wheel pass-through** — décoder 0x21F, broadcaster MediaButton intents
9. **Polish** — auto-start au boot, full-screen lock, gestion power off
10. **APK signé + sideload** sur Atoto, test in-car

---

## 10. Décisions à figer avant de coder

- [ ] **Connection** : CANable USB direct (option choisie) — confirmé OK
- [ ] **UI** : WebView avec dashboard.html (recommandé) — réutilise tout l'existant
- [ ] **SWC** : Intent broadcast d'abord, AccessibilityService en backup
- [ ] **Branding** : nom de l'app, icône, couleurs du dashboard
- [ ] **Persistence** : ForegroundService (mandatory)
- [ ] **API niveau cible** : Android 10 (API 29) minimum (Atoto A6PF tourne ≥ 10)
- [ ] **Langage** : Kotlin
- [ ] **Build** : Android Studio Hedgehog ou Iguana

## 11. Tests d'acceptation

À valider sur l'Atoto en voiture, avant déploiement :

- [ ] L'app démarre auto au contact et reste en avant-plan
- [ ] CANable détecté, slcan0 ouvert sans erreur, frames RX > 100/s
- [ ] Dashboard affiche RPM/vitesse en temps réel
- [ ] T° moteur/huile/ext, fuel, autonomie cohérents avec Lexia
- [ ] Frein à main, portes, feux, clignos détectés en live
- [ ] Mode EMF : MENU ouvre le menu sur écran central, navigation OK
- [ ] Boutons volant VOL+/-/SUIVANT/PRECEDANT/SOURCE pilotent Spotify/AA
- [ ] Set d'heure fonctionne via le menu EMF (séquence à scripter une fois)
- [ ] Pas de crash après 1h d'utilisation continue
- [ ] Mémoire stable (pas de fuite)
