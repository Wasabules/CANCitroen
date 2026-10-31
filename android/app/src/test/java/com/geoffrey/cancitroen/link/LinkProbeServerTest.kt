package com.geoffrey.cancitroen.link

import com.geoffrey.cancitroen.link.LinkProbeServer.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkProbeServerTest {
    @Test fun ping() = assertEquals(Route.Ping, LinkProbeServer.routeOf("GET /ping HTTP/1.0"))

    @Test fun blobLimitedInSize() {
        assertEquals(Route.Blob(1024), LinkProbeServer.routeOf("GET /blob?kb=1024 HTTP/1.0"))
        assertEquals(Route.Blob(4096), LinkProbeServer.routeOf("GET /blob?kb=999999 HTTP/1.0"))
        assertEquals(Route.Blob(1), LinkProbeServer.routeOf("GET /blob?kb=-5 HTTP/1.0"))
    }

    @Test fun nothingElseIsServed() {
        for (line in listOf("", "GET", "POST /ping HTTP/1.0", "GET /blob HTTP/1.0", "GET /blob?kb=x HTTP/1.0", "GET /history.db HTTP/1.0")) {
            assertEquals(line, Route.NotFound, LinkProbeServer.routeOf(line))
        }
    }
}
