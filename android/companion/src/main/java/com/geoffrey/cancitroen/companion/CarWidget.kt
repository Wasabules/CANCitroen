package com.geoffrey.cancitroen.companion

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject

/**
 * Widget de l'écran d'accueil : carburant, autonomie, dernier trajet, liaison. Le suivi le
 * rafraîchit à chaque nouveau résumé ; Android, toutes les demi-heures au plus.
 */
class CarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = render(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        /** Sans widget posé, ne coûte qu'une requête au système. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CarWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        internal fun render(context: Context): RemoteViews {
            val summary = VehicleHistory.summary(context)
            val views = RemoteViews(context.packageName, R.layout.widget_voiture)
            val lines = WidgetText.of(summary, CarSessionService.status, HomeAssistant.lastContactMs(context))
            views.setTextViewText(R.id.widget_etat, lines.status)
            views.setTextViewText(R.id.widget_carburant, lines.fuel)
            views.setTextViewText(R.id.widget_autonomie, lines.range)
            views.setTextViewText(R.id.widget_trajet, lines.trip)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_racine, open)
            return views
        }
    }
}

/** Les textes du widget, sans Android : testés en JVM. */
object WidgetText {
    class Lines(val status: String, val fuel: String, val range: String, val trip: String)

    fun of(summary: JSONObject?, status: CarSessionService.Status, lastContactMs: Long): Lines {
        fun value(key: String): Double? =
            summary?.takeIf { it.has(key) && !it.isNull(key) }?.optDouble(key)?.takeUnless { it.isNaN() }
        val linked = status.running && status.phase == SessionPlan.Phase.LINKED
        val state = when {
            linked -> "connectée"
            lastContactMs > 0 -> "vue ${dayTime(lastContactMs)}"
            else -> ""
        }
        if (summary == null) return Lines(state, "–", "", "Rien de reçu pour l'instant.")
        val trip = if (summary.optBoolean(F.TRIP_ACTIVE) && linked) {
            listOfNotNull("Trajet en cours", value(F.CURRENT_KM)?.let { "${number(it, 1)} km" }).joinToString(" · ")
        } else {
            listOfNotNull(
                value(F.TRIP_KM)?.let { "Dernier trajet ${number(it, 1)} km" },
                value(F.TRIP_CONS)?.let { "${number(it, 1)} L/100" },
            ).joinToString(" · ")
        }
        return Lines(
            status = state,
            fuel = value(F.FUEL_PCT)?.let { "${number(it, 0)} %" } ?: "–",
            range = value(F.RANGE)?.let { "${number(it, 0)} km d'autonomie" }.orEmpty(),
            trip = trip,
        )
    }
}
