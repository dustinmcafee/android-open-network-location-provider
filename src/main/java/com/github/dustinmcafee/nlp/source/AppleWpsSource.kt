package com.github.dustinmcafee.nlp.source

import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.github.dustinmcafee.nlp.net.ArpcEnvelope
import com.github.dustinmcafee.nlp.net.HttpClient
import com.github.dustinmcafee.nlp.net.proto.AppleWpsProto
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Online Wi-Fi positioning via Apple's WPS endpoint.
 *
 * SHIP-BLOCKER (see vendor/<company>/open-network-location-provider/SHIP-BLOCKERS.md): replace this
 * source with a commercial Wi-Fi positioning wrapper (Skyhook/HERE/Combain)
 * before any customer-facing release. The wrapper API is the same — feed in
 * a list of (BSSID, RSSI), get back a [Location].
 *
 * Phase 2 behaviour: takes the BSSIDs visible in a Wi-Fi scan, asks Apple
 * which ones it knows about, and centroids the returned positions weighted
 * by reported accuracy. Phase 3 will additionally consult the local learned
 * cache before falling back here.
 */
internal class AppleWpsSource(
    private val http: HttpClient = HttpClient,
    private val endpoint: String = "https://gs-loc.apple.com/clls/wloc",
) {
    /** A Wi-Fi observation: BSSID + signal strength (dBm, typically -100..0). */
    data class WifiObservation(
        val bssid: String,
        val rssi: Int,
    )

    /**
     * Result of a WPS query: the fused location ([fix]) and the raw per-BSSID
     * positions Apple returned for the BSSIDs we sent ([perBssidFixes]). The
     * fusion engine uses [fix] for delivery to the system and feeds
     * [perBssidFixes] into the learned cache so a future query against the
     * same BSSIDs can be served offline. The fused [fix] is computed via
     * RSSI-and-accuracy-weighted centroid + weighted-RMS dispersion, see
     * [fuseLocations] / [fixWeight] for the math.
     */
    data class Result(
        val fix: Location?,
        val perBssidFixes: List<AppleWpsProto.WifiResult>,
    ) {
        companion object {
            val EMPTY = Result(fix = null, perBssidFixes = emptyList())
        }
    }

    fun queryWithRaw(observations: List<WifiObservation>): Result {
        if (observations.isEmpty()) return Result.EMPTY

        // Map normalized BSSID → RSSI for the BSSIDs we actually observed.
        // Used both for response filtering (drop Apple's "neighbor expansion"
        // BSSIDs we can't see) and for RSSI-weighted centroiding.
        val rssiByBssid: Map<String, Int> =
            observations.associate { normalizeBssid(it.bssid) to it.rssi }

        val queries = rssiByBssid.keys.map { AppleWpsProto.WifiQuery(bssid = it) }
        val payload = AppleWpsProto.encodeRequest(queries, numWifiResults = 0)
        val body = ArpcEnvelope.wrap(payload)

        val started = SystemClock.elapsedRealtime()
        val response =
            try {
                http.post(endpoint, body, HttpClient.APPLE_LOCATIONS_HEADERS)
            } catch (e: IOException) {
                Log.w(TAG, "WPS POST failed: ${e.message}")
                return Result.EMPTY
            }
        if (response.statusCode !in 200..299) {
            Log.w(TAG, "WPS HTTP ${response.statusCode}")
            return Result.EMPTY
        }

        val protobuf =
            try {
                ArpcEnvelope.unwrap(response.body)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "WPS response too short: ${response.body.size} bytes")
                return Result.EMPTY
            }

        // Normalize BSSIDs in the response so set-membership matches the
        // request side. Apple's responses come back uppercase with colons,
        // but we normalize defensively in case a future server change varies.
        val allWithFixes =
            AppleWpsProto
                .parseResponse(protobuf)
                .filter { it.hasFix }
                .map { it.copy(bssid = normalizeBssid(it.bssid)) }

        // (A) Filter to BSSIDs we actually observed. Apple's neighbor-
        // expansion typically returns 50–150 BSSIDs that aren't in our
        // radio range, which inflates dispersion if used naively. Fall
        // back to all results only if Apple knew none of ours — useful
        // when our few BSSIDs happen to be unmapped but neighbors aren't.
        val onlyObserved = allWithFixes.filter { it.bssid in rssiByBssid }
        val toFuse = if (onlyObserved.isNotEmpty()) onlyObserved else allWithFixes
        val rssiForFuse: Map<String, Int> =
            if (onlyObserved.isNotEmpty()) rssiByBssid else emptyMap()

        Log.i(
            TAG,
            "WPS query: ${observations.size} BSSIDs in, " +
                "${allWithFixes.size} returned with positions, " +
                "${onlyObserved.size} of ours, fusing ${toFuse.size}, " +
                "${SystemClock.elapsedRealtime() - started}ms round-trip",
        )

        // Even when we have no usable fix to fuse (e.g. zero matches AND
        // empty fallback), we still return any per-BSSID positions Apple
        // gave us — those can seed the cache for future hits.
        val perBssid = onlyObserved.ifEmpty { allWithFixes }
        if (toFuse.isEmpty()) return Result(fix = null, perBssidFixes = perBssid)

        return Result(
            fix = fuseLocations(toFuse, rssiForFuse, started),
            perBssidFixes = perBssid,
        )
    }

    /** Backward-compatible thin wrapper for callers that only need the fix. */
    fun query(observations: List<WifiObservation>): Location? = queryWithRaw(observations).fix

    /**
     * Compute weight for a single fix combining geographic and signal terms.
     *   wGeo    = 1 / accuracy²   (per Apple's reported accuracy)
     *   wSignal = 10^((rssi+100)/30)   ≈ 100 at -40 dBm, ≈ 2 at -90 dBm
     *
     * For BSSIDs Apple returned that we don't have RSSI for (neighbor-
     * expansion fallback path), [rssiByBssid] is empty and wSignal collapses
     * to a constant — degrading gracefully to pure 1/accuracy² weighting.
     */
    private fun fixWeight(
        f: AppleWpsProto.WifiResult,
        rssiByBssid: Map<String, Int>,
    ): Double {
        val acc = (f.accuracyMeters ?: 100f).coerceAtLeast(1f).toDouble()
        val wGeo = 1.0 / (acc * acc)
        val rssi = rssiByBssid[f.bssid] ?: -95
        val wSignal = Math.pow(10.0, (rssi + 100) / 30.0)
        return wGeo * wSignal
    }

    /**
     * Weighted centroid + weighted-RMS dispersion of a set of WifiResults
     * that all have positions. Weighting blends Apple's per-AP accuracy with
     * our observed RSSI (closer == stronger == higher weight).
     */
    private fun fuseLocations(
        fixes: List<AppleWpsProto.WifiResult>,
        rssiByBssid: Map<String, Int>,
        startedElapsed: Long,
    ): Location {
        var sumLat = 0.0
        var sumLng = 0.0
        var sumW = 0.0
        for (f in fixes) {
            val w = fixWeight(f, rssiByBssid)
            sumLat += (f.latDeg ?: 0.0) * w
            sumLng += (f.lngDeg ?: 0.0) * w
            sumW += w
        }
        val lat = sumLat / sumW
        val lng = sumLng / sumW

        // Weighted-RMS dispersion: same weights as centroid so close,
        // strong-signal APs dominate the dispersion estimate too.
        var sqSumW = 0.0
        var sumW2 = 0.0
        for (f in fixes) {
            val w = fixWeight(f, rssiByBssid)
            val dx = haversineMeters(lat, lng, f.latDeg!!, f.lngDeg!!)
            sqSumW += w * dx * dx
            sumW2 += w
        }
        val dispersion = sqrt(sqSumW / sumW2).toFloat()

        // Effective accuracy: weighted-mean reported accuracy + dispersion.
        // Using the mean rather than max so a single far-away AP doesn't
        // poison the result when most contributors are close.
        var sumAccW = 0.0
        var sumW3 = 0.0
        for (f in fixes) {
            val w = fixWeight(f, rssiByBssid)
            sumAccW += (f.accuracyMeters ?: 100f) * w
            sumW3 += w
        }
        val meanReported = (sumAccW / sumW3).toFloat()
        val accuracy = (meanReported + dispersion).coerceIn(1f, 5_000f)

        return Location(PROVIDER_NAME).apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            elapsedRealtimeNanos = startedElapsed * 1_000_000L
            time = System.currentTimeMillis()
        }
    }

    private fun normalizeBssid(bssid: String): String =
        bssid.trim().lowercase().replace("-", ":").let { s ->
            if (':' in s) {
                s.uppercase()
            } else {
                // No separators — insert colons between octets.
                require(s.length == 12) { "bad BSSID: $bssid" }
                s.chunked(2).joinToString(":").uppercase()
            }
        }

    /** Spherical-earth haversine distance in metres. Good to ~0.5% over short distances. */
    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLam = Math.toRadians(lng2 - lng1)
        val a =
            Math.sin(dPhi / 2).let { it * it } +
                cos(phi1) * cos(phi2) * Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.asin(sqrt(a))
    }

    companion object {
        const val PROVIDER_NAME = "wps"
        private const val TAG = "AppleWpsSource"
    }
}
