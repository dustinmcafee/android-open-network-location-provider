package com.github.dustinmcafee.nlp.observe

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.github.dustinmcafee.nlp.source.AppleWpsSource.WifiObservation
import com.github.dustinmcafee.nlp.store.LearnedCacheDb

/**
 * Passive GPS observer that anchors visible BSSIDs to learned positions.
 *
 * Subscribes to [LocationManager.GPS_PROVIDER] at a low duty cycle. Each
 * incoming GPS fix that meets the confidence bar
 * (accuracy ≤ [MAX_GPS_ACCURACY_M], device hasn't moved fast since the last
 * scan) is paired with the most recent Wi-Fi observation list and persisted
 * into [LearnedCacheDb] via Welford's update.
 *
 * Rationale for GPS-only learning (vs. also feeding back from WPS fixes):
 * WPS positions inherit Apple's database-level error. Feeding our own WPS
 * fixes back into the cache compounds that error and creates a slow drift
 * away from ground truth. GPS, by contrast, is independent — its errors
 * don't correlate with WPS errors, so the learned cache acts as a
 * triangulation against the WPS source. We only learn from BSSIDs with
 * strong RSSI ([MIN_RSSI_DBM]) so we don't pin distant APs to our location.
 *
 * Note: this observer only consumes GPS — it does not bind a network
 * provider request, so it doesn't itself trigger fusion-engine ticks.
 */
internal class GpsLearningObserver(
    private val context: Context,
    private val handler: Handler,
    private val cache: LearnedCacheDb,
    private val getCurrentObservations: () -> List<WifiObservation>?,
) {

    private val lm: LocationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var registered = false
    private var lastLearnEpochSec: Long = 0L

    fun start() {
        if (registered) return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                /* minTimeMs = */ MIN_INTERVAL_MS,
                /* minDistanceM = */ 0f,
                gpsListener,
                handler.looper,
            )
            registered = true
            Log.i(TAG, "subscribed to GPS_PROVIDER at ${MIN_INTERVAL_MS}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "ACCESS_FINE_LOCATION not granted: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // GPS_PROVIDER may not exist on Wi-Fi-only SKUs.
            Log.w(TAG, "GPS_PROVIDER unavailable: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try { lm.removeUpdates(gpsListener) } catch (_: Throwable) {}
        registered = false
        Log.i(TAG, "unsubscribed from GPS_PROVIDER")
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun handle(gps: Location) {
        if (gps.accuracy <= 0f || gps.accuracy > MAX_GPS_ACCURACY_M) {
            Log.d(TAG, "GPS fix accuracy=${gps.accuracy}m — too loose, skipping")
            return
        }
        val obs = getCurrentObservations() ?: run {
            Log.d(TAG, "no recent BSSID scan — skipping")
            return
        }
        val strong = obs.filter { it.rssi >= MIN_RSSI_DBM }
        if (strong.isEmpty()) {
            Log.d(TAG, "no strong-signal BSSIDs in latest scan — skipping")
            return
        }

        val nowEpochSec = System.currentTimeMillis() / 1000L
        val started = SystemClock.elapsedRealtime()
        for (o in strong) {
            cache.observe(
                bssid = normalizeBssid(o.bssid),
                latDeg = gps.latitude,
                lngDeg = gps.longitude,
                nowEpochSec = nowEpochSec,
            )
        }
        lastLearnEpochSec = nowEpochSec
        Log.i(TAG, "learned: ${strong.size} BSSIDs anchored at " +
            "lat=${gps.latitude} lng=${gps.longitude} " +
            "acc=${gps.accuracy}m (${SystemClock.elapsedRealtime() - started}ms, " +
            "cache: ${cache.stats()})")
    }

    private fun normalizeBssid(bssid: String): String =
        bssid.trim().lowercase().replace("-", ":").let { s ->
            if (':' in s) s.uppercase() else {
                require(s.length == 12) { "bad BSSID: $bssid" }
                s.chunked(2).joinToString(":").uppercase()
            }
        }

    companion object {
        private const val TAG = "NlpGpsLearner"
        // 60s between GPS fixes — battery-friendly; APs don't move.
        private const val MIN_INTERVAL_MS = 60_000L
        // Tighter than the WPS gate so we only learn from confident GPS fixes.
        private const val MAX_GPS_ACCURACY_M = 30f
        // Only anchor BSSIDs we're physically close to.
        private const val MIN_RSSI_DBM = -80
    }
}
