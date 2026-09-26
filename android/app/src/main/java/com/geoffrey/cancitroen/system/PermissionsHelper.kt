package com.geoffrey.cancitroen.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Helpers pour les permissions runtime + opt-out battery / launcher. */
object PermissionsHelper {

    fun isLocationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Lance le settings intent pour exempter l'app de l'optim batterie. */
    @Suppress("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (e: Exception) {
            // fallback : ouvre la liste des paramètres batterie
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }

    /** Ouvre les settings "default apps" pour permettre de choisir CANCitroen comme launcher. */
    fun openHomeSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Sur certains OEMs, fallback vers les paramètres généraux
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Résout le package qui répond actuellement à l'intent HOME (le launcher
     * par défaut courant). Retourne null si aucun choix utilisateur n'a été fait
     * (Android affichera alors le picker) ou en cas d'erreur.
     */
    fun currentDefaultHomePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val pkg = info.activityInfo?.packageName ?: return null
        // "android" = picker système ; pas un vrai launcher par défaut.
        return pkg.takeIf { it != "android" }
    }

    /** True si CANCitroen est le launcher par défaut actuel. */
    fun isDefaultHome(context: Context): Boolean =
        currentDefaultHomePackage(context) == context.packageName

    /** Ouvre Réglages → Apps → Accès aux notifications.
     *  Nécessaire pour que MediaSessionManager.getActiveSessions soit autorisé. */
    fun openNotificationListenerSettings(context: Context) {
        try {
            // Action documentée Android, fonctionne SDK 22+
            context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // fallback : settings de l'app
            openAppDetails(context)
        }
    }

    /** Vérifie si le NotificationListenerService est activé pour ce package. */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val pkg = context.packageName
        return flat.split(":").any { it.startsWith("$pkg/") }
    }

    /** Ouvre les paramètres détaillés de l'app (gestion permissions, batterie, etc.). */
    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
