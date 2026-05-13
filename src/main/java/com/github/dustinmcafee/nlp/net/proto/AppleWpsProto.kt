package com.github.dustinmcafee.nlp.net.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Hand-rolled codec for Apple's AppleWLoc protobuf, used by the WPS endpoint
 * at https://gs-loc.apple.com/clls/wloc.
 *
 * Schema reference (verified against acheong08/apple-corelocation-experiments
 * on 2026-04-28):
 *
 *   message AppleWLoc {
 *     repeated WifiDevice wifi_devices    = 2;
 *     optional sint32     num_wifi_results = 4;
 *     // (other fields ignored — cell_tower_*, app_bundle_id, device_type, ...)
 *   }
 *   message WifiDevice {
 *     string             bssid    = 1;
 *     optional Location  location = 2;
 *   }
 *   message Location {
 *     optional int64 latitude            = 1;   // degrees * 1e8
 *     optional int64 longitude           = 2;   // degrees * 1e8
 *     optional int64 horizontal_accuracy = 3;   // metres
 *     // (other fields ignored)
 *   }
 *
 * BSSID format expected: "AA:BB:CC:DD:EE:FF" — uppercase hex pairs separated
 * by colons. Lat/lng as double = int64 / 1e8.
 */
internal object AppleWpsProto {

    private const val WIRE_LATLON = 1.0e8

    // AppleWLoc field numbers
    private const val ALOC_WIFI_DEVICES = 2
    private const val ALOC_NUM_WIFI_RESULTS = 4

    // WifiDevice field numbers
    private const val WD_BSSID = 1
    private const val WD_LOCATION = 2

    // Location field numbers
    private const val LOC_LAT = 1
    private const val LOC_LNG = 2
    private const val LOC_HACCURACY = 3

    /** A BSSID we want positioned. */
    data class WifiQuery(val bssid: String)

    /** A BSSID with its server-returned position (or null if Apple didn't know it). */
    data class WifiResult(
        val bssid: String,
        val latDeg: Double?,
        val lngDeg: Double?,
        val accuracyMeters: Float?,
    ) {
        val hasFix: Boolean get() = latDeg != null && lngDeg != null
    }

    /**
     * Encode an AppleWLoc payload requesting positions for [wifis]. Apple
     * returns positions for any BSSID it knows, plus often a handful of
     * "neighbor" BSSIDs (controlled by [numWifiResults] >= 0).
     */
    fun encodeRequest(wifis: List<WifiQuery>, numWifiResults: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        for (w in wifis) {
            val wd = encodeWifiDevice(w.bssid)
            WireFormat.writeLengthDelimited(out, ALOC_WIFI_DEVICES, wd)
        }
        if (numWifiResults != 0) {
            WireFormat.writeSint32Field(out, ALOC_NUM_WIFI_RESULTS, numWifiResults)
        }
        return out.toByteArray()
    }

    private fun encodeWifiDevice(bssid: String): ByteArray {
        val out = ByteArrayOutputStream()
        WireFormat.writeLengthDelimited(out, WD_BSSID, bssid.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * Parse an AppleWLoc response payload. Returns one [WifiResult] per
     * WifiDevice in the response; [WifiResult.hasFix] is false for entries
     * Apple returned with no Location sub-message.
     */
    fun parseResponse(payload: ByteArray): List<WifiResult> {
        val results = mutableListOf<WifiResult>()
        val `in` = ByteArrayInputStream(payload)
        while (`in`.available() > 0) {
            val tag = WireFormat.readVarint(`in`)
            val (field, wireType) = WireFormat.parseTag(tag)
            when {
                field == ALOC_WIFI_DEVICES && wireType == WireFormat.WIRE_LENGTH_DELIMITED -> {
                    val len = WireFormat.readVarint(`in`).toInt()
                    val wdBytes = WireFormat.readBytes(`in`, len)
                    results.add(parseWifiDevice(wdBytes))
                }
                else -> WireFormat.skipField(`in`, wireType)
            }
        }
        return results
    }

    private fun parseWifiDevice(payload: ByteArray): WifiResult {
        var bssid = ""
        var locBytes: ByteArray? = null
        val `in` = ByteArrayInputStream(payload)
        while (`in`.available() > 0) {
            val (field, wireType) = WireFormat.parseTag(WireFormat.readVarint(`in`))
            when {
                field == WD_BSSID && wireType == WireFormat.WIRE_LENGTH_DELIMITED -> {
                    val len = WireFormat.readVarint(`in`).toInt()
                    bssid = String(WireFormat.readBytes(`in`, len), Charsets.UTF_8)
                }
                field == WD_LOCATION && wireType == WireFormat.WIRE_LENGTH_DELIMITED -> {
                    val len = WireFormat.readVarint(`in`).toInt()
                    locBytes = WireFormat.readBytes(`in`, len)
                }
                else -> WireFormat.skipField(`in`, wireType)
            }
        }
        if (locBytes == null) return WifiResult(bssid, null, null, null)
        return parseLocation(locBytes).copy(bssid = bssid)
    }

    /**
     * Parses Location into (lat°, lng°, accuracy m). Apple returns lat/lng as
     * int64 in fixed-point (degrees × 1e8). Apple uses sentinel int64 values
     * (very large negative numbers like -180e8) to mean "no fix" — we treat
     * any value outside the valid degree range as null.
     */
    private fun parseLocation(payload: ByteArray): WifiResult {
        var lat: Long? = null
        var lng: Long? = null
        var hAcc: Long? = null
        val `in` = ByteArrayInputStream(payload)
        while (`in`.available() > 0) {
            val (field, wireType) = WireFormat.parseTag(WireFormat.readVarint(`in`))
            when {
                field == LOC_LAT && wireType == WireFormat.WIRE_VARINT ->
                    lat = WireFormat.readVarint(`in`)
                field == LOC_LNG && wireType == WireFormat.WIRE_VARINT ->
                    lng = WireFormat.readVarint(`in`)
                field == LOC_HACCURACY && wireType == WireFormat.WIRE_VARINT ->
                    hAcc = WireFormat.readVarint(`in`)
                else -> WireFormat.skipField(`in`, wireType)
            }
        }
        val latDeg = lat?.let { it.toDouble() / WIRE_LATLON }?.takeIf { it in -90.0..90.0 }
        val lngDeg = lng?.let { it.toDouble() / WIRE_LATLON }?.takeIf { it in -180.0..180.0 }
        val acc = hAcc?.let { it.toFloat() }?.takeIf { it in 0f..50_000f }
        return WifiResult(bssid = "", latDeg = latDeg, lngDeg = lngDeg, accuracyMeters = acc)
    }
}
