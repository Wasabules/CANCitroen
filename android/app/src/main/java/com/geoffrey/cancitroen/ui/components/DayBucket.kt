package com.geoffrey.cancitroen.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bucketise une série temporelle (ts, value) par jour calendaire et applique
 * [reducer] à chaque groupe de valeurs. Retourne (label, valeur) ordonnés
 * chronologiquement, prêts pour un bar chart.
 *
 *  @param labelFormat motif SimpleDateFormat (par défaut "d/M")
 */
fun aggregateByDay(
    points: List<Pair<Double, Double>>,
    reducer: (List<Double>) -> Double,
    labelFormat: String = "d/M",
): List<Pair<String, Double>> {
    if (points.isEmpty()) return emptyList()
    val tz = TimeZone.getDefault()
    // Clé = jour local (epoch day décalé du fuseau, heure d'été comprise) :
    // pas de SimpleDateFormat par point (jusqu'à ~10 000 points sur 7 jours).
    val byDay = java.util.TreeMap<Long, MutableList<Double>>()
    val dayTs = HashMap<Long, Long>()
    for ((ts, v) in points) {
        val t = ts.toLong()
        val day = Math.floorDiv(t + tz.getOffset(t), DAY_MS)
        byDay.getOrPut(day) { mutableListOf() }.add(v)
        dayTs[day] = t
    }
    val sdfLabel = SimpleDateFormat(labelFormat, Locale.FRANCE)
    return byDay.map { (day, ys) -> sdfLabel.format(Date(dayTs.getValue(day))) to reducer(ys) }
}

private const val DAY_MS = 24L * 3600L * 1000L
