package com.geoffrey.cancitroen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        // Sur Android 12+, startForegroundService au boot peut lever
        // ForegroundServiceStartNotAllowedException (écran pas encore
        // déverrouillé, restrictions OEM). Sans try/catch, le Receiver
        // crash silencieusement et le service n'est jamais relancé.
        // CanService est aussi déclenché par USB_DEVICE_ATTACHED + lancement
        // utilisateur, donc on peut tolérer l'échec ici.
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, CanService::class.java)
            )
            Log.i(TAG, "CanService lancé au boot")
        } catch (e: Throwable) {
            Log.w(TAG, "startForegroundService refusé au boot : ${e.message}")
        }
    }
}
