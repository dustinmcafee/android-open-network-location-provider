package com.github.dustinmcafee.nlp.source

import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.github.dustinmcafee.nlp.store.LearnedCacheDb
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Offline positioning source — looks up observed BSSIDs in [LearnedCacheDb]
 * and returns a fused [Location] when enough cached BSSIDs match with
 * sufficient confidence.
 *
 * Design intent: this is the *fast path*. When it returns a non-null fix,
 * the fusion engine skips the online WPS round-trip entirely (sub-millisecond
 * vs. 200–500 ms). When it returns null, fusion falls through to WPS.
 *
 * Confidence gates (tunable):
 *   - At least [MIN_BSSIDS_FOR_FIX] cache hits in the current observation set
 *   - At least one hit must be "strong" (RSSI > [STRONG_RSSI_DBM])
 *   - At least one entry must have ≥ [MIN_SAMPLES_FOR_TRUST] observations
 *
 * Without these gates the source would happily report a fix from a single
 * once-seen BSSID — which is worse than WPS would do, so we'd rather defer.
 */
internal class LearnedCacheSource(
    private val db: LearnedCacheDb,
) {
    fun query(observations: List<AppleWpsSource.WifiObservation>): Location? {
        if (observations.size < MIN_BSSIDS_FOR_FIX) return null

        val rssiByBssid = observations.associate { normalizeBssid(it.bssid) to it.rssi }
        val matches = db.getAll(rssiByBssid.keys).values.toList()

        if (matches.size < MIN_BSSIDS_FOR_FIX) return null
        if (matches.none { it.sampleCount >= MIN_SAMPLES_FOR_TRUST }) return null
        if (matches.none { (rssiByBssid[it.bssid] ?: -100) > STRONG_RSSI_DBM }) return null

        val started = SystemClock.elapsedRealtime()
        val fix = fuseEntries(matches, rssiByBssid, started)
        Log.i(
            TAG,
            "cache hit: ${matches.size} of ${observations.size} BSSIDs → " +
                "lat=${fix.latitude} lng=${fix.longitude} acc=${fix.accuracy}m " +
                "(${SystemClock.elapsedRealtime() - started}ms)",
        )
        return fix
    }

    /**
     * Centroid + accuracy estimate for a set of cached entries the device is
     * currently seeing. Weighted by:
     *   - Sample count: more-observed BSSIDs are more reliable
     *   - Inverse-square dispersion: tightly-localized BSSIDs pull harder
     *   - Signal strength (10^((rssi+100)/30)): close APs dominate
     */
    private fun fuseEntries(
        entries: List<LearnedCacheDb.Entry>,
        rssiByBssid: Map<String, Int>,
        startedElapsed: Long,
    ): Location {
        var sumLat = 0.0
        var sumLng = 0.0
        var sumW = 0.0
        for (e in entries) {
            val w = entryWeight(e, rssiByBssid)
            sumLat += e.latDeg * w
            sumLng += e.lngDeg * w
            sumW += w
        }
        val lat = sumLat / sumW
        val lng = sumLng / sumW

        // Accuracy: the dispersion of the contributing entries themselves
        // (how well-pinned each AP is in the cache) plus the dispersion of
        // their centroids around the fused point.
        var sumDispW = 0.0
        var sqDistSumW = 0.0
        var sumW2 = 0.0
        for (e in entries) {
            val w = entryWeight(e, rssiByBssid)
            sumDispW += e.dispersionMeters * w
            val dist = haversineMeters(lat, lng, e.latDeg, e.lngDeg)
            sqDistSumW += w * dist * dist
            sumW2 += w
        }
        val avgDispersion = (sumDispW / sumW2).toFloat()
        val centroidSpread = sqrt(sqDistSumW / sumW2).toFloat()
        val accuracy = (avgDispersion + centroidSpread).coerceIn(5f, 5_000f)

        return Location(PROVIDER_NAME).apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            elapsedRealtimeNanos = startedElapsed * 1_000_000L
            time = System.currentTimeMillis()
        }
    }

    private fun entryWeight(
        e: LearnedCacheDb.Entry,
        rssiByBssid: Map<String, Int>,
    ): Double {
        // Sample weight: log so doubling sample count doesn't double weight.
        val wSamples = Math.log1p(e.sampleCount.toDouble())
        // Dispersion weight: 1/(disp+1)² — tightly-pinned APs dominate.
        val disp = e.dispersionMeters.toDouble().coerceAtLeast(1.0)
        val wDisp = 1.0 / (disp * disp)
        // Signal weight: same shape as AppleWpsSource (consistency).
        val rssi = rssiByBssid[e.bssid] ?: -95
        val wSignal = Math.pow(10.0, (rssi + 100) / 30.0)
        return wSamples * wDisp * wSignal
    }

    private fun normalizeBssid(bssid: String): String =
        bssid.trim().lowercase().replace("-", ":").let { s ->
            if (':' in s) {
                s.uppercase()
            } else {
                require(s.length == 12) { "bad BSSID: $bssid" }
                s.chunked(2).joinToString(":").uppercase()
            }
        }

    private fun haversineMeters(
        la1: Double,
        ln1: Double,
        la2: Double,
        ln2: Double,
    ): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(la1)
        val phi2 = Math.toRadians(la2)
        val dPhi = Math.toRadians(la2 - la1)
        val dLam = Math.toRadians(ln2 - ln1)
        val a =
            Math.sin(dPhi / 2).let { it * it } +
                cos(phi1) * cos(phi2) * Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.asin(sqrt(a))
    }

    companion object {
        const val PROVIDER_NAME = "cached"
        private const val TAG = "NlpLearnedCache"

        private const val MIN_BSSIDS_FOR_FIX = 3
        private const val MIN_SAMPLES_FOR_TRUST = 3
        private const val STRONG_RSSI_DBM = -80
    }
}
