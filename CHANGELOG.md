# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] — 2026-05-01

### Added

**Core service**
- `NetworkLocationService` — AOSP `NETWORK_PROVIDER` service implementing the
  v3 `LocationProviderBase` contract (Android 12+, API 31)
- `FusionEngine` — priority-ordered fix dispatch: learned cache → Wi-Fi WPS → offline cell-DB
- `WifiObserver` — `getScanResults()` wrapper with Android 9+ scan-throttle awareness
- `CellObserver` — `getAllCellInfo()` wrapper normalizing LTE / GSM / WCDMA / NR to a uniform tuple
- `GpsLearningObserver` — passive GPS listener that anchors observed BSSIDs to GPS coordinates
  using Welford's online running-mean algorithm

**Wi-Fi positioning**
- `AppleWpsSource` — Wi-Fi BSSID → lat/lng via Apple's WPS endpoint (dev/test only;
  see SHIP-BLOCKERS.md before shipping)
- ARPC envelope encoding (verified against acheong08/apple-corelocation-experiments)
- Hand-rolled AppleWLoc protobuf codec — no `protoc` dependency
- RSSI-weighted centroid + response-filter (observed BSSIDs only, not neighbor expansion)

**Learned cache**
- `LearnedCacheDb` — SQLite-backed BSSID position store, Welford's mean updates,
  LRU eviction at 10K entries
- `LearnedCacheSource` — confidence-gated offline lookup (≥3 BSSID hits, ≥1 trusted sample,
  ≥1 strong-RSSI AP required before delivering a fix)
- WPS responses also seed the cache (per-BSSID positions from Apple's neighbor expansion,
  de-noised by running mean)

**Offline cell-tower DB**
- `OfflineCellDb` — read-only SQLite at `/data/misc/nlp-celldb/cells.db`,
  graceful empty-mode when file absent
- `OfflineCellDbSource` — signal-weighted centroid from matched tower positions
- `celldb-pipeline/opencellid-to-sqlite.py` — converts OpenCellID global CSV
  (~4.26M rows after filtering) to an indexed 151 MB SQLite
- `celldb-pipeline/upload-celldb.sh` — full build-server pipeline with regional filtering
- `integration/init/nlp-celldb-fetch.sh` — first-boot fetcher with SHA-256 verification,
  90-day staleness refresh, idempotent restart, userdebug dev-override support

**Integration**
- Soong `android_app` module (`sdk_version: "system_current"`, platform-signed, system\_ext)
- Framework overlay: `config_enableNetworkLocationOverlay=false` +
  `config_networkLocationProviderPackageName` (verified against Android 14 source)
- SELinux policy: `nlp_celldb_fetch` domain, `nlp_celldb_data_file` label,
  `platform_app` read grant
- `NlpProbe` test harness APK

### Known limitations (ship-blockers)

- Apple WPS endpoint (`gs-loc.apple.com/clls/wloc`) is undocumented and not licensed
  for commercial use. Replace `AppleWpsSource` before shipping.
- `DUMP_URL` in the cell-DB fetcher is a placeholder. Run `upload-celldb.sh` and
  set a real CDN URL before first fleet deployment.
- OpenCelliD CC BY-SA 4.0 attribution must be surfaced in product UI.

[1.0.0]: https://github.com/dustinmcafee/open-network-location-provider/releases/tag/v1.0.0
