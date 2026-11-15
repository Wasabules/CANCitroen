package com.geoffrey.cancitroen.link

import com.geoffrey.cancitroen.history.DataExport
import com.geoffrey.cancitroen.history.VehicleSummary
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.settings.AppSettingsRepository
import com.geoffrey.cancitroen.system.GpsFix
import kotlinx.coroutines.flow.first

/** Ce que le téléphone associé peut lire de l'historique. */
interface TelemetrySource {
    enum class Table { AGGREGATES, TRIPS, REFUELS }

    /** [ackId] : dernier échantillon que le téléphone a confirmé. */
    data class Status(val lastId: Long, val ackId: Long, val pending: Long)
    data class SampleBatch(val csv: String, val count: Int, val lastId: Long)

    /** Renvoie le jeton du téléphone si la preuve est bonne et qu'aucun autre n'est associé. */
    suspend fun pair(proof: String): String?
    suspend fun isAuthorized(token: String): Boolean
    suspend fun status(): Status
    suspend fun samples(afterId: Long, upToId: Long, limit: Int): SampleBatch

    /** Les échantillons encore en base, en plages de numéros : `12-500,520-900`. */
    suspend fun inventory(): String
    suspend fun table(table: Table): String

    /** L'état de la voiture en JSON, pour le compagnon ; [withPosition] : avec le dernier point GPS. */
    suspend fun summary(withPosition: Boolean = false): String

    /** Renvoie le nombre d'échantillons nouvellement confirmés. */
    suspend fun ack(id: Long): Long
}

/** Numéros triés → plages. */
internal fun idRanges(ids: List<Long>): String = buildString {
    var start = -1L
    var previous = -1L
    fun flush() {
        if (start < 0) return
        if (isNotEmpty()) append(',')
        append(start).append('-').append(previous)
    }
    for (id in ids) {
        if (id != previous + 1 || start < 0) {
            flush()
            start = id
        }
        previous = id
    }
    flush()
}

class HistoryTelemetrySource(
    private val dao: HistoryDao,
    private val settings: AppSettingsRepository,
    private val fingerprint: ByteArray,
    private val position: () -> GpsFix? = { null },
) : TelemetrySource {

    override suspend fun pair(proof: String): String? {
        val dev = settings.flow.first().dev
        if (!dev.transferEnabled || dev.transferTokenHash.isNotEmpty() || dev.transferCode.isEmpty()) return null
        if (!TransferSecurity.sameText(TransferSecurity.proof(dev.transferCode, fingerprint), proof)) return null
        val token = TransferSecurity.newToken()
        settings.completeTransferPairing(TransferSecurity.sha256Hex(token))
        return token
    }

    override suspend fun isAuthorized(token: String): Boolean {
        val dev = settings.flow.first().dev
        return dev.transferEnabled && dev.transferTokenHash.isNotEmpty() &&
            TransferSecurity.sameText(dev.transferTokenHash, TransferSecurity.sha256Hex(token))
    }

    override suspend fun status(): TelemetrySource.Status {
        val last = dao.lastSampleId() ?: 0
        var ack = settings.flow.first().dev.transferAckedId
        // Base recréée, les numéros repartent de zéro : l'ancienne confirmation ne vaut plus.
        if (ack > last) {
            settings.setTransferAckedId(0)
            ack = 0
        }
        return TelemetrySource.Status(last, ack, dao.countSamplesAfter(ack))
    }

    override suspend fun inventory(): String = idRanges(dao.allSampleIds())

    override suspend fun samples(afterId: Long, upToId: Long, limit: Int): TelemetrySource.SampleBatch {
        val rows = dao.samplesAfter(afterId, upToId, limit)
        return TelemetrySource.SampleBatch(DataExport.samplesCsv(rows), rows.size, rows.lastOrNull()?.id ?: afterId)
    }

    override suspend fun table(table: TelemetrySource.Table): String = when (table) {
        TelemetrySource.Table.AGGREGATES -> DataExport.aggregatesCsv(dao.allAggregatesOnce())
        TelemetrySource.Table.TRIPS -> DataExport.tripsCsv(dao.allTripsOnce())
        TelemetrySource.Table.REFUELS -> DataExport.refuelsCsv(dao.allRefuelsOnce())
    }

    override suspend fun summary(withPosition: Boolean): String {
        val trips = dao.allTripsOnce()
        val fuel = settings.flow.first().fuel
        return VehicleSummary.compute(
            now = System.currentTimeMillis(),
            recent = dao.latestSamples(VehicleSummary.RECENT_SAMPLES).reversed(),
            lastAggregate = dao.latestAggregate(),
            trips = trips,
            refuels = dao.allRefuelsOnce(),
            consumptionFactor = fuel.consumptionFactor,
            currentCounters = trips.lastOrNull()?.takeIf { it.endTs == null }?.let { dao.tripResumeCounters(it.id) },
            pricePerLiter = fuel.pricePerLiter,
            position = if (withPosition) position() else null,
        ).toJson()
    }

    override suspend fun ack(id: Long): Long {
        val before = settings.flow.first().dev.transferAckedId
        val acked = id.coerceAtMost(dao.lastSampleId() ?: 0)
        if (acked <= before) return 0
        settings.setTransferAckedId(acked)
        return acked - before
    }
}
