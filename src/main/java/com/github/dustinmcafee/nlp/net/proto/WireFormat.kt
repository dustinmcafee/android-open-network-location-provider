package com.github.dustinmcafee.nlp.net.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Minimal protobuf wire-format primitives — just enough to encode/decode the
 * AppleWLoc message used by Apple WPS. We avoid pulling in protobuf-javalite
 * because (a) Soong build glue for .proto compilation adds friction, and
 * (b) the message shape we care about is tiny: a few varint and
 * length-delimited fields.
 *
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 */
internal object WireFormat {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    /** Encode a (fieldNumber, wireType) tag as a varint. */
    fun tag(fieldNumber: Int, wireType: Int): Int = (fieldNumber shl 3) or wireType

    /** Write a varint (LEB128) to [out]. Handles signed by reinterpreting as ulong. */
    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        // Sign-extend negative ints to long so they encode as 10 bytes (matches proto3).
        writeVarint(out, value.toLong())
    }

    /** Write a tag byte sequence. */
    fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, tag(fieldNumber, wireType))
    }

    /** Encode a length-delimited field: tag + length + bytes. */
    fun writeLengthDelimited(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        bytes: ByteArray,
    ) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    /** Encode a varint field: tag + varint value. */
    fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeVarint(out, value)
    }

    /**
     * Encode a sint32 field. proto3 sint32/sint64 use ZigZag encoding so that
     * small negatives produce small varints.
     */
    fun writeSint32Field(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeVarint(out, ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)
    }

    /** Read a varint as long. EOFException on truncation. */
    fun readVarint(`in`: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = `in`.read()
            if (b == -1) throw EOFException("varint truncated")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) throw IllegalStateException("varint too long")
        }
    }

    /** Read exactly [n] bytes; EOFException if stream ends early. */
    fun readBytes(`in`: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = `in`.read(out, read, n - read)
            if (r < 0) throw EOFException("expected $n bytes, got $read")
            read += r
        }
        return out
    }

    /** Skip an unknown field's value given its wireType. */
    fun skipField(`in`: InputStream, wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint(`in`)
            WIRE_FIXED64 -> readBytes(`in`, 8)
            WIRE_LENGTH_DELIMITED -> {
                val len = readVarint(`in`).toInt()
                readBytes(`in`, len)
            }
            WIRE_FIXED32 -> readBytes(`in`, 4)
            else -> throw IllegalStateException("unsupported wire type $wireType")
        }
    }

    /** Decompose a tag varint into (fieldNumber, wireType). */
    fun parseTag(tag: Long): Pair<Int, Int> = ((tag ushr 3).toInt()) to ((tag and 0x7).toInt())
}
