package com.github.dustinmcafee.nlp.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Apple's "ARPC" envelope wraps the AppleWLoc protobuf payload that gets
 * POSTed to gs-loc.apple.com/clls/wloc.
 *
 * Wire format (verified against acheong08/apple-corelocation-experiments
 * lib/arpc.go on 2026-04-28):
 *
 *   [u16 BE]       version             (= 1)
 *   [u16 BE + s]   locale              (pascal string, big-endian length)
 *   [u16 BE + s]   appIdentifier       (pascal string)
 *   [u16 BE + s]   osVersion           (pascal string)
 *   [u32 BE]       functionId          (= 1 for /clls/wloc)
 *   [u32 BE]       payloadLength
 *   [n bytes]      payload             (AppleWLoc protobuf)
 *
 * The response uses a much simpler framing — a fixed 10-byte header followed
 * by the AppleWLoc protobuf — so [parseResponseEnvelope] just slices off the
 * leading 10 bytes.
 */
internal object ArpcEnvelope {
    // Identity values mirror what Apple's locationd sends. Avoid changing
    // OS_VERSION wantonly — Apple may screen on apparent client version.
    // If WPS responses degrade in the future, refresh these from a current
    // capture of the locationd traffic.
    private const val VERSION: Int = 1
    private const val LOCALE: String = "en-001_001"
    private const val APP_IDENTIFIER: String = "com.apple.locationd"
    private const val OS_VERSION: String = "18.6.2.22G100"
    private const val FUNCTION_ID_WLOC: Int = 1

    private const val RESPONSE_HEADER_BYTES = 10

    /** Wrap a protobuf payload in the ARPC envelope ready for POST. */
    fun wrap(protobufPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUint16BE(out, VERSION)
        writePascalString(out, LOCALE)
        writePascalString(out, APP_IDENTIFIER)
        writePascalString(out, OS_VERSION)
        writeUint32BE(out, FUNCTION_ID_WLOC)
        writeUint32BE(out, protobufPayload.size)
        out.write(protobufPayload)
        return out.toByteArray()
    }

    /**
     * Strip the response-side envelope. The first 10 bytes are a fixed
     * header (status / counters); everything after is the AppleWLoc protobuf.
     * Returns the protobuf bytes alone, or throws if the body is too short.
     */
    fun unwrap(responseBody: ByteArray): ByteArray {
        require(responseBody.size > RESPONSE_HEADER_BYTES) {
            "response body too short: ${responseBody.size} bytes"
        }
        return responseBody.copyOfRange(RESPONSE_HEADER_BYTES, responseBody.size)
    }

    private fun writeUint16BE(
        out: ByteArrayOutputStream,
        v: Int,
    ) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeUint32BE(
        out: ByteArrayOutputStream,
        v: Int,
    ) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v)
        out.write(buf.array())
    }

    private fun writePascalString(
        out: ByteArrayOutputStream,
        s: String,
    ) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "pascal string too long: ${bytes.size}" }
        writeUint16BE(out, bytes.size)
        out.write(bytes)
    }
}
