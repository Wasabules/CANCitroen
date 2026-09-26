package com.geoffrey.cancitroen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geoffrey.cancitroen.decode.CanDecoder
import com.geoffrey.cancitroen.decode.CanIds
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.audio.EngineProfiles
import com.geoffrey.cancitroen.audio.EngineSoundSynth
import com.geoffrey.cancitroen.audio.MemeEngineCatalog
import com.geoffrey.cancitroen.emf.EmfController
import com.geoffrey.cancitroen.history.RollupAggregator
import com.geoffrey.cancitroen.history.VehicleHistoryRecorder
import com.geoffrey.cancitroen.sim.VehicleSimulator
import com.geoffrey.cancitroen.swc.SteeringWheelHandler
import com.geoffrey.cancitroen.system.LauncherGuard
import com.geoffrey.cancitroen.usb.CanableSerial
import com.geoffrey.cancitroen.usb.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ForegroundService unique : possède la connexion CANable, la décode, alimente
 * l'état véhicule et offre les contrôles EMF + SWC à l'UI.
 */
class CanService : Service() {

    companion object {
        private const val TAG = "CanService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "can_service_channel"
        /** Au-delà de cette silence en STREAMING, on considère le CANable freezé. */
        private const val FRAME_SILENCE_TIMEOUT_MS = 30_000L
        /**
         * Bus muet depuis ce délai (contact coupé, BSI endormie) → on rend le
         * wakelock pour laisser l'unité se mettre en veille. Pendant un trajet
         * le bus émet ~185 trames/s : il reste tenu en permanence.
         */
        private const val WAKELOCK_BUS_IDLE_MS = 10L * 60_000L
        /** Timeout du wakelock, réarmé tant que le bus parle (filet si le service meurt). */
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60_000L
        /** Cadence de l'état exposé à l'UI (l'audio et l'historique lisent l'état brut). */
        private const val UI_SAMPLE_MS = 100L
    }

    inner class LocalBinder : Binder() {
        val service: CanService get() = this@CanService
    }

    private val binder = LocalBinder()
    /**
     * Toute exception non rattrapée d'une coroutine du service (USB, Room…)
     * est journalisée au lieu de tuer le process — qui est aussi le launcher
     * de la voiture. Le watchdog se charge de relancer la connexion CAN.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Exception non rattrapée (service maintenu)", e) }
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockArmedAt = 0L

    /**
     * Sérialise les appels à [tryConnectAndStream] : init initial, attach USB,
     * accord de permission USB, et watchdog peuvent tous l'invoquer en parallèle
     * → sans verrou, deux `tryOpen` simultanés laisseraient l'état USB
     * indéterminé sur le driver.
     */
    private val connectMutex = Mutex()

    private lateinit var canable: CanableSerial
    lateinit var emfController: EmfController
        private set
    private lateinit var steeringWheel: SteeringWheelHandler

    private val _vehicleState = MutableStateFlow(VehicleState())
    /**
     * State exposé : applique la calibration utilisateur (offset T° ext,
     * capacité du réservoir) en post-traitement, sans toucher la valeur brute
     * décodée. Pleine cadence : l'audio suit le RPM trame par trame.
     */
    val vehicleState: StateFlow<VehicleState> by lazy {
        val calibration = App.get().settings.flow
            .map { it.calibration.tExtOffsetC to it.fuel.tankCapacityLiters }
            .distinctUntilChanged()
        combine(_vehicleState, calibration) { state, (tExtOffset, tankLiters) ->
            applyUserCalibration(state, tExtOffset, tankLiters)
        }.stateIn(scope, SharingStarted.Eagerly, VehicleState())
    }

    /**
     * État pour l'UI, échantillonné à 10 Hz : RPM/vitesse changent à chaque
     * trame 0x0B6 (20-50 Hz), ce qui faisait recomposer l'accueil à ce rythme.
     * Les jauges interpolent entre deux échantillons, l'affichage reste fluide.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val uiState: StateFlow<VehicleState> by lazy {
        vehicleState.sample(UI_SAMPLE_MS)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), vehicleState.value)
    }

    private val simulator = VehicleSimulator(_vehicleState)
    private val historyRecorder by lazy {
        VehicleHistoryRecorder(App.get().historyDao, vehicleState)
    }
    private val rollupAggregator by lazy {
        RollupAggregator(App.get().historyDao,
            com.geoffrey.cancitroen.history.HistoryDatabase.get(this))
    }
    private val engineSynth get() = App.get().engineSynth
    private val memeRunner get() = App.get().memeEngineRunner

    val connectionState: StateFlow<ConnectionState>
        get() = canable.connectionState

    /** BroadcastReceiver pour le retour de la popup USB permission. */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CanableSerial.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission ${if (granted) "GRANTED" else "DENIED"}")
            if (granted) {
                scope.launch { tryConnectAndStream() }
            }
        }
    }

    /** Receiver pour quand le CANable est plugué/déplugué pendant l'exécution. */
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB attached → tentative open")
                    scope.launch { tryConnectAndStream() }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB detached")
                    // Sous le verrou : un initBus() en cours ne doit pas voir
                    // le port disparaître sous ses pieds.
                    scope.launch { connectMutex.withLock { canable.close() } }
                    // Reset le state véhicule pour ne pas afficher des valeurs
                    // figées (vitesse, RPM, etc.) après débranchement
                    _vehicleState.value = VehicleState()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // ⚠ Chemin critique cold-boot Atoto SYU : on doit retourner d'onCreate
        // le plus vite possible. Seul startForeground+wakelock+canable doivent
        // être prêts ici. Tout le reste (DAO Room, audio flows, historique,
        // simulateur) est posté dans `scope.launch` → s'exécute sur IO sans
        // bloquer le thread main du Service.
        startForegroundWithNotif()
        acquireWakeLock()

        canable = CanableSerial(this)
        emfController = EmfController(canable, scope)
        steeringWheel = SteeringWheelHandler(
            this,
            App.get().settings.flow.stateIn(
                scope, SharingStarted.Eagerly,
                com.geoffrey.cancitroen.settings.AppSettings()
            ),
            scope,
        )

        // Enregistrer les broadcast receivers
        val permFilter = IntentFilter(CanableSerial.ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
        }

        // Pipeline RX en parallèle (collecté indépendamment de la connexion)
        scope.launch {
            canable.framesFlow.collect { frame ->
                // updateAndGet : atomique vis-à-vis du reset au débranchement
                // et du simulateur, qui écrivent aussi l'état.
                val newState = _vehicleState.updateAndGet { CanDecoder.decode(it, frame) }
                if (frame.id == CanIds.ID_RADIO_REMOTE) {
                    // Non bloquant : traité sur le thread dédié du handler.
                    steeringWheel.onWheelStateChanged(newState)
                }
            }
        }

        // Tentative initiale (au cas où le CANable est déjà branché)
        scope.launch { tryConnectAndStream() }

        // Watchdog : si plus de frame depuis 30s en STREAMING, ou si on est en
        // ERROR, on relance la séquence open → init → read avec backoff.
        // Évite de devoir rebrancher physiquement le CANable quand son firmware
        // freeze (observé sur certaines combos câble/hub USB Atoto).
        scope.launch { runConnectionWatchdog() }

        // ── Init "lourde" différée : Room DAO (lazy), audio flows, simulateur.
        //    On la lance sur le scope IO, donc onCreate retourne sans attendre.
        scope.launch { initDeferredComponents() }
    }

    /**
     * Bootstrap des composants qui peuvent prendre un peu de temps :
     *  - historyRecorder + rollupAggregator (touchent Room → lazy init du DAO)
     *  - 2 collect flows audio (lus à chaque update settings/RPM)
     *  - collect simulator toggle
     *
     * Tout est lancé via `scope.launch` enfant pour rester sous le contrôle du
     * scope du Service. Échoue silencieusement par-pièce si une dépendance
     * (DAO, settings) n'est pas encore prête — Room réessaiera au prochain
     * collect.
     */
    private fun initDeferredComponents() {
        historyRecorder.start(scope)
        rollupAggregator.start(scope)

        // Pilote synth OU meme runner selon les settings + RPM live
        scope.launch {
            App.get().settings.flow
                .map { it.engineSound }
                .distinctUntilChanged()
                .collect { sound ->
                    val profile = EngineProfiles.byId(sound.profileId)
                    engineSynth.setProfile(profile)
                    engineSynth.setVolume(sound.volume)
                    memeRunner.setProfile(profile.cylinders, profile.idleRpm, profile.redlineRpm)
                    memeRunner.setVolume(sound.volume)

                    val memeId = sound.memeEngineId
                    if (sound.enabled && memeId != null) {
                        // Mode meme : on stoppe le synth, on lance le runner
                        engineSynth.stop()
                        // Trouve le path du meme
                        val meme = MemeEngineCatalog.listAvailable(applicationContext)
                            .firstOrNull { it.id == memeId }
                        if (meme != null) {
                            memeRunner.load(meme.assetPath)
                            memeRunner.start(scope)
                        } else {
                            Log.w(TAG, "memeId '$memeId' introuvable dans assets/memes/engine/")
                            memeRunner.stop()
                        }
                    } else if (sound.enabled) {
                        // Mode synth normal
                        memeRunner.stop()
                        engineSynth.start(scope)
                    } else {
                        engineSynth.stop()
                        memeRunner.stop()
                    }
                }
        }
        scope.launch {
            vehicleState
                .map { it.rpm?.toFloat() ?: 0f }
                .distinctUntilChanged()
                .collect { rpm ->
                    engineSynth.setRpm(rpm)
                    memeRunner.setRpm(rpm)
                }
        }

        // Pilote le simulateur en fonction du flag DataStore
        scope.launch {
            App.get().settings.flow
                .map { it.dev.simulatorEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        Log.i(TAG, "▶ Simulateur ON")
                        simulator.start(scope)
                    } else {
                        Log.i(TAG, "■ Simulateur OFF")
                        simulator.stop()
                    }
                }
        }

        // ── Enforcement launcher (root) ──
        // Réconcilié à chaque démarrage du service (boot, relance par le
        // keepalive root…) : si l'utilisateur veut forcer le launcher, on arme
        // LauncherGuard — home par défaut + launcher natif FYT + service root
        // détaché (redémarré seulement si sa config a changé). Sinon on
        // désarme (aucun appel su si jamais armé).
        scope.launch {
            App.get().settings.flow
                .map { it.behavior.forceHomeRoot to it.behavior.aggressiveDisableLaunchers }
                .distinctUntilChanged()
                .collect { (force, aggressive) ->
                    if (force) {
                        Log.i(TAG, "LauncherGuard: arm (aggressive=$aggressive)")
                        LauncherGuard.arm(applicationContext, aggressive)
                    } else {
                        Log.i(TAG, "LauncherGuard: disarm")
                        LauncherGuard.disarm(applicationContext)
                    }
                }
        }
    }

    private suspend fun tryConnectAndStream() = connectMutex.withLock {
        // Déjà en train de lire (second broadcast ATTACHED/permission) : rien à faire.
        if (canable.connectionState.value == ConnectionState.STREAMING) return@withLock
        when (val r = canable.tryOpen()) {
            is CanableSerial.OpenResult.Success -> try {
                canable.initBus()
                canable.startReadLoop(scope)
            } catch (e: Exception) {
                // CANable figé : l'écriture de C/S4/O expire. On referme ; le
                // watchdog retentera avec backoff.
                Log.e(TAG, "Init du bus échouée", e)
                try { canable.close() } catch (_: Exception) {}
            }
            is CanableSerial.OpenResult.NoDevice ->
                Log.w(TAG, "Aucun CANable détecté (USB pas branché ?)")
            is CanableSerial.OpenResult.NeedsPermission ->
                Log.i(TAG, "Permission USB demandée — popup ouverte")
            is CanableSerial.OpenResult.Error ->
                Log.e(TAG, "Erreur ouverture USB", r.cause)
        }
    }

    /**
     * Surveille la connexion CANable et la relance si :
     *  - state == ERROR (read loop a baillé sur exception USB)
     *  - state == STREAMING mais aucun frame reçu depuis [FRAME_SILENCE_TIMEOUT_MS]
     *    (firmware CANable freeze, câble qui fait du contact, etc.)
     *
     * Backoff exponentiel borné pour ne pas hammerer la pile USB quand le
     * CANable est physiquement débranché.
     */
    private suspend fun runConnectionWatchdog() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            delay(5_000L)
            updateWakeLock()
            val state = canable.connectionState.value
            val needReconnect = when (state) {
                ConnectionState.ERROR -> true
                ConnectionState.STREAMING ->
                    canable.millisSinceLastFrame() > FRAME_SILENCE_TIMEOUT_MS
                else -> false  // DISCONNECTED / AWAITING_PERMISSION / OPEN : on ne touche pas
            }
            if (!needReconnect) {
                // Reset du backoff seulement sur de vraies trames reçues : juste
                // après une réouverture, le silence est remis à zéro sans que le
                // CANable ait parlé (bus endormi) → sinon on rouvrait toutes les
                // ~35 s indéfiniment, au lieu d'espacer jusqu'à 30 s de backoff.
                if (canable.hasReceivedSinceOpen()) backoffMs = 1_000L
                continue
            }
            Log.w(TAG, "Watchdog : reconnect (state=$state, silence=${canable.millisSinceLastFrame()}ms)")
            connectMutex.withLock { try { canable.close() } catch (_: Exception) {} }
            tryConnectAndStream()
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        releaseWakeLock()
        try { engineSynth.stop() } catch (_: Exception) {}
        try { memeRunner.unload() } catch (_: Exception) {}
        // Shutdown CAN bus : fire-and-forget non-cancelable, borné à 500 ms.
        // On capture les références dans des locals AVANT de lancer la
        // coroutine : sans ça, la lambda retenait `this@CanService` (via
        // `emfController` et `canable` qui sont des champs de l'instance),
        // ce qui pouvait faire coexister brièvement deux CanService dont
        // l'ancien écrivait encore "C\r" sur le port que la nouvelle
        // venait d'ouvrir via onTaskRemoved/START_STICKY.
        val emf = emfController
        val cnb = canable
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(500L) {
                try { emf.setActive(false) } catch (_: Exception) {}
                try { cnb.close() } catch (_: Exception) {}
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    /** START_STICKY = si Android tue le service, il le relance dès qu'il peut. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Si l'app est swipe-killée par l'utilisateur, relance le service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed → reschedule service")
        val restart = Intent(applicationContext, CanService::class.java)
        startService(restart)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CANCitroen:CanServiceWakeLock"
            ).apply { setReferenceCounted(false) }.also { wakeLock = it }
            if (!wl.isHeld) Log.i(TAG, "WakeLock acquis")
            wl.acquire(WAKELOCK_TIMEOUT_MS)  // non compté : réarme le timeout
            wakeLockArmedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire fail", e)
        }
    }

    /**
     * Wakelock tenu tant que le bus CAN parle (trajet : lecture continue même
     * écran éteint), rendu après [WAKELOCK_BUS_IDLE_MS] de silence pour que
     * l'unité puisse se mettre en veille contact coupé. N'affecte pas le
     * premier plan : l'activité garde l'écran allumé elle-même.
     */
    private fun updateWakeLock() {
        val busActive = canable.millisSinceLastFrame() < WAKELOCK_BUS_IDLE_MS
        val held = wakeLock?.isHeld == true
        when {
            busActive && (!held || System.currentTimeMillis() - wakeLockArmedAt > WAKELOCK_TIMEOUT_MS / 2) ->
                acquireWakeLock()
            !busActive && held -> {
                Log.i(TAG, "Bus CAN muet depuis ${WAKELOCK_BUS_IDLE_MS / 60_000} min → WakeLock rendu")
                releaseWakeLock()
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
    }

    /** Offset T° ext + litres recalculés avec la capacité réglée (le décodeur suppose 41 L). */
    private fun applyUserCalibration(s: VehicleState, tExtOffset: Int, tankLiters: Float): VehicleState {
        val tExt = s.tExt?.let { it + tExtOffset }
        val fuelLiters = s.fuelPct?.takeIf { it > 0 }
            ?.let { Math.round(it * tankLiters.toDouble() / 10.0) / 10.0 }
            ?: s.fuelLitersEst
        return if (tExt == s.tExt && fuelLiters == s.fuelLitersEst) s
        else s.copy(tExt = tExt, fuelLitersEst = fuelLiters)
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // SPECIAL_USE : pas de pré-requis hardware (CONNECTED_DEVICE exige un USB
            // device matché à un filter, ce qui pose souci sur émulateur ou avant le
            // branchement du CANable). SPECIAL_USE est validé par la déclaration
            // <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">
            // dans le manifest.
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
