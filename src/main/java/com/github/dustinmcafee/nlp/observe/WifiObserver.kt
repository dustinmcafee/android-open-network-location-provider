package com.github.dustinmcafee.nlp.observe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.github.dustinmcafee.nlp.source.AppleWpsSource.WifiObservation

/**
 * Triggers a Wi-Fi scan and delivers its results, respecting Android 9+
 * scan throttling.
 *
 * Android 9 introduced background-scan throttling: at most 4 [startScan]
 * calls per app per 2-minute window. Hitting that limit makes [startScan]
 * silently return false; the system delivers the *cached* scan results
 * instead, which may be hundreds of seconds stale. We track our own
 * scan-start timestamps and skip explicit re-scans when within the
 * throttling window — falling back to the system-cached results.
 */
internal class WifiObserver(
    private val context: Context,
    private val handler: Handler,
) {
    /** Callback for a completed scan. May be invoked on the handler thread. */
    fun interface Listener {
        fun onObservations(observations: List<WifiObservation>)
    }

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Timestamps of recent successful startScan() calls. */
    private val scanStartTimes = ArrayDeque<Long>(MAX_SCANS_PER_WINDOW)

    private var receiver: BroadcastReceiver? = null

    /**
     * Trigger a Wi-Fi scan (or use cached results if we're at the throttle
     * limit) and call [listener] when results arrive. Idempotent; calling
     * twice in quick succession won't register a second receiver.
     */
    fun observeOnce(listener: Listener) {
        if (receiver != null) {
            Log.d(TAG, "observeOnce: scan already in flight, ignoring")
            return
        }

        val canScan = mayTriggerScan()
        if (canScan) {
            registerReceiver(listener)
            val started = wifiManager.startScan()
            if (!started) {
                Log.w(TAG, "startScan() returned false — delivering cached results immediately")
                deliverCached(listener)
                unregisterReceiver()
                return
            }
            scanStartTimes.addLast(SystemClock.elapsedRealtime())
            // Belt-and-suspenders timeout: if SCAN_RESULTS_AVAILABLE_ACTION
            // never fires, deliver cached results after 8s.
            handler.postDelayed({
                if (receiver != null) {
                    Log.w(TAG, "scan timed out — delivering cached results")
                    deliverCached(listener)
                    unregisterReceiver()
                }
            }, SCAN_TIMEOUT_MS)
        } else {
            Log.i(
                TAG,
                "scan throttled (${scanStartTimes.size}/$MAX_SCANS_PER_WINDOW " +
                    "in last ${SCAN_THROTTLE_WINDOW_MS / 1000}s); using cached results",
            )
            deliverCached(listener)
        }
    }

    private fun mayTriggerScan(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (scanStartTimes.isNotEmpty() &&
            now - scanStartTimes.first() > SCAN_THROTTLE_WINDOW_MS
        ) {
            scanStartTimes.removeFirst()
        }
        return scanStartTimes.size < MAX_SCANS_PER_WINDOW
    }

    private fun registerReceiver(listener: Listener) {
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    val updated =
                        intent.getBooleanExtra(
                            WifiManager.EXTRA_RESULTS_UPDATED,
                            true,
                        )
                    Log.d(TAG, "SCAN_RESULTS_AVAILABLE updated=$updated")
                    deliverCached(listener)
                    unregisterReceiver()
                }
            }
        receiver = r
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered — fine.
            }
        }
        receiver = null
    }

    private fun deliverCached(listener: Listener) {
        val raw: List<ScanResult> =
            try {
                wifiManager.scanResults ?: emptyList()
            } catch (e: SecurityException) {
                Log.e(TAG, "scanResults: ${e.message}")
                emptyList()
            }
        // No "locally-administered MAC" filter: scan results never contain
        // device-randomized MACs (those only show up as the device's own
        // address, never in other APs' broadcasts). Enterprise APs commonly
        // emit virtual SSID BSSIDs with the 0x02 bit set — those are stable,
        // continuously broadcast, and indexed in upstream WPS databases just
        // like any other AP. Filtering them out cost us ~95% of usable
        // signal during testing.
        val obs =
            raw.mapNotNull { sr ->
                val bssid = sr.BSSID ?: return@mapNotNull null
                WifiObservation(bssid = bssid, rssi = sr.level)
            }
        Log.i(TAG, "delivering ${obs.size} BSSIDs (${raw.size} raw scan results)")
        handler.post { listener.onObservations(obs) }
    }

    companion object {
        private const val TAG = "NlpWifiObserver"
        private const val MAX_SCANS_PER_WINDOW = 4
        private const val SCAN_THROTTLE_WINDOW_MS = 2L * 60 * 1000 // 2 min
        private const val SCAN_TIMEOUT_MS = 8_000L
    }
}
