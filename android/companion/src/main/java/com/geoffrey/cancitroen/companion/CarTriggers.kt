package com.geoffrey.cancitroen.companion

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Android Auto sans fil commence par le Bluetooth : sa connexion est le signal le plus précoce
 * et le plus fiable que le téléphone est dans la voiture. Diffusion système reçue même app
 * fermée (exception aux diffusions implicites), à condition d'avoir l'autorisation « Appareils
 * à proximité » (Android 12 et plus).
 *
 * Tant que la voiture n'est pas reconnue, tout appareil lance une recherche de l'Atoto, qui
 * s'arrête seule au bout de [SessionPlan.SEARCH_MS] ; ensuite, seule la voiture la lance.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = device(intent)?.address
        val prefs = TransferPrefs(context)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (address != null) {
                    prefs.lastBluetooth = address
                    prefs.lastBluetoothMs = System.currentTimeMillis()
                }
                if (!prefs.paired) return
                val car = prefs.carBluetooth
                // Un casque, une montre : pas la voiture.
                if (car.isNotEmpty() && address != null && address != car) return
                if (!CarSessionService.start(context, "Bluetooth connecté", address)) {
                    TransferScheduler.onCarConnectionChanged(context)
                }
            }
            // La voiture coupe le Bluetooth au contact coupé : on vérifie sans attendre.
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (address != null && address == prefs.carBluetooth) {
                CarSessionService.poke()
            }
        }
    }

    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}

/** Après un redémarrage ou une mise à jour : la tâche de vérification doit exister. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TransferScheduler.refresh(context)
        }
    }
}
