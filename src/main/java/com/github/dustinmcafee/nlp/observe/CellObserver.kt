package com.github.dustinmcafee.nlp.observe

import android.content.Context
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Reads currently-visible cell towers from [TelephonyManager] and normalizes
 * heterogeneous radio types (GSM / WCDMA / LTE / NR) to a uniform tuple the
 * offline OpenCellID DB can index against.
 *
 * Returns an empty list cleanly on Wi-Fi-only devices (no SIM, no radio,
 * SecurityException) so the fusion engine can skip cell-DB lookup gracefully.
 */
internal class CellObserver(context: Context) {

    enum class Radio { GSM, WCDMA, LTE, NR }

    /**
     * One observed cell tower. [signalDbm] is the most-negative-is-weakest
     * RSRP/RSSI/RSCP depending on radio type; we keep its sign so the source
     * can weight by signal strength like Wi-Fi does.
     */
    data class CellObservation(
        val radio: Radio,
        val mcc: Int,
        val mnc: Int,
        val area: Int,    // LAC (GSM/WCDMA) or TAC (LTE/NR)
        val cell: Long,   // CID (GSM/WCDMA), CI (LTE), NCI (NR — 36-bit)
        val signalDbm: Int,
    )

    private val tm: TelephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /**
     * Snapshot the currently visible cells. Returns an empty list on:
     *   - Wi-Fi-only devices (TelephonyManager returns null/empty)
     *   - Missing READ_PHONE_STATE / FINE_LOCATION (SecurityException, logged)
     *   - getAllCellInfo returning null (rare, transient hardware state)
     *
     * The returned list deduplicates on the (radio, mcc, mnc, area, cell)
     * tuple — the system sometimes reports the serving cell twice as both
     * registered and neighbor.
     */
    fun observeOnce(): List<CellObservation> {
        val raw: List<CellInfo> = try {
            @Suppress("DEPRECATION")
            tm.allCellInfo ?: return emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "getAllCellInfo: ${e.message}")
            return emptyList()
        }

        val seen = HashSet<Long>(raw.size)
        val out = ArrayList<CellObservation>(raw.size)
        for (ci in raw) {
            val obs = ciToObservation(ci) ?: continue
            // Pack tuple into a long for cheap dedup: hash mcc<<48 | mnc<<32 | area<<16 | cell.
            // Collisions are possible across radio types; OK to keep both.
            val key = (obs.radio.ordinal.toLong() shl 60) or
                (obs.mcc.toLong() and 0xFFF shl 48) or
                (obs.mnc.toLong() and 0xFFF shl 36) or
                (obs.area.toLong() and 0xFFFF shl 20) or
                (obs.cell and 0xFFFFFL)
            if (seen.add(key)) out.add(obs)
        }
        Log.i(TAG, "observed ${out.size} cells (${raw.size} raw)")
        return out
    }

    private fun ciToObservation(ci: CellInfo): CellObservation? {
        return when (ci) {
            is CellInfoLte -> {
                val id = ci.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc
                val tac = id.tac
                val ci_ = id.ci
                if (mcc == Int.MAX_VALUE || mnc == Int.MAX_VALUE ||
                    tac == Int.MAX_VALUE || ci_ == Int.MAX_VALUE) null
                else CellObservation(
                    radio = Radio.LTE, mcc = mcc, mnc = mnc,
                    area = tac, cell = ci_.toLong(),
                    signalDbm = ci.cellSignalStrength.dbm,
                )
            }
            is CellInfoGsm -> {
                val id = ci.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc
                val lac = id.lac
                val cid = id.cid
                if (mcc == Int.MAX_VALUE || mnc == Int.MAX_VALUE ||
                    lac == Int.MAX_VALUE || cid == Int.MAX_VALUE) null
                else CellObservation(
                    radio = Radio.GSM, mcc = mcc, mnc = mnc,
                    area = lac, cell = cid.toLong(),
                    signalDbm = ci.cellSignalStrength.dbm,
                )
            }
            is CellInfoWcdma -> {
                val id = ci.cellIdentity
                val mcc = id.mccString?.toIntOrNull() ?: id.mcc
                val mnc = id.mncString?.toIntOrNull() ?: id.mnc
                val lac = id.lac
                val cid = id.cid
                if (mcc == Int.MAX_VALUE || mnc == Int.MAX_VALUE ||
                    lac == Int.MAX_VALUE || cid == Int.MAX_VALUE) null
                else CellObservation(
                    radio = Radio.WCDMA, mcc = mcc, mnc = mnc,
                    area = lac, cell = cid.toLong(),
                    signalDbm = ci.cellSignalStrength.dbm,
                )
            }
            is CellInfoNr -> {
                val id = ci.cellIdentity as? CellIdentityNr ?: return null
                val mcc = id.mccString?.toIntOrNull() ?: return null
                val mnc = id.mncString?.toIntOrNull() ?: return null
                val tac = id.tac
                val nci = id.nci
                if (tac == Int.MAX_VALUE || nci == Long.MAX_VALUE) null
                else CellObservation(
                    radio = Radio.NR, mcc = mcc, mnc = mnc,
                    area = tac, cell = nci,
                    signalDbm = ci.cellSignalStrength.dbm,
                )
            }
            else -> null   // CDMA — not in OpenCellID's coverage shape
        }
    }

    companion object {
        private const val TAG = "NlpCellObserver"
    }
}
