package com.github.dustinmcafee.nlp.store

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.github.dustinmcafee.nlp.observe.CellObserver
import java.io.File

/**
 * Read-only cell-tower lookup against the OpenCellID-derived SQLite shipped
 * to /data/misc/nlp-celldb/cells.db by [integration/init/nlp-celldb-fetch.sh]
 * on first boot.
 *
 * The DB is built off-device by celldb-pipeline/opencellid-to-sqlite.py with
 * a single primary-key index on (radio, mcc, net, area, cell). Lookups are
 * compound-key B-tree probes — sub-millisecond per cell.
 *
 * Schema (matches what the build-server pipeline produces):
 *   CREATE TABLE cells (
 *     radio TEXT NOT NULL,           -- "GSM"/"WCDMA"/"LTE"/"NR"
 *     mcc INTEGER NOT NULL,          -- 3-digit country
 *     net INTEGER NOT NULL,          -- mnc
 *     area INTEGER NOT NULL,         -- LAC (GSM/WCDMA) / TAC (LTE/NR)
 *     cell INTEGER NOT NULL,         -- CID/CI/NCI (signed-64 fits NR's 36-bit nci)
 *     lat_e8 INTEGER NOT NULL,       -- degrees × 1e8
 *     lng_e8 INTEGER NOT NULL,
 *     range_m INTEGER NOT NULL,      -- estimated coverage radius
 *     PRIMARY KEY (radio, mcc, net, area, cell)
 *   );
 *
 * If the file doesn't exist (cold first-boot, before fetcher runs, or the
 * fetcher hasn't been wired up to a real CDN URL yet), this class returns
 * an "always-empty" instance — every lookup yields null, the source bails
 * out gracefully, fusion falls through to whatever else is available.
 */
internal class OfflineCellDb private constructor(
    private val db: SQLiteDatabase?,
) {
    /** A cell tower's database position. */
    data class Tower(
        val latE8: Long,
        val lngE8: Long,
        val rangeMeters: Int,
    ) {
        val latDeg: Double get() = latE8.toDouble() / 1e8
        val lngDeg: Double get() = lngE8.toDouble() / 1e8
    }

    /** True if the underlying DB file exists and opened cleanly. */
    val isAvailable: Boolean get() = db != null

    fun lookup(obs: CellObserver.CellObservation): Tower? {
        val d = db ?: return null
        return try {
            d
                .query(
                    TABLE,
                    COLUMNS,
                    "radio = ? AND mcc = ? AND net = ? AND area = ? AND cell = ?",
                    arrayOf(
                        obs.radio.name,
                        obs.mcc.toString(),
                        obs.mnc.toString(),
                        obs.area.toString(),
                        obs.cell.toString(),
                    ),
                    null,
                    null,
                    null,
                    "1",
                ).use { c ->
                    if (!c.moveToFirst()) return null
                    Tower(
                        latE8 = c.getLong(0),
                        lngE8 = c.getLong(1),
                        rangeMeters = c.getInt(2),
                    )
                }
        } catch (e: SQLException) {
            Log.w(TAG, "cell lookup failed: ${e.message}")
            null
        }
    }

    fun close() {
        db?.close()
    }

    companion object {
        private const val TAG = "NlpOfflineCellDb"
        private const val DB_PATH = "/data/misc/nlp-celldb/cells.db"
        private const val TABLE = "cells"
        private val COLUMNS = arrayOf("lat_e8", "lng_e8", "range_m")

        /**
         * Open the shipped cell DB read-only. Returns an "empty" instance
         * (all lookups null, [isAvailable] false) if the file doesn't exist
         * or fails to open — never throws.
         */
        fun openOrEmpty(): OfflineCellDb {
            val f = File(DB_PATH)
            if (!f.exists()) {
                Log.i(TAG, "$DB_PATH not present yet — running in fall-through mode")
                return OfflineCellDb(null)
            }
            return try {
                val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                val db = SQLiteDatabase.openDatabase(DB_PATH, null, flags)
                Log.i(TAG, "opened $DB_PATH (${f.length()} bytes)")
                OfflineCellDb(db)
            } catch (e: Exception) {
                Log.e(TAG, "failed to open $DB_PATH: ${e.message}")
                OfflineCellDb(null)
            }
        }
    }
}
