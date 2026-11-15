package com.geoffrey.cancitroen.companion

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** L'état du suivi en lignes « libellé, valeur », pour l'écran du téléphone et celui d'Android Auto. */
object SessionReport {
    fun rows(context: Context, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val status = CarSessionService.status
        val tracking = when {
            !TransferPrefs(context).paired -> "téléphone non associé à l'Atoto"
            !status.running -> "inactif, démarre avec la voiture"
            status.phase == SessionPlan.Phase.LINKED -> "voiture connectée depuis ${ago(status.startedMs, now)}"
            else -> "recherche de l'Atoto (${status.reason})"
        }
        val contact = status.lastContactMs ?: HomeAssistant.lastContactMs(context).takeIf { it > 0 }
        val atoto = when {
            contact == null -> "jamais joint"
            status.running && status.phase == SessionPlan.Phase.LINKED -> "répond, il y a ${ago(contact, now)}" + status.route?.let { " ($it)" }.orEmpty()
            else -> "dernier contact ${date(contact)}"
        }
        val homeAssistant = if (HomeAssistant.isConnected(context)) {
            HomeAssistant.lastResult(context).ifEmpty { "rien envoyé pour l'instant" }
        } else {
            "non connecté"
        }
        val transfer = TransferPrefs(context).lastResult.ifEmpty { "aucun pour l'instant" }
        return listOf("Suivi" to tracking, "Atoto" to atoto, "Home Assistant" to homeAssistant, "Transfert" to transfer)
    }

    fun ago(ms: Long, now: Long): String {
        val seconds = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            seconds < 90 -> "$seconds s"
            seconds < 90 * 60 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h"
        }
    }

    fun date(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(ms))
}

/** Ce dont dépend le démarrage automatique du suivi, à vérifier et corriger depuis l'écran. */
object Reliability {
    enum class Fix { BATTERY, BLUETOOTH, NOTIFICATIONS, APP_SETTINGS }

    class Check(val label: String, val ok: Boolean, val detail: String, val fix: Fix)

    fun checks(context: Context): List<Check> {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = power.isIgnoringBatteryOptimizations(context.packageName)
        val bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        return listOf(
            Check(
                "Batterie non optimisée", battery,
                if (battery) "Le suivi peut démarrer seul." else "Sans cela, Android refuse de démarrer le suivi en arrière-plan.",
                Fix.BATTERY,
            ),
            Check(
                "Détection Bluetooth", bluetooth,
                if (bluetooth) "La connexion à la voiture lance le suivi." else "Autorisation « Appareils à proximité » : sans elle, la voiture n'est pas détectée.",
                Fix.BLUETOOTH,
            ),
            Check(
                "Notification du suivi", notifications,
                if (notifications) "Visible pendant le trajet." else "Le suivi tourne, mais sans notification pour le voir ou l'arrêter.",
                Fix.NOTIFICATIONS,
            ),
            Check(
                "Arrière-plan autorisé", !restricted,
                if (!restricted) "Aucune restriction." else "Activité en arrière-plan restreinte : à lever dans les réglages de l'app.",
                Fix.APP_SETTINGS,
            ),
        )
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
