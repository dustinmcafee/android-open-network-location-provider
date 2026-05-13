package com.github.dustinmcafee.nlp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.github.dustinmcafee.nlp.fusion.FusionEngine

/**
 * The system NETWORK_PROVIDER service for non-GMS AOSP builds.
 *
 * Bound by system_server when config_networkLocationProviderPackageName ==
 * com.github.dustinmcafee.nlp (set by vendor/<company>/open-network-location-provider/integration/overlay/...nlp.xml
 * + config_enableNetworkLocationOverlay=false). The framework calls
 * [Provider.onSetRequest] to start/stop fix production; we drive a
 * [FusionEngine] that owns Wi-Fi scanning + WPS queries.
 */
class NetworkLocationService : Service() {

    private lateinit var provider: Provider

    override fun onCreate() {
        super.onCreate()
        provider = Provider(this)
        Log.i(TAG, "NetworkLocationService created")
    }

    override fun onBind(intent: Intent?): IBinder? = provider.binder

    override fun onDestroy() {
        Log.i(TAG, "NetworkLocationService destroyed")
        provider.shutdown()
        super.onDestroy()
    }

    private class Provider(context: Context) : LocationProviderBase(
        context,
        TAG,
        ProviderProperties.Builder()
            .setHasNetworkRequirement(true)
            .setHasCellRequirement(false)
            .setHasSatelliteRequirement(false)
            .setHasMonetaryCost(false)
            .setHasAltitudeSupport(false)
            .setHasSpeedSupport(false)
            .setHasBearingSupport(false)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_COARSE)
            .build()
    ) {
        // Bridge from FusionEngine.Sink → LocationProviderBase.reportLocation.
        // reportLocation is safe to call from any thread.
        private val fusion = FusionEngine(context) { fix ->
            reportLocation(fix)
        }

        override fun onSetRequest(request: ProviderRequest) {
            Log.i(TAG, "onSetRequest: $request")
            if (request.isActive) {
                // ProviderRequest.intervalMillis is the interval the framework
                // wants. We pass it directly; FusionEngine handles re-arming
                // with the new value if it's already running.
                fusion.setRequest(request.intervalMillis)
            } else {
                fusion.stop()
            }
        }

        override fun onFlush(callback: OnFlushCompleteCallback) {
            // We don't buffer fixes — every WPS response is delivered
            // immediately via reportLocation. So flush is a no-op ack.
            callback.onFlushComplete()
        }

        override fun onSendExtraCommand(command: String, extras: Bundle?) {
            Log.d(TAG, "onSendExtraCommand: $command")
        }

        fun shutdown() {
            fusion.shutdown()
        }
    }

    companion object {
        private const val TAG = "OpenNlpService"
    }
}
