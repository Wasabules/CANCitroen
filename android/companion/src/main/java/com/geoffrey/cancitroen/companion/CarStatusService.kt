package com.geoffrey.cancitroen.companion

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Le compagnon dans Android Auto : la voiture (valeurs et graphique), ses trajets, et l'état du
 * suivi. Android Auto n'affiche que ses modèles d'écran : les graphiques y sont des images.
 * L'ouvrir lance aussi le suivi s'il ne tourne pas. Installé hors du Play Store, il n'apparaît
 * qu'avec les « Sources inconnues » activées dans les réglages développeur d'Android Auto.
 */
class CarStatusService : CarAppService() {
    // Seul Android Auto, signé par Google, peut s'y lier.
    override fun createHostValidator(): HostValidator = HostValidator.Builder(applicationContext)
        .addAllowedHosts(R.array.hotes_android_auto)
        .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VehicleCarScreen(carContext)
    }
}

/** Écran rafraîchi tant qu'il est visible ; [load] tourne hors du thread principal. */
internal abstract class RefreshingScreen(context: CarContext, private val periodMs: Long) : Screen(context) {
    protected val main = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)
    private val tick = object : Runnable {
        override fun run() {
            reload()
            main.postDelayed(this, periodMs)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                main.post(tick)
            }

            override fun onStop(owner: LifecycleOwner) {
                main.removeCallbacks(tick)
            }
        })
    }

    /** Lit les données ; appelée hors du thread principal (et directement par les tests). */
    internal open fun load() = Unit

    fun reload() {
        if (!loading.compareAndSet(false, true)) return
        thread(name = "Android Auto") {
            try {
                load()
            } finally {
                loading.set(false)
                main.post { invalidate() }
            }
        }
    }

    /** Graphique en image : seulement si l'hôte sait en afficher une (API 4 et plus). */
    protected fun chartIcon(kind: ChartKind, view: HistoryView?): CarIcon? {
        if (view == null || carContext.carAppApiLevel < IMAGE_API_LEVEL) return null
        val bitmap = ChartPainter.bitmap(kind, view.series, view.from, view.to, CHART_WIDTH_PX, CHART_HEIGHT_PX, CHART_SCALE)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    protected fun nextChart(kind: ChartKind): ChartKind = ChartKind.entries[(kind.ordinal + 1) % ChartKind.entries.size]

    /** Le geste tourne hors du thread principal ; son résultat s'affiche en bulle. */
    protected fun gesture(title: String, pending: String, work: () -> String): Action =
        Action.Builder().setTitle(title).setOnClickListener {
            CarToast.makeText(carContext, pending, CarToast.LENGTH_SHORT).show()
            thread(name = "Geste Android Auto") {
                val result = work()
                main.post {
                    CarToast.makeText(carContext, result.substringAfter(" · "), CarToast.LENGTH_LONG).show()
                    invalidate()
                }
            }
        }.build()

    companion object {
        private const val IMAGE_API_LEVEL = 4
        private const val CHART_WIDTH_PX = 720
        private const val CHART_HEIGHT_PX = 440
        private const val CHART_SCALE = 2f
        // Quatre lignes : le maximum garanti d'un écran de ce type.
        const val PANE_ROWS = 4
        const val LIST_ROWS = 6
    }
}

/** L'accueil : l'état de la voiture et la courbe du trajet en cours, ou des dernières 24 h. */
internal class VehicleCarScreen(context: CarContext) : RefreshingScreen(context, REFRESH_MS) {
    @Volatile private var summary: JSONObject? = null
    @Volatile private var view: HistoryView? = null
    private var chart = ChartKind.FUEL

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!CarSessionService.status.running) CarSessionService.start(carContext, "écran Android Auto")
            }
        })
    }

    override fun load() {
        val current = VehicleHistory.summary(carContext)
        val now = System.currentTimeMillis()
        val tripStart = current?.takeIf { it.optBoolean(F.TRIP_ACTIVE) }?.optLong(F.TRIP_START)?.takeIf { it > 0 }
        summary = current
        view = VehicleHistory.load(carContext, tripStart ?: (now - DAY_MS), now)
    }

    @Suppress("DEPRECATION") // setTitle et setHeaderAction : les seuls connus des hôtes anciens (API 1).
    override fun onGetTemplate(): Template {
        val current = summary
        val pane = Pane.Builder()
        if (current == null && view == null) {
            pane.setLoading(true)
        } else {
            val rows = carRows(current) + ("Suivi" to SessionReport.rows(carContext).first().second)
            for ((label, value) in rows.take(PANE_ROWS)) pane.addRow(Row.Builder().setTitle(label).addText(value).build())
            chartIcon(chart, view)?.let(pane::setImage)
            pane.addAction(Action.Builder().setTitle("Trajets").setOnClickListener { screenManager.push(TripsCarScreen(carContext)) }.build())
            pane.addAction(Action.Builder().setTitle("Suivi").setOnClickListener { screenManager.push(StatusCarScreen(carContext)) }.build())
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(if (current?.optBoolean(F.TRIP_ACTIVE) == true) "Trajet en cours · ${chart.title}" else "CANCitroen · ${chart.title}")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Courbe").setOnClickListener {
                        chart = nextChart(chart)
                        invalidate()
                    }.build(),
                ).build(),
            )
            .build()
    }

    private fun carRows(summary: JSONObject?): List<Pair<String, String>> {
        if (summary == null) return listOf("Voiture" to "rien de reçu pour l'instant")
        fun value(key: String): Double? = if (!summary.has(key) || summary.isNull(key)) null else summary.optDouble(key).takeUnless { it.isNaN() }
        val fuel = listOfNotNull(
            value(F.FUEL_PCT)?.let { "${number(it, 0)} %" },
            value(F.FUEL_LITERS)?.let { "${number(it, 1)} L" },
            value(F.RANGE)?.let { "${number(it, 0)} km" },
        )
        val temperatures = listOfNotNull(
            value(F.COOLANT)?.let { "eau ${number(it, 0)} °C" },
            value(F.OIL)?.let { "huile ${number(it, 0)} °C" },
            value(F.OUTSIDE)?.let { "ext. ${number(it, 0)} °C" },
        )
        val trip = listOfNotNull(
            value(F.TRIP_KM)?.let { "${number(it, 1)} km" },
            value(F.TRIP_START)?.let { start -> value(F.TRIP_END)?.let { minutes((it - start).toLong()) } },
            value(F.TRIP_CONS)?.let { "${number(it, 1)} L/100" },
        )
        return listOf(
            "Carburant" to fuel.joinToString(" · ").ifEmpty { "–" },
            "Températures" to temperatures.joinToString(" · ").ifEmpty { "–" },
            "Dernier trajet" to trip.joinToString(" · ").ifEmpty { "–" },
        )
    }

    companion object {
        private const val REFRESH_MS = 30_000L
        private const val DAY_MS = 24L * 3_600_000L
    }
}

/** Les derniers trajets ; chacun ouvre son détail. */
internal class TripsCarScreen(context: CarContext) : RefreshingScreen(context, REFRESH_MS) {
    @Volatile private var trips: List<TripRow>? = null

    override fun load() {
        val now = System.currentTimeMillis()
        trips = VehicleHistory.load(carContext, now - MONTH_MS, now).trips.asReversed()
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val template = ListTemplate.Builder().setTitle("Trajets (30 jours)").setHeaderAction(Action.BACK)
        val loaded = trips ?: return template.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("Aucun trajet reçu sur 30 jours.")
        for (trip in loaded.take(LIST_ROWS)) {
            list.addItem(
                Row.Builder()
                    .setTitle(dayTime(trip.startTs) + (trip.endTs?.let { " · ${minutes(it - trip.startTs)}" } ?: " · en cours"))
                    .addText(tripLine(trip))
                    .setOnClickListener { screenManager.push(TripCarScreen(carContext, trip)) }
                    .build(),
            )
        }
        return template.setSingleList(list.build()).build()
    }

    companion object {
        private const val REFRESH_MS = 60_000L
        private const val MONTH_MS = 30L * 86_400_000L
    }
}

/** Un trajet : ses chiffres et ses courbes. */
internal class TripCarScreen(context: CarContext, private val trip: TripRow) : RefreshingScreen(context, REFRESH_MS) {
    @Volatile private var view: HistoryView? = null
    @Volatile private var price: Double? = null
    private var chart = ChartKind.SPEED

    override fun load() {
        val end = trip.endTs ?: System.currentTimeMillis()
        view = VehicleHistory.load(carContext, trip.startTs - MARGIN_MS, end + MARGIN_MS)
        price = literPrice(VehicleHistory.summary(carContext))
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val rows = listOf(
            "Départ" to dayTime(trip.startTs) + (trip.endTs?.let { ", ${minutes(it - trip.startTs)}" } ?: ", en cours"),
            "Distance et conso" to listOfNotNull(
                tripLine(trip).takeIf { it.isNotEmpty() },
                TripEvents.liters(trip.km, trip.cons)?.let { liters -> price?.let { euros(liters * it) } },
            ).joinToString(" · ").ifEmpty { "–" },
            "Vitesse" to listOfNotNull(
                trip.avgSpeed?.let { "moyenne ${number(it, 0)} km/h" },
                trip.maxSpeed?.let { "max ${number(it, 0)} km/h" },
            ).joinToString(" · ").ifEmpty { "–" },
            "Jauge" to if (trip.fuelStart != null && trip.fuelEnd != null) "${trip.fuelStart} % → ${trip.fuelEnd} %" else "–",
        )
        for ((label, value) in rows.take(PANE_ROWS)) pane.addRow(Row.Builder().setTitle(label).addText(value).build())
        chartIcon(chart, view)?.let(pane::setImage)
        return PaneTemplate.Builder(pane.build())
            .setTitle("Trajet · ${chart.title}")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle("Courbe").setOnClickListener {
                        chart = nextChart(chart)
                        invalidate()
                    }.build(),
                ).build(),
            )
            .build()
    }

    companion object {
        private const val REFRESH_MS = 60_000L
        private const val MARGIN_MS = 60_000L
    }
}

/** L'état du suivi, et deux gestes. */
internal class StatusCarScreen(context: CarContext) : RefreshingScreen(context, REFRESH_MS) {
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        for ((label, value) in SessionReport.rows(carContext).take(PANE_ROWS)) {
            pane.addRow(Row.Builder().setTitle(label).addText(value).build())
        }
        pane.addAction(gesture("Envoyer à HA", "Envoi à Home Assistant…") {
            HomeAssistant.publish(carContext, true, force = true).message
        })
        pane.addAction(gesture("Transférer", "Transfert en cours…") { TelemetrySync.run(carContext).message })
        return PaneTemplate.Builder(pane.build())
            .setTitle("Suivi")
            .setHeaderAction(Action.BACK)
            .build()
    }

    companion object {
        private const val REFRESH_MS = 5_000L
    }
}

private fun tripLine(trip: TripRow): String = listOfNotNull(
    trip.km?.let { "${number(it, 1)} km" },
    trip.cons?.let { "${number(it, 1)} L/100" },
    trip.avgSpeed?.let { "moy. ${number(it, 0)} km/h" },
).joinToString(" · ")
