package com.github.dustinmcafee.nlp.source

import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.github.dustinmcafee.nlp.observe.CellObserver
import com.github.dustinmcafee.nlp.store.OfflineCellDb
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Offline cell-tower positioning source. Looks up each observed cell in the
 * shipped OpenCellID-derived SQLite, and returns a signal-weighted centroid
 * with provider="cell".
 *
 * Accuracy class: 200m–2km depending on tower density and the device's
 * radio types. Cell-tower positioning is much coarser than Wi-Fi, so this
 * source is the *fallback* when no Wi-Fi BSSIDs are visible (or when WPS
 * is unreachable). It will never be the first thing the fusion engine
 * tries — that's always the learned cache.
 *
 * Returns null when:
 *   - The DB file isn't on disk yet (first-boot, before fetcher runs)
 *   - None of the observed cells are in the DB (rural / new tower)
 *   - We have only one cell with no signal info (can't estimate accuracy)
 */
internal class OfflineCellDbSource(private val db: OfflineCellDb) {

    fun query(observations: List<CellObserver.CellObservation>): Location? {
        if (!db.isAvailable || observations.isEmpty()) return null

        val matches = observations.mapNotNull { obs ->
            val t = db.lookup(obs) ?: return@mapNotNull null
            Pair(obs, t)
        }
        if (matches.isEmpty()) return null

        val started = SystemClock.elapsedRealtime()
        val fix = fuseTowers(matches, started)
        Log.i(TAG, "cell hit: ${matches.size} of ${observations.size} cells → " +
            "lat=${fix.latitude} lng=${fix.longitude} acc=${fix.accuracy}m " +
            "(${SystemClock.elapsedRealtime() - started}ms)")
        return fix
    }

    /**
     * Signal-weighted centroid of matched towers. Weight per tower:
     *   - signal: 10^((dbm + 120) / 30) — typical LTE RSRP range -140..-44 dBm
     *   - 1/range²: smaller-coverage cells are more localizing
     *
     * Effective accuracy = max(reported tower range, weighted dispersion of
     * tower positions around the centroid). Bounded to [50m, 5km] —
     * anything outside that range is suspicious enough to clamp.
     */
    private fun fuseTowers(
        matches: List<Pair<CellObserver.CellObservation, OfflineCellDb.Tower>>,
        startedElapsed: Long,
    ): Location {
        var sumLat = 0.0
        var sumLng = 0.0
        var sumW = 0.0
        for ((obs, tower) in matches) {
            val w = towerWeight(obs.signalDbm, tower.rangeMeters)
            sumLat += tower.latDeg * w
            sumLng += tower.lngDeg * w
            sumW += w
        }
        val lat = sumLat / sumW
        val lng = sumLng / sumW

        val maxRange = matches.maxOf { it.second.rangeMeters }.toFloat()
        var sqDistSumW = 0.0
        var sumW2 = 0.0
        for ((obs, tower) in matches) {
            val w = towerWeight(obs.signalDbm, tower.rangeMeters)
            val dist = haversineMeters(lat, lng, tower.latDeg, tower.lngDeg)
            sqDistSumW += w * dist * dist
            sumW2 += w
        }
        val dispersion = sqrt(sqDistSumW / sumW2).toFloat()
        val accuracy = maxOf(maxRange, dispersion).coerceIn(50f, 5_000f)

        return Location(PROVIDER_NAME).apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            elapsedRealtimeNanos = startedElapsed * 1_000_000L
            time = System.currentTimeMillis()
        }
    }

    /** Combined signal + range weight. */
    private fun towerWeight(signalDbm: Int, rangeMeters: Int): Double {
        val wSignal = Math.pow(10.0, (signalDbm + 120.0) / 30.0)
        val r = rangeMeters.coerceAtLeast(50).toDouble()
        val wRange = 1.0 / (r * r)
        return wSignal * wRange
    }

    private fun haversineMeters(la1: Double, ln1: Double, la2: Double, ln2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(la1)
        val phi2 = Math.toRadians(la2)
        val dPhi = Math.toRadians(la2 - la1)
        val dLam = Math.toRadians(ln2 - ln1)
        val a = Math.sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.asin(sqrt(a))
    }

    companion object {
        const val PROVIDER_NAME = "cell"
        private const val TAG = "NlpCellSource"
    }
}
