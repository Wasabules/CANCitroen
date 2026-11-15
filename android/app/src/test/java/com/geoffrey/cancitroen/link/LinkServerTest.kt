package com.geoffrey.cancitroen.link

import com.geoffrey.cancitroen.link.LinkServer.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkServerTest {
    @Test fun ping() = assertEquals(Route.Ping, LinkServer.routeOf("GET /ping HTTP/1.0"))

    @Test fun blobLimitedInSize() {
        assertEquals(Route.Blob(1024), LinkServer.routeOf("GET /blob?kb=1024 HTTP/1.0"))
        assertEquals(Route.Blob(4096), LinkServer.routeOf("GET /blob?kb=999999 HTTP/1.0"))
        assertEquals(Route.Blob(1), LinkServer.routeOf("GET /blob?kb=-5 HTTP/1.0"))
    }

    @Test fun telemetryRoutes() {
        assertEquals(Route.TelemetryStatus, LinkServer.routeOf("GET /telemetry/status HTTP/1.0"))
        assertEquals(Route.TelemetrySummary(false), LinkServer.routeOf("GET /telemetry/summary HTTP/1.0"))
        // La position ne part que si le téléphone la demande.
        assertEquals(Route.TelemetrySummary(true), LinkServer.routeOf("GET /telemetry/summary?position=1 HTTP/1.0"))
        assertEquals(Route.TelemetrySamples(42, 100), LinkServer.routeOf("GET /telemetry/samples?after=42&limit=100 HTTP/1.0"))
        assertEquals(Route.TelemetrySamples(0, 5000), LinkServer.routeOf("GET /telemetry/samples?after=0&limit=999999 HTTP/1.0"))
        assertEquals(Route.TelemetrySamples(4, 10, 9), LinkServer.routeOf("GET /telemetry/samples?after=4&limit=10&upto=9 HTTP/1.0"))
        assertEquals(Route.TelemetryInventory, LinkServer.routeOf("GET /telemetry/inventory HTTP/1.0"))
        assertEquals(Route.TelemetryAck(42), LinkServer.routeOf("POST /telemetry/ack?id=42 HTTP/1.0"))
        assertEquals(Route.TelemetryPair("abc"), LinkServer.routeOf("POST /telemetry/pair?proof=abc HTTP/1.0"))
        assertEquals(Route.TelemetryTable(TelemetrySource.Table.TRIPS), LinkServer.routeOf("GET /telemetry/trips HTTP/1.0"))
    }

    @Test fun idRangesCompactes() {
        assertEquals("", idRanges(emptyList()))
        assertEquals("5-5", idRanges(listOf(5)))
        assertEquals("1-3,7-8,10-10", idRanges(listOf(1, 2, 3, 7, 8, 10)))
        assertEquals("0-1", idRanges(listOf(0, 1)))
    }

    @Test fun nothingElseIsServed() {
        for (line in listOf(
            "", "GET", "POST /ping HTTP/1.0", "GET /blob HTTP/1.0", "GET /blob?kb=x HTTP/1.0", "GET /history.db HTTP/1.0",
            "GET /telemetry/samples HTTP/1.0", "GET /telemetry/samples?after=-1 HTTP/1.0", "GET /telemetry/ack?id=1 HTTP/1.0",
            "POST /telemetry/ack HTTP/1.0", "POST /telemetry/pair HTTP/1.0", "GET /telemetry/pair?proof=a HTTP/1.0",
        )) {
            assertEquals(line, Route.NotFound, LinkServer.routeOf(line))
        }
    }
}
