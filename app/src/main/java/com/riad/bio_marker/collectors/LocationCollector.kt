package com.riad.bio_marker.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Privacy-preserving mobility collector.
 *
 * **Never persists raw lat/lon.** It bins recent fixes into a coarse 250 m
 * spatial grid in memory only, then reports two aggregate scalars:
 *  - `entropy`        : Shannon entropy of grid-cell visit distribution (0..)
 *  - `radiusKm`       : approximate radius of gyration of the day's fixes
 *
 * Spec note: only "how much movement" matters, not "where".
 */
class LocationCollector(private val context: Context) : LocationListener {

    /** Raw fixes are kept only in this in-memory map and discarded on [reset]. */
    private val gridCounts = mutableMapOf<Long, Int>()
    private var refLat = Double.NaN
    private var refLon = Double.NaN
    private var totalFixes = 0
    private var sumXmKm = 0.0   // sum of (x-meanX)^2 in km^2 (incremental)
    private var sumYmKm = 0.0
    private var meanX = 0.0
    private var meanY = 0.0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Sample sparingly to save battery: every ~5 min, ≥100 m delta.
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return false
        }
        return try {
            lm.requestLocationUpdates(provider, 5 * 60 * 1000L, 100f, this)
            true
        } catch (_: SecurityException) { false }
    }

    fun stop() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
        } catch (_: Throwable) { /* ignore */ }
    }

    override fun onLocationChanged(location: Location) {
        if (refLat.isNaN()) {
            refLat = location.latitude
            refLon = location.longitude
        }
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))
        val xKm = (location.longitude - refLon) * mPerDegLon / 1000.0
        val yKm = (location.latitude - refLat) * mPerDegLat / 1000.0

        // 250 m grid bin = 0.25 km.
        val gx = (xKm / 0.25).roundToInt()
        val gy = (yKm / 0.25).roundToInt()
        val key = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
        gridCounts[key] = (gridCounts[key] ?: 0) + 1

        // Welford for radius-of-gyration components.
        totalFixes += 1
        val dx = xKm - meanX
        meanX += dx / totalFixes
        sumXmKm += dx * (xKm - meanX)
        val dy = yKm - meanY
        meanY += dy / totalFixes
        sumYmKm += dy * (yKm - meanY)
    }

    @Deprecated("Required by Android < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    /** Shannon entropy of grid distribution (nats). 0 = stayed in one cell. */
    fun entropy(): Float {
        if (totalFixes == 0) return 0f
        val n = totalFixes.toDouble()
        var h = 0.0
        for (c in gridCounts.values) {
            val p = c / n
            if (p > 0) h -= p * ln(p)
        }
        return h.toFloat()
    }

    fun radiusKm(): Float {
        if (totalFixes < 2) return 0f
        val varX = sumXmKm / totalFixes
        val varY = sumYmKm / totalFixes
        return kotlin.math.sqrt(varX + varY).toFloat()
    }

    fun fixCount(): Int = totalFixes

    /** Should be called at end-of-day after the snapshot is persisted. */
    fun reset() {
        gridCounts.clear()
        refLat = Double.NaN
        refLon = Double.NaN
        totalFixes = 0
        sumXmKm = 0.0
        sumYmKm = 0.0
        meanX = 0.0
        meanY = 0.0
    }
}

