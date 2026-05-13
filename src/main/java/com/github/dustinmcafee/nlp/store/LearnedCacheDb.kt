package com.github.dustinmcafee.nlp.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Local persistent cache of (BSSID → learned position) observations,
 * derived passively from GPS fixes seen by [GpsLearningObserver].
 *
 * Storage shape: a single SQLite table with one row per BSSID. Position is
 * stored as int64 with degrees × 1e8 (matches Apple WPS convention; avoids
 * REAL precision loss on small lat/lng deltas during averaging).
 *
 * Update math: Welford's online algorithm for the mean — O(1) memory per
 * BSSID, numerically stable. The accuracy column carries an empirical
 * dispersion estimate (running mean of distances from current centroid)
 * which lets [LearnedCacheSource] gauge confidence.
 *
 * Eviction: when row count exceeds [MAX_ROWS], delete the oldest [EVICT_BATCH]
 * by last_updated. Cheap; runs at most every [EVICT_CHECK_INTERVAL_MS].
 */
internal class LearnedCacheDb(
    context: Context,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    /** Result of a cache lookup. */
    data class Entry(
        val bssid: String,
        val latE8: Long,
        val lngE8: Long,
        /** Empirical dispersion (metres) — running mean of distance-from-centroid. */
        val dispersionMeters: Float,
        val sampleCount: Int,
        val lastUpdatedEpochSec: Long,
    ) {
        val latDeg: Double get() = latE8.toDouble() / 1e8
        val lngDeg: Double get() = lngE8.toDouble() / 1e8
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_BSSID TEXT PRIMARY KEY,
                $COL_LAT_E8 INTEGER NOT NULL,
                $COL_LNG_E8 INTEGER NOT NULL,
                $COL_DISPERSION REAL NOT NULL,
                $COL_SAMPLE_COUNT INTEGER NOT NULL,
                $COL_LAST_UPDATED INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_last_updated " +
                "ON $TABLE ($COL_LAST_UPDATED)",
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Phase 3 schema-v1 only; future versions handle migrations here.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Look up a single BSSID. Returns null if not learned yet.
     * Threadsafe by virtue of SQLite's connection pool.
     */
    fun get(bssid: String): Entry? {
        readableDatabase
            .query(
                TABLE,
                COLUMNS,
                "$COL_BSSID = ?",
                arrayOf(bssid),
                null,
                null,
                null,
                "1",
            ).use { c ->
                if (!c.moveToFirst()) return null
                return Entry(
                    bssid = c.getString(0),
                    latE8 = c.getLong(1),
                    lngE8 = c.getLong(2),
                    dispersionMeters = c.getFloat(3),
                    sampleCount = c.getInt(4),
                    lastUpdatedEpochSec = c.getLong(5),
                )
            }
    }

    /**
     * Bulk-fetch entries for a list of BSSIDs. Returns a map of only the
     * BSSIDs that exist in the cache.
     */
    fun getAll(bssids: Collection<String>): Map<String, Entry> {
        if (bssids.isEmpty()) return emptyMap()
        val placeholders = bssids.joinToString(",") { "?" }
        val out = HashMap<String, Entry>(bssids.size)
        readableDatabase
            .query(
                TABLE,
                COLUMNS,
                "$COL_BSSID IN ($placeholders)",
                bssids.toTypedArray(),
                null,
                null,
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val e =
                        Entry(
                            bssid = c.getString(0),
                            latE8 = c.getLong(1),
                            lngE8 = c.getLong(2),
                            dispersionMeters = c.getFloat(3),
                            sampleCount = c.getInt(4),
                            lastUpdatedEpochSec = c.getLong(5),
                        )
                    out[e.bssid] = e
                }
            }
        return out
    }

    /**
     * Update one BSSID's centroid + dispersion with a new GPS-anchored sample.
     * Welford's algorithm: each call is O(1), no need to retain prior samples.
     *
     *   newMean = oldMean + (sample - oldMean) / newCount
     *   newDispersion (running mean of |sample - newMean|):
     *      newDisp = oldDisp + (|sample - newMean| - oldDisp) / newCount
     *
     * The dispersion update uses the new mean (not old) so the first sample
     * gets dispersion=0, the second gets the actual distance between samples,
     * etc. This is an empirical "mean absolute deviation around the centroid"
     * not a strict Welford-style variance, but it's monotone and converges.
     */
    fun observe(
        bssid: String,
        latDeg: Double,
        lngDeg: Double,
        nowEpochSec: Long,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = get(bssid)
            val sampleLatE8 = (latDeg * 1e8).toLong()
            val sampleLngE8 = (lngDeg * 1e8).toLong()
            val cv = ContentValues()
            cv.put(COL_BSSID, bssid)
            cv.put(COL_LAST_UPDATED, nowEpochSec)
            if (existing == null) {
                cv.put(COL_LAT_E8, sampleLatE8)
                cv.put(COL_LNG_E8, sampleLngE8)
                cv.put(COL_DISPERSION, 0f)
                cv.put(COL_SAMPLE_COUNT, 1)
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            } else {
                val n = existing.sampleCount + 1
                val newLatE8 = existing.latE8 + (sampleLatE8 - existing.latE8) / n
                val newLngE8 = existing.lngE8 + (sampleLngE8 - existing.lngE8) / n
                val distMeters =
                    haversineMeters(
                        newLatE8.toDouble() / 1e8,
                        newLngE8.toDouble() / 1e8,
                        latDeg,
                        lngDeg,
                    ).toFloat()
                val newDisp =
                    existing.dispersionMeters +
                        (distMeters - existing.dispersionMeters) / n
                cv.put(COL_LAT_E8, newLatE8)
                cv.put(COL_LNG_E8, newLngE8)
                cv.put(COL_DISPERSION, newDisp)
                cv.put(COL_SAMPLE_COUNT, n)
                db.update(TABLE, cv, "$COL_BSSID = ?", arrayOf(bssid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        maybeEvict(nowEpochSec)
    }

    private var lastEvictCheckEpochSec: Long = 0L

    private fun maybeEvict(nowEpochSec: Long) {
        if (nowEpochSec - lastEvictCheckEpochSec < EVICT_CHECK_INTERVAL_S) return
        lastEvictCheckEpochSec = nowEpochSec
        val db = writableDatabase
        val count = db.compileStatement("SELECT COUNT(*) FROM $TABLE").simpleQueryForLong()
        if (count <= MAX_ROWS) return
        val deleted =
            db.delete(
                TABLE,
                "$COL_BSSID IN (SELECT $COL_BSSID FROM $TABLE " +
                    "ORDER BY $COL_LAST_UPDATED ASC LIMIT $EVICT_BATCH)",
                null,
            )
        Log.i(TAG, "evicted $deleted oldest entries (had $count, max $MAX_ROWS)")
    }

    /** Diagnostics for `dumpsys` / debug commands. Cheap. */
    fun stats(): String {
        readableDatabase
            .rawQuery(
                "SELECT COUNT(*), AVG($COL_SAMPLE_COUNT), MAX($COL_LAST_UPDATED) FROM $TABLE",
                null,
            ).use { c ->
                if (!c.moveToFirst()) return "empty"
                val count = c.getLong(0)
                val avgSamples = c.getFloat(1)
                val newest = c.getLong(2)
                return "rows=$count avgSamples=${"%.1f".format(avgSamples)} newestEpoch=$newest"
            }
    }

    private fun haversineMeters(
        la1: Double,
        ln1: Double,
        la2: Double,
        ln2: Double,
    ): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(la1)
        val phi2 = Math.toRadians(la2)
        val dPhi = Math.toRadians(la2 - la1)
        val dLam = Math.toRadians(ln2 - ln1)
        val a =
            Math.sin(dPhi / 2).let { it * it } +
                Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    companion object {
        private const val TAG = "NlpLearnedCacheDb"
        private const val DB_NAME = "nlp_learned_cache.db"
        private const val DB_VERSION = 1

        private const val TABLE = "learned_bssid"
        private const val COL_BSSID = "bssid"
        private const val COL_LAT_E8 = "lat_e8"
        private const val COL_LNG_E8 = "lng_e8"
        private const val COL_DISPERSION = "dispersion_m"
        private const val COL_SAMPLE_COUNT = "sample_count"
        private const val COL_LAST_UPDATED = "last_updated_epoch_s"

        private val COLUMNS =
            arrayOf(
                COL_BSSID,
                COL_LAT_E8,
                COL_LNG_E8,
                COL_DISPERSION,
                COL_SAMPLE_COUNT,
                COL_LAST_UPDATED,
            )

        // Eviction tuning: cap at 10k entries; when exceeded, drop oldest 1000.
        // Check at most every 5 minutes (don't COUNT on every observe).
        private const val MAX_ROWS = 10_000L
        private const val EVICT_BATCH = 1_000
        private const val EVICT_CHECK_INTERVAL_S = 300L
    }
}
