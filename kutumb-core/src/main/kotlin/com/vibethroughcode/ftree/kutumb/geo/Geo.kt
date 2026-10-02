package com.vibethroughcode.ftree.kutumb.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LocationLabel { BIRTHPLACE, CURRENT }

enum class LocationSource { MANUAL, GEOCODED }

/**
 * One place for one person. [sharingEnabled] is per person, not per contact: off hides every one of
 * that person's locations from everything that reads them.
 */
data class PersonLocation(
    val personId: String,
    val label: LocationLabel,
    val lat: Double,
    val lon: Double,
    val source: LocationSource,
    val updatedAt: Long,
    val sharingEnabled: Boolean = true,
) {
    init {
        require(personId.isNotBlank()) { "personId is blank" }
        require(lat in -90.0..90.0) { "latitude out of range" }
        require(lon in -180.0..180.0) { "longitude out of range" }
    }
}

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088
    const val KM_PER_MILE = 1.609344

    /** Great-circle distance, in kilometres. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        // min() guards against a rounding hair above 1 for antipodal points.
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /** The location a person has chosen to share for [label], or null if they have none or share none. */
    fun visible(locations: Collection<PersonLocation>, personId: String, label: LocationLabel): PersonLocation? =
        locations.firstOrNull { it.personId == personId && it.label == label && it.sharingEnabled }

    /** Kilometres between two people's [label] locations; null unless both are known and shared. */
    fun distanceKm(locations: Collection<PersonLocation>, a: String, b: String, label: LocationLabel = LocationLabel.CURRENT): Double? {
        val from = visible(locations, a, label) ?: return null
        val to = visible(locations, b, label) ?: return null
        return haversineKm(from.lat, from.lon, to.lat, to.lon)
    }
}
