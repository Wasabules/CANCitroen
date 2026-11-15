package com.geoffrey.cancitroen.companion

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Avec séparateur de milliers et virgule : 154 336 km, 6,2 L/100. */
fun number(value: Double, decimals: Int): String = String.format(Locale.FRANCE, "%,.${decimals}f", value)

fun dayTime(ms: Long): String = SimpleDateFormat("EEE dd/MM HH:mm", Locale.FRANCE).format(Date(ms))

fun minutes(ms: Long): String {
    val total = (ms / 60_000).coerceAtLeast(0)
    return if (total < 60) "$total min" else "${total / 60} h ${"%02d".format(total % 60)}"
}

/** Un euro au centime : 1,40 €. */
fun euros(value: Double): String = "${number(value, 2)} €"
