# Architecture

## Overview

`open-network-location-provider` is a single privileged Android service that
registers itself as the system `NETWORK_PROVIDER` on AOSP devices that lack
Google Play Services. It does not require microG or any other GMS replacement.

```
                   AOSP LocationManagerService
                   (NETWORK_PROVIDER lookup)
                              │
                              ▼  Binder (v3 NLP contract)
                   NetworkLocationService
                              │
                       FusionEngine
          __________________|___________________
         /           |              |            \
   WifiObserver  CellObserver  GpsLearningObserver  (future)
         \           |              │
          \_________|______________|
                    │
          parallel-query, accuracy-ranked
                    │
       _____________|_______________
      /             |               \
  LearnedCacheSource  OfflineCellDbSource  AppleWpsSource
  (SQLite, /data,   (SQLite, /data,        (HTTPS, online)
   <1 ms, offline)   <1 ms, offline)       ~250 ms)
```

GPS is unaffected — the Qualcomm/MediaTek GNSS HAL continues to serve
`GPS_PROVIDER`. AOSP's stock `FusedLocationProvider` automatically merges
GPS + NETWORK fixes once this service is registered.

---

## Fix sources (priority order)

### 1. Learned cache (`LearnedCacheSource`, `LearnedCacheDb`)

**What:** Local SQLite DB mapping BSSID/cell-ID → observed GPS coordinates.
Built passively from GPS fixes via `GpsLearningObserver`. Once a device has
been at a site with GPS visibility, subsequent fixes there hit the cache
sub-millisecond with no network round-trip.

**Accuracy:** depends on number of samples; converges to ~5–20 m after a
few dozen GPS-anchored observations. Better than WPS for known sites.

**Offline:** always.

### 2. Wi-Fi WPS (`AppleWpsSource`)

**What:** Posts observed BSSIDs to a Wi-Fi Positioning System endpoint.
Returns per-BSSID lat/lng positions from the upstream database. Response is
RSSI-weighted and response-filtered (only BSSIDs you observed, not the
"neighbor expansion" extras the endpoint returns).

**Accuracy:** 10–50 m typical in areas with dense, indexed Wi-Fi APs.

**Offline:** never. Requires internet connectivity.

**⚠️ SHIP-BLOCKER:** replace the default `AppleWpsSource` implementation
with a commercially licensed provider before shipping. See `SHIP-BLOCKERS.md`.

### 3. Offline cell-tower DB (`OfflineCellDbSource`, `OfflineCellDb`)

**What:** Looks up visible LTE/GSM/WCDMA/NR cell towers in a local SQLite
derived from OpenCellID's global crowdsourced dataset. The DB is not shipped
in the system image (too large for OTA); it is fetched to `/data/misc/nlp-celldb/`
on first boot by the `nlp-celldb-fetch` init service.

**Accuracy:** 200 m – 2 km depending on tower density.

**Offline:** yes, once the DB has been downloaded.

---

## Learning pipeline

```
GPS fix (accuracy ≤ 30 m)
      │
      ▼
GpsLearningObserver
      │
      ├── For each visible BSSID with RSSI ≥ -80 dBm:
      │       LearnedCacheDb.observe(bssid, lat, lng)
      │       Uses Welford's online algorithm: O(1) memory, numerically stable.
      │
      └── Each WPS response also seeds the cache:
              For each BSSID Apple returned a position for:
                  LearnedCacheDb.observe(bssid, apple_lat, apple_lng)
              This ensures indoor-only devices (GPS never fires) still
              build up a cache over time from accumulated WPS responses.
```

---

## Fusion logic

```
on each tick (interval = requested by LocationManager client):

  1. Wi-Fi scan → list of (BSSID, RSSI)
  2. cache.query(observations)
        → if ≥ 3 cache hits with sufficient confidence:
               deliver Location(provider="cached")
               return   ← skip network call

  3. wps.queryWithRaw(observations)
        → deliver Location(provider="wps")
        → seed cache with per-BSSID positions from response
        → if WPS fails or no Wi-Fi:

  4. cellSource.query(cell.observeOnce())
        → deliver Location(provider="cell")
        → if no cells reported:

  5. no fix this tick
```

---

## Key data paths

| Path | Contents | Owner |
|------|----------|-------|
| `/data/data/<pkg>/databases/learned_cache.db` | Learned BSSID→position mapping | App private, created at runtime |
| `/data/misc/nlp-celldb/cells.db` | OpenCellID global cell-tower SQLite | system:system, fetched by init service |
| `/system_ext/etc/nlp-celldb.sha256` | SHA-256 of the canonical cells.db | Shipped in system image |
| `/data/misc/nlp-celldb/.dev_url` | *(userdebug only)* override CDN URL | Dev testing only |
| `/data/misc/nlp-celldb/.dev_sha` | *(userdebug only)* override SHA file | Dev testing only |

---

## SELinux domains

| Domain | Purpose |
|--------|---------|
| `nlp_celldb_fetch` | Init service that downloads cells.db |
| `nlp_celldb_data_file` | File label for `/data/misc/nlp-celldb/` |
| `nlp_celldb_fetch_exec` | File label for the fetch script |
| `platform_app` | The location provider APK's runtime domain (read access to `nlp_celldb_data_file` granted) |
