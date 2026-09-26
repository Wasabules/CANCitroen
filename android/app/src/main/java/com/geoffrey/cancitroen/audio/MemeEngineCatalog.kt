package com.geoffrey.cancitroen.audio

import android.content.Context

data class MemeEngine(
    val id: String,
    val label: String,
    val emoji: String,
    val assetPath: String,
)

/**
 * Catalog des "moteurs meme" disponibles. On scanne `assets/memes/engine/` et
 * on présente la liste à l'utilisateur. Chaque fichier court (mp3/ogg/wav) y
 * devient un "moteur" potentiel.
 */
object MemeEngineCatalog {

    fun listAvailable(context: Context): List<MemeEngine> {
        val files = try {
            context.assets.list("memes/engine")?.filter {
                val l = it.lowercase()
                l.endsWith(".mp3") || l.endsWith(".ogg") || l.endsWith(".wav") ||
                    l.endsWith(".m4a")
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        return files.map { fname ->
            val baseName = fname.substringBeforeLast(".")
            MemeEngine(
                id = baseName,
                label = humanLabel(baseName),
                emoji = pickEmoji(baseName),
                assetPath = "memes/engine/$fname",
            )
        }.sortedBy { it.label }
    }

    private fun humanLabel(id: String): String = id
        .replace('_', ' ').replace('-', ' ')
        .split(' ')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    private fun pickEmoji(id: String): String {
        val l = id.lowercase()
        return when {
            "fart" in l || "prout" in l -> "💨"
            "apple" in l || "pay" in l -> "💳"
            "buzzer" in l -> "🔔"
            "fahhh" in l || "fah" in l -> "📢"
            "discord" in l -> "💬"
            "shock" in l || "surpris" in l -> "😱"
            "horn" in l || "klaxon" in l -> "📯"
            "bruh" in l -> "🤦"
            "boom" in l -> "💥"
            "bell" in l || "ding" in l -> "🔔"
            "drum" in l -> "🥁"
            else -> "🔊"
        }
    }
}
