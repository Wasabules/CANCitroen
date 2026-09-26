package com.geoffrey.cancitroen.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Lance des apps externes et résout leurs icônes.
 *
 * Liste de "candidats favoris" (Android Auto, Spotify, Maps, etc.) — l'app les
 * affiche si elles sont installées ; sinon les ignore.
 */
object AppShortcuts {

    /** Catalog des raccourcis "officiels" qu'on essaie d'afficher dans le Home. */
    val DEFAULT_SHORTCUTS: List<ShortcutCandidate> = listOf(
        ShortcutCandidate("Android Auto",  "com.google.android.projection.gearhead",     primary = true),
        ShortcutCandidate("Spotify",       "com.spotify.music"),
        ShortcutCandidate("YouTube Music", "com.google.android.apps.youtube.music"),
        ShortcutCandidate("Maps",          "com.google.android.apps.maps"),
        ShortcutCandidate("Waze",          "com.waze"),
        ShortcutCandidate("Téléphone",     "com.google.android.dialer"),
        ShortcutCandidate("Téléphone",     "com.android.dialer"),
        ShortcutCandidate("Messages",      "com.google.android.apps.messaging"),
        ShortcutCandidate("Bluetooth",     "com.android.settings", componentClass =
                            "com.android.settings.bluetooth.BluetoothSettings"),
        ShortcutCandidate("Caméra",        "com.android.camera"),
        ShortcutCandidate("Paramètres",    "com.android.settings"),
    )

    data class ShortcutCandidate(
        val label: String,
        val packageName: String,
        val componentClass: String? = null,
        val primary: Boolean = false,
    )

    data class ResolvedShortcut(
        val label: String,
        val packageName: String,
        val icon: ImageBitmap?,
        val primary: Boolean,
        /** Index du slot dans la grille (utile pour pickerSlot). Null = primary AA. */
        val slotIndex: Int? = null,
    )

    /** Une entrée de la grille : remplie ou vide (bouton "+"). */
    sealed class GridSlot {
        abstract val index: Int
        data class Filled(override val index: Int, val shortcut: ResolvedShortcut) : GridSlot()
        data class Empty(override val index: Int) : GridSlot()
    }

    /** Nombre de slots affichés dans la grille (excluant le hero AA). */
    const val GRID_SLOT_COUNT = 6

    /**
     * Retourne les raccourcis à afficher.
     *  - Le primaire (Android Auto) reste fixe en tête s'il est installé
     *  - Pour les autres slots, applique les overrides utilisateur s'ils existent,
     *    sinon retombe sur la liste DEFAULT_SHORTCUTS (apps installées seulement)
     */
    /**
     * Retourne :
     *  - le shortcut "primary" (Android Auto) s'il est installé,
     *  - puis [GRID_SLOT_COUNT] slots, chacun rempli (Filled) ou vide (Empty).
     *
     *  Pour chaque slot index :
     *   1. Si l'utilisateur a défini un override → on tente de le résoudre
     *   2. Sinon on prend une valeur par défaut depuis [DEFAULT_SHORTCUTS]
     *      (premières apps installées en ordre de la liste)
     *   3. Si rien à mettre → Empty (l'UI affichera un bouton "+")
     */
    fun resolveSlots(
        context: Context,
        overrides: Map<Int, String> = emptyMap(),
        candidates: List<ShortcutCandidate> = DEFAULT_SHORTCUTS,
    ): Pair<ResolvedShortcut?, List<GridSlot>> {
        val pm = context.packageManager

        val primary = candidates.firstOrNull { it.primary }?.let { c ->
            pm.getLaunchIntentForPackage(c.packageName)?.let {
                ResolvedShortcut(c.label, c.packageName, loadIcon(pm, c.packageName), true, null)
            }
        }

        val defaults = candidates.filterNot { it.primary }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .distinctBy { it.packageName }
            .toMutableList()

        // Packages déjà placés (override ou primary) : on ne les remet pas en défaut
        val claimed = mutableSetOf<String>()
        primary?.let { claimed += it.packageName }

        val slots = mutableListOf<GridSlot>()
        for (idx in 0 until GRID_SLOT_COUNT) {
            val pkg = overrides[idx]
            val resolved: ResolvedShortcut? = when {
                pkg != null -> {
                    pm.getLaunchIntentForPackage(pkg)?.let {
                        ResolvedShortcut(loadLabel(pm, pkg), pkg, loadIcon(pm, pkg), false, idx)
                    }
                }
                else -> {
                    val def = defaults.firstOrNull { it.packageName !in claimed }
                    if (def != null) {
                        defaults.remove(def)
                        ResolvedShortcut(def.label, def.packageName,
                            loadIcon(pm, def.packageName), false, idx)
                    } else null
                }
            }
            if (resolved != null) {
                claimed += resolved.packageName
                slots += GridSlot.Filled(idx, resolved)
            } else {
                slots += GridSlot.Empty(idx)
            }
        }
        return primary to slots
    }

    /** Toutes les apps avec un launcher intent — pour le picker. */
    fun listLaunchableApps(context: Context): List<ResolvedShortcut> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                ResolvedShortcut(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = try { ri.loadIcon(pm).toImageBitmap() } catch (_: Exception) { null },
                    primary = false,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadIcon(pm: PackageManager, pkg: String): ImageBitmap? = try {
        pm.getApplicationIcon(pkg).toImageBitmap()
    } catch (_: Exception) { null }

    private fun loadLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg.substringAfterLast('.') }

    /** Lance l'app par son package. Retourne true si OK. */
    fun launch(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppShortcuts", "launch($packageName) failed", e)
            false
        }
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        val w = if (intrinsicWidth > 0) intrinsicWidth else 96
        val h = if (intrinsicHeight > 0) intrinsicHeight else 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp.asImageBitmap()
    }
}
