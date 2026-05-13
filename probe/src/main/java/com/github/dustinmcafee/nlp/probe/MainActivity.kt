package com.github.dustinmcafee.nlp.probe

import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Engineering-only test harness. Launches, requests NETWORK_PROVIDER updates
 * at 1-second intervals for 30 seconds, logs every fix to tag
 * `NlpProbe`, then auto-finishes.
 *
 * Use:
 *   adb logcat -c
 *   adb shell am start -n com.github.dustinmcafee.nlp.probe/.MainActivity
 *   adb logcat -s NlpProbe:V NlpFusion:V AppleWpsSource:V \
 *                 NlpWifiObserver:V OpenNlpService:V
 */
class MainActivity :
    Activity(),
    LocationListener {
    private val tv: TextView by lazy { TextView(this).apply { textSize = 14f } }
    private lateinit var lm: LocationManager
    private val startedElapsed = SystemClock.elapsedRealtime()
    private var fixCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(tv)
            },
        )
        log("=== NlpProbe started ===")
        log("Listing providers:")
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in lm.getProviders(false)) {
            log("  $p (enabled=${lm.isProviderEnabled(p)})")
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                // minTimeMs =
                1_000L,
                // minDistanceM =
                0f,
                this,
            )
            log("requestLocationUpdates(NETWORK_PROVIDER) registered, waiting...")
        } catch (e: SecurityException) {
            log("SecurityException: ${e.message}")
        } catch (e: IllegalArgumentException) {
            log("IllegalArgumentException: ${e.message}")
        }

        // Auto-finish after 30s.
        tv.postDelayed({
            log("=== 30s timeout — finishing ($fixCount fixes received) ===")
            try {
                lm.removeUpdates(this)
            } catch (_: Throwable) {
            }
            finish()
        }, 30_000L)
    }

    override fun onLocationChanged(location: Location) {
        fixCount++
        val dt = SystemClock.elapsedRealtime() - startedElapsed
        log(
            "fix #$fixCount @ +${dt}ms: " +
                "lat=${"%.6f".format(location.latitude)} " +
                "lng=${"%.6f".format(location.longitude)} " +
                "acc=${"%.1f".format(location.accuracy)}m " +
                "provider=${location.provider}",
        )
    }

    override fun onProviderEnabled(provider: String) {
        log("provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        log("provider disabled: $provider")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { tv.append("$msg\n") }
    }

    companion object {
        private const val TAG = "NlpProbe"
    }
}
