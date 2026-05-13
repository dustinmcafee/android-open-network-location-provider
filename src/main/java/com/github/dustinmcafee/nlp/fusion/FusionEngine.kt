package com.github.dustinmcafee.nlp.fusion

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.github.dustinmcafee.nlp.observe.CellObserver
import com.github.dustinmcafee.nlp.observe.GpsLearningObserver
import com.github.dustinmcafee.nlp.observe.WifiObserver
import com.github.dustinmcafee.nlp.source.AppleWpsSource
import com.github.dustinmcafee.nlp.source.AppleWpsSource.WifiObservation
import com.github.dustinmcafee.nlp.source.LearnedCacheSource
import com.github.dustinmcafee.nlp.source.OfflineCellDbSource
import com.github.dustinmcafee.nlp.store.LearnedCacheDb
import com.github.dustinmcafee.nlp.store.OfflineCellDb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-4 fusion engine: learned-cache → online WPS → offline cell DB.
 *
 * Per tick:
 *   1. Wi-Fi scan
 *   2. If we have BSSIDs, try [LearnedCacheSource] (offline, sub-ms)
 *   3. On cache miss, try Apple WPS (online, ~250 ms). Each WPS response
 *      seeds the cache for next time.
 *   4. If WPS path can't deliver a fix (no Wi-Fi, network down, Apple
 *      doesn't know any of our BSSIDs), try [OfflineCellDbSource] using
 *      [CellObserver]'s currently-visible cell towers.
 *
 * Fix priority is ordered by accuracy class: Wi-Fi cache (10–30 m) >
 * Wi-Fi WPS (20–50 m) > cell-tower (200 m–2 km). The fusion engine never
 * blends across classes — it picks the highest-accuracy class that has
 * data and stops there.
 */
internal class FusionEngine(
    context: Context,
    private val sink: Sink,
) {
    fun interface Sink {
        fun deliver(location: Location)
    }

    private val appCtx = context.applicationContext

    private val scanThread = HandlerThread("NlpFusionScan").also { it.start() }
    private val scanHandler = Handler(scanThread.looper)

    private val httpExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "NlpFusionHttp").apply { isDaemon = true }
        }

    private val wifi = WifiObserver(appCtx, scanHandler)
    private val wps = AppleWpsSource()
    private val cell = CellObserver(appCtx)
    private val cacheDb = LearnedCacheDb(appCtx)
    private val cache = LearnedCacheSource(cacheDb)
    private val cellDb = OfflineCellDb.openOrEmpty()
    private val cellSource = OfflineCellDbSource(cellDb)

    private val latestObservations = AtomicReference<List<WifiObservation>?>(null)

    private val gpsLearner =
        GpsLearningObserver(
            context = appCtx,
            handler = scanHandler,
            cache = cacheDb,
            getCurrentObservations = { latestObservations.get() },
        )

    private val running = AtomicBoolean(false)
    private var intervalMs: Long = 0L

    fun setRequest(intervalMillis: Long) {
        if (intervalMillis <= 0L) {
            stop()
            return
        }
        intervalMs = intervalMillis
        if (running.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "starting fusion loop at ${intervalMs}ms interval " +
                    "(cellDb available=${cellDb.isAvailable})",
            )
            gpsLearner.start()
            scanHandler.post(tickRunnable)
        } else {
            Log.i(TAG, "interval updated to ${intervalMs}ms")
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            Log.i(TAG, "stopping fusion loop")
            scanHandler.removeCallbacks(tickRunnable)
            gpsLearner.stop()
        }
    }

    fun shutdown() {
        stop()
        scanThread.quitSafely()
        httpExecutor.shutdown()
        cacheDb.close()
        cellDb.close()
    }

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (!running.get()) return
                wifi.observeOnce { observations ->
                    latestObservations.set(observations)

                    // Path 1: Wi-Fi cache (offline, fastest, most accurate).
                    if (observations.isNotEmpty()) {
                        val cacheFix = cache.query(observations)
                        if (cacheFix != null) {
                            sink.deliver(cacheFix)
                            rescheduleNext()
                            return@observeOnce
                        }
                    }

                    // Path 2: Wi-Fi WPS (online, seeds cache for next time).
                    // Path 3: cell-DB fallback runs after WPS on the same
                    // executor so we don't double up on Wi-Fi-rich queries.
                    httpExecutor.execute {
                        var delivered = false
                        if (observations.isNotEmpty()) {
                            val result = wps.queryWithRaw(observations)
                            val nowEpochSec = System.currentTimeMillis() / 1000L
                            for (b in result.perBssidFixes) {
                                if (b.hasFix) {
                                    cacheDb.observe(
                                        bssid = b.bssid,
                                        latDeg = b.latDeg!!,
                                        lngDeg = b.lngDeg!!,
                                        nowEpochSec = nowEpochSec,
                                    )
                                }
                            }
                            val fix = result.fix
                            if (fix != null) {
                                Log.i(
                                    TAG,
                                    "fix (wps): lat=${fix.latitude} " +
                                        "lng=${fix.longitude} acc=${fix.accuracy}m " +
                                        "(seeded ${result.perBssidFixes.size} cache entries)",
                                )
                                sink.deliver(fix)
                                delivered = true
                            }
                        }
                        // Path 3: cell-DB. Runs only when:
                        //   - no Wi-Fi BSSIDs visible at all, OR
                        //   - WPS couldn't deliver a fix.
                        // The cell DB lookup is offline and cheap, so we always
                        // try it as a last resort rather than gating on whether
                        // we have a SIM (CellObserver returns empty cleanly).
                        if (!delivered) {
                            val cells = cell.observeOnce()
                            if (cells.isNotEmpty()) {
                                val cellFix = cellSource.query(cells)
                                if (cellFix != null) {
                                    Log.i(
                                        TAG,
                                        "fix (cell): lat=${cellFix.latitude} " +
                                            "lng=${cellFix.longitude} acc=${cellFix.accuracy}m",
                                    )
                                    sink.deliver(cellFix)
                                } else {
                                    Log.d(
                                        TAG,
                                        "tick: cell DB had no matches " +
                                            "for ${cells.size} observed cells",
                                    )
                                }
                            } else {
                                Log.d(TAG, "tick: no Wi-Fi fix, no cells observed — no fix this tick")
                            }
                        }
                    }

                    rescheduleNext()
                }
            }

            private fun rescheduleNext() {
                if (running.get()) {
                    scanHandler.postDelayed(this, intervalMs)
                }
            }
        }

    companion object {
        private const val TAG = "NlpFusion"
    }
}
