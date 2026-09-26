package com.geoffrey.cancitroen

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.geoffrey.cancitroen.settings.AppSettings
import com.geoffrey.cancitroen.system.AppShortcuts
import com.geoffrey.cancitroen.system.GpsTracker
import com.geoffrey.cancitroen.system.PermissionsHelper
import com.geoffrey.cancitroen.ui.screens.DashboardScreen
import com.geoffrey.cancitroen.ui.screens.EmfControlScreen
import com.geoffrey.cancitroen.ui.screens.EngineSoundScreen
import com.geoffrey.cancitroen.ui.screens.HomeRoute
import com.geoffrey.cancitroen.ui.screens.SettingsRoute
import com.geoffrey.cancitroen.ui.screens.StatsScreen
import com.geoffrey.cancitroen.ui.screens.SummaryScreen
import com.geoffrey.cancitroen.ui.theme.CANCitroenTheme
import com.geoffrey.cancitroen.ui.theme.LocalAppBackgroundGradient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Index de la page d'accueil dans le pager (à gauche : son, récap, stats ; à droite : tableau, EMF, réglages). */
private const val HOME_PAGE = 3

/** Demande de retour à l'accueil ; une instance par appui (clé de LaunchedEffect). */
private class HomeRequest(val animate: Boolean)

class MainActivity : ComponentActivity() {

    private val homeRequest = mutableStateOf<HomeRequest?>(null)

    private val boundService: MutableState<CanService?> = mutableStateOf(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            boundService.value = (binder as CanService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService.value = null
        }
    }

    private val gpsTracker: GpsTracker
        get() = App.get().gpsTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Garde l'écran allumé en permanence (carPC)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Mode immersif : edge-to-edge + barres système masquées.
        // Voir onWindowFocusChanged ci-dessous : les flags sont aussi
        // réappliqués à chaque retour de focus (sinon un dialog système
        // — perm runtime, exempt batterie, choix launcher — restaure
        // les barres et l'app n'est plus en plein écran).
        applyImmersiveMode()

        // setContent EN PREMIER pour que la frame initiale soit rendue avant
        // que le watchdog SYU (~3 s au boot froid Atoto) ne timeout. Si on
        // est en train de servir d'intent HOME, on doit avoir affiché quelque
        // chose ASAP sinon le ROM rebascule sur son launcher (ro.fyt.launcher
        // = com.android.launcher8).
        // Tout le travail lourd (foreground service, bindService, GPS,
        // demande exempt batterie) est posté à la fin du message loop.
        setContent {
            CANCitroenTheme { AppContent() }
        }

        window.decorView.post {
            startBackgroundWork()
        }
    }

    /**
     * singleTask : tout nouvel intent arrive ici (pas dans onCreate). On
     * réapplique l'immersif et on relance le GPS si l'utilisateur avait
     * autorisé entre-temps.
     *
     * Intent **HOME** (touche HOME, bouton volant, garde root) : retour à la
     * page d'accueil, comme un launcher — animé si l'app était déjà à l'écran,
     * instantané si elle revient d'arrière-plan (même règle que Launcher3).
     * Les autres intents (branchement USB via l'activity-alias) ne changent
     * pas de page.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyImmersiveMode()
        gpsTracker.start()
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val alreadyOnHome = hasWindowFocus() &&
                (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
            homeRequest.value = HomeRequest(animate = alreadyOnHome)
        }
    }

    /**
     * Démarrage différé des composants lourds. Posté après [setContent] pour
     * laisser le premier rendu se faire — c'est ce que le watchdog Atoto/SYU
     * surveille pour décider si on est un launcher viable.
     */
    private fun startBackgroundWork() {
        ContextCompat.startForegroundService(this, Intent(this, CanService::class.java))
        bindService(Intent(this, CanService::class.java), connection, Context.BIND_AUTO_CREATE)

        gpsTracker.start()

        // Demande la dérogation batterie au premier démarrage si pas faite.
        if (!PermissionsHelper.isIgnoringBatteryOptimizations(this)) {
            PermissionsHelper.requestIgnoreBatteryOptimizations(this)
        }
    }

    override fun onResume() {
        super.onResume()
        gpsTracker.start()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * Masque status/nav bars et passe en edge-to-edge. À appeler au démarrage
     * **et** à chaque retour de focus : sur Android 10 les flags sont effacés
     * par les dialogs système (permission runtime, picker launcher, etc.).
     */
    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Fallback pour les ROMs OEM (Atoto SYU) qui n'écoutent pas
        // toujours le controller récent.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onPause() {
        super.onPause()
        gpsTracker.stop()
    }

    override fun onDestroy() {
        gpsTracker.stop()
        try { unbindService(connection) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Composable
    private fun AppContent() {
        val service by boundService

        // Demande de permissions (location pour GPS, notifications)
        val locationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) gpsTracker.start()
        }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* no-op */ }

        LaunchedEffect(Unit) {
            if (!PermissionsHelper.isLocationGranted(this@MainActivity)) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !PermissionsHelper.isNotificationGranted(this@MainActivity)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Background gradient global — suit le preset d'accent (teinté vers
        // la hue choisie, plus aucun bleu hardcodé si l'utilisateur a switch).
        Box(
            modifier = Modifier.fillMaxSize().background(LocalAppBackgroundGradient.current),
        ) {
            if (service == null) {
                LoadingScreen()
            } else {
                MainNav(service!!)
            }
        }
    }

    @Composable
    private fun MainNav(service: CanService) {
        // État échantillonné à 10 Hz (le brut change à chaque trame RPM).
        val state by service.uiState.collectAsStateWithLifecycle()
        val emfActive by service.emfController.active.collectAsStateWithLifecycle()
        val emfLast by service.emfController.lastAction.collectAsStateWithLifecycle()
        val gpsState by gpsTracker.state.collectAsStateWithLifecycle(initialValue = null)
        val gpsSpeedKmh by gpsTracker.speedKmh.collectAsStateWithLifecycle(initialValue = null)

        var pickerSlot by remember { mutableStateOf<Int?>(null) }

        val pageCount = 7
        // Accueil = page centrale d'arrivée (index 3).
        // À gauche : EngineSound, Récap, Stats.
        // À droite : Tableau, EMF, Réglages.
        val pagerState = rememberPagerState(initialPage = HOME_PAGE, pageCount = { pageCount })
        val pagerScope = rememberCoroutineScope()

        // ── Touche HOME : retour à l'accueil depuis n'importe quelle page ──
        val home = homeRequest.value
        LaunchedEffect(home) {
            if (home == null) return@LaunchedEffect
            pickerSlot = null   // referme le sélecteur d'apps s'il était ouvert
            if (home.animate) pagerState.animateScrollToPage(HOME_PAGE)
            else pagerState.scrollToPage(HOME_PAGE)
        }

        // ── Ambient idle : passe en mode estompé après inactivité ──
        // `lastInteraction` n'est lu que par le snapshotFlow ci-dessous : un
        // toucher ne recompose rien, seul le passage idle ↔ actif le fait.
        var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var isIdle by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            snapshotFlow { lastInteraction }.collectLatest {
                isIdle = false
                delay(4_000L)   // 4 s sans interaction → idle
                isIdle = true
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // pointerInput en pass = Initial : on observe l'event AVANT les
                // enfants (HorizontalPager + clickable) sans le consommer, ce qui
                // permet de tracker n'importe quel tap / swipe sans casser l'UI.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastInteraction = System.currentTimeMillis()
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page -> when (page) {
                // ── À gauche d'Accueil ──
                0 -> EngineSoundScreen(modifier = Modifier.fillMaxSize())
                1 -> SummaryScreen(modifier = Modifier.fillMaxSize())
                2 -> StatsScreen(modifier = Modifier.fillMaxSize())
                // ── Page centrale (page d'arrivée) ──
                3 -> HomeRoute(
                    state = { state },
                    gpsState = gpsState,
                    gpsSpeedKmh = gpsSpeedKmh,
                    isIdle = isIdle,
                    onPickShortcut = { slot -> pickerSlot = slot },
                )
                // ── À droite d'Accueil ──
                4 -> DashboardScreen(state, modifier = Modifier.fillMaxSize())
                5 -> EmfControlScreen(
                    active = emfActive,
                    lastAction = emfLast,
                    onToggle = { service.emfController.setActive(!emfActive) },
                    onPress  = { service.emfController.pressButton(it) },
                    modifier = Modifier.fillMaxSize(),
                )
                6 -> SettingsRoute()
            } }

            PageIndicator(
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
                isIdle = isIdle,
                onPageClick = { i ->
                    pagerScope.launch { pagerState.animateScrollToPage(i) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            )
        }

        // ── Picker d'apps (long-press sur shortcut) ──
        val slot = pickerSlot
        if (slot != null) {
            com.geoffrey.cancitroen.ui.components.AppPickerSheet(
                onDismiss = { pickerSlot = null },
                onPick = { pkg ->
                    lifecycleScope.launch {
                        App.get().settings.setShortcutOverride(slot, pkg)
                    }
                },
                onClear = {
                    lifecycleScope.launch {
                        App.get().settings.setShortcutOverride(slot, null)
                    }
                },
            )
        }
    }

    /**
     * Indicateur 4 dots discret. Le dot actif s'élargit en pill, les autres restent ronds.
     * Tap sur un dot → jump direct.
     */
    @Composable
    private fun PageIndicator(
        pageCount: Int,
        currentPage: Int,
        isIdle: Boolean,
        onPageClick: (Int) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        // Quand idle ou en mode nuit : dots plus petits, alpha global réduit.
        // Mode nuit a la priorité (encore plus discret qu'idle).
        val nightMode = com.geoffrey.cancitroen.ui.theme.LocalNightMode.current
        val containerAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = when {
                nightMode -> 0.20f
                isIdle    -> 0.35f
                else      -> 1f
            },
            animationSpec = tween(700),
            label = "indAlpha",
        )
        val compact = isIdle || nightMode
        val activeW = if (compact) 18.dp else 28.dp
        val inactiveW = if (compact) 6.dp else 8.dp
        val dotH = if (compact) 6.dp else 8.dp

        Row(
            modifier = modifier.graphicsLayerAlpha(containerAlpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until pageCount) {
                val active = i == currentPage
                val w by animateDpAsState(
                    targetValue = if (active) activeW else inactiveW,
                    animationSpec = tween(700),
                    label = "dotW",
                )
                val h by animateDpAsState(
                    targetValue = dotH,
                    animationSpec = tween(700),
                    label = "dotH",
                )
                Box(
                    modifier = Modifier
                        .size(width = w, height = h)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.25f)
                        )
                        .clickable { onPageClick(i) },
                )
            }
        }
    }

    /** Petit helper : applique un alpha global à un composable via graphicsLayer. */
    private fun Modifier.graphicsLayerAlpha(a: Float): Modifier =
        this.then(Modifier.graphicsLayer { alpha = a })

    @Composable
    private fun LoadingScreen() {
        Box(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connexion au service CAN…",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
