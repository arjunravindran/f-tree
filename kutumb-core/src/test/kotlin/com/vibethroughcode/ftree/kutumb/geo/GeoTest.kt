package com.vibethroughcode.ftree.kutumb.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    private fun loc(id: String, lat: Double, lon: Double, label: LocationLabel = LocationLabel.CURRENT, share: Boolean = true) =
        PersonLocation(id, label, lat, lon, LocationSource.MANUAL, 1, share)

    @Test
    fun haversineMatchesKnownDistances() {
        // London to Paris is about 344 km; New York to Los Angeles about 3936 km.
        assertEquals(344.0, Geo.haversineKm(51.5074, -0.1278, 48.8566, 2.3522), 2.0)
        assertEquals(3936.0, Geo.haversineKm(40.7128, -74.0060, 34.0522, -118.2437), 10.0)
        assertEquals(0.0, Geo.haversineKm(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test
    fun antipodesAndTheDateLineDoNotBreakIt() {
        assertEquals(Math.PI * 6371.0088, Geo.haversineKm(0.0, 0.0, 0.0, 180.0), 0.01)
        assertEquals(Geo.haversineKm(0.0, 179.0, 0.0, -179.0), Geo.haversineKm(0.0, 179.0, 0.0, 181.0 - 360.0), 1e-6)
        assertEquals(222.4, Geo.haversineKm(0.0, 179.0, 0.0, -179.0), 0.5)
    }

    @Test
    fun aLocationMustBeOnTheGlobe() {
        runCatching { loc("a", 91.0, 0.0) }.let { assertTrue(it.isFailure) }
        runCatching { loc("a", 0.0, 181.0) }.let { assertTrue(it.isFailure) }
    }

    @Test
    fun distanceNeedsBothPlacesAndBothSharing() {
        val all = listOf(loc("a", 51.5, -0.1), loc("b", 48.9, 2.35))
        assertNotNull(Geo.distanceKm(all, "a", "b"))
        assertNull(Geo.distanceKm(all, "a", "nobody"))
        // Sharing is a switch on the person: either side off hides the distance.
        assertNull(Geo.distanceKm(listOf(loc("a", 51.5, -0.1), loc("b", 48.9, 2.35, share = false)), "a", "b"))
        // A different label is a different question.
        assertNull(Geo.distanceKm(all, "a", "b", LocationLabel.BIRTHPLACE))
    }
}
