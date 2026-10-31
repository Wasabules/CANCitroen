package com.geoffrey.cancitroen.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.VehicleSample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La rétention supprime des données : rien ne doit disparaître sans être agrégé. */
@RunWith(AndroidJUnit4::class)
class RollupAggregatorTest {

    private lateinit var db: HistoryDatabase
    private val dao get() = db.historyDao()
    private val rollup get() = RollupAggregator(dao, db)

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = System.currentTimeMillis()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            HistoryDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun sample(ts: Long, speed: Float = 50f) = VehicleSample(ts = ts, speedKmh = speed, fuelPct = 70)

    private fun aggregate(ts: Long, bucket: Long, count: Int) = AggregatedSample(
        ts = ts, bucketMs = bucket, count = count, speedAvg = 50f, speedMax = 60f, fuelPct = 70,
    )

    private suspend fun aggregatedWeight(): Int =
        dao.aggregatesAllInRangeOnce(0L, now + day).sumOf { it.count }

    @Test
    fun brutNonEnvoyeAttendLeTelephone() = runBlocking {
        val old = now - 10 * day
        repeat(36) { dao.insertSample(sample(old + it * 5_000L)) }

        RollupAggregator(dao, db, rawHold = { RawHold(true, dao.oldestSampleTsAfter(0)) }).runOnce()
        assertEquals("gardé tant qu'il n'est pas reçu", 36, dao.samplesInRangeOnce(0L, now + day).size)

        // Reçu, et plus vieux que la marge de 7 jours : la rétention reprend.
        val lastId = dao.lastSampleId()!!
        RollupAggregator(dao, db, rawHold = { RawHold(true, dao.oldestSampleTsAfter(lastId)) }).runOnce()
        assertEquals(0, dao.samplesInRangeOnce(0L, now + day).size)
        assertEquals("aucun échantillon perdu", 36, aggregatedWeight())
        assertEquals("le numéro survit à l'agrégation", lastId, dao.lastSampleId())
    }

    @Test
    fun brutRecuGardeSeptJoursPourRecuperation() = runBlocking {
        repeat(12) { dao.insertSample(sample(now - 2 * day + it * 5_000L)) }
        val lastId = dao.lastSampleId()!!

        RollupAggregator(dao, db, rawHold = { RawHold(true, dao.oldestSampleTsAfter(lastId)) }).runOnce()

        assertEquals(12, dao.samplesInRangeOnce(0L, now + day).size)
        assertEquals(12, dao.samplesAfter(0, lastId, 100).size)
    }

    @Test
    fun attenteLimiteeATrenteJours() = runBlocking {
        val tooOld = now - 40 * day
        repeat(12) { dao.insertSample(sample(tooOld + it * 5_000L)) }
        val waiting = now - 10 * day
        repeat(12) { dao.insertSample(sample(waiting + it * 5_000L)) }

        RollupAggregator(dao, db, rawHold = { RawHold(true, dao.oldestSampleTsAfter(0)) }).runOnce()

        assertEquals(12, dao.samplesInRangeOnce(0L, now + day).size)
        assertEquals(12, aggregatedWeight())
    }

    @Test
    fun brutAncienAgregeBrutRecentConserve() = runBlocking {
        val old = now - 7 * hour
        repeat(36) { dao.insertSample(sample(old + it * 5_000L)) }
        val recent = now - hour
        repeat(10) { dao.insertSample(sample(recent + it * 5_000L)) }

        rollup.runOnce()

        assertEquals(10, dao.samplesInRangeOnce(0L, now + day).size)
        assertEquals("aucun échantillon perdu", 36, aggregatedWeight())
    }

    @Test
    fun deuxPassagesNeDoublentRien() = runBlocking {
        repeat(36) { dao.insertSample(sample(now - 7 * hour + it * 5_000L)) }
        rollup.runOnce()
        val first = dao.aggregatesAllInRangeOnce(0L, now + day)
        rollup.runOnce()
        assertEquals(first.size, dao.aggregatesAllInRangeOnce(0L, now + day).size)
        assertEquals(36, aggregatedWeight())
    }

    @Test
    fun paliersSuperieursGardentLePoidsDesEchantillons() = runBlocking {
        val minute = RollupAggregator.ONE_MIN
        val base = (now - 8 * day) / minute * minute
        dao.insertAggregates((0 until 10).map { aggregate(base + it * minute, minute, 12) })
        val older = (now - 40 * day) / RollupAggregator.THIRTY_MIN * RollupAggregator.THIRTY_MIN
        dao.insertAggregates(listOf(aggregate(older, RollupAggregator.THIRTY_MIN, 360)))

        rollup.runOnce()

        val all = dao.aggregatesAllInRangeOnce(0L, now + day)
        assertTrue("les sources sont remplacées", all.none { it.bucketMs == minute })
        assertEquals(10 * 12 + 360, all.sumOf { it.count })
    }
}
