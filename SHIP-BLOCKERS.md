# Ship-Blockers

Issues that MUST be resolved before deploying this in any customer-facing product.

---

## 1. Replace Apple WPS with a licensed Wi-Fi positioning backend

**File:** `src/main/java/com/github/dustinmcafee/nlp/source/AppleWpsSource.kt`

The current Wi-Fi positioning source queries Apple's `gs-loc.apple.com/clls/wloc`
endpoint. This endpoint is undocumented and unauthenticated. Apple has
tolerated its use by open-source projects (GrapheneOS, CalyxOS, /e/OS,
LineageOS) for years, but:

- Apple has not authorized third-party commercial use
- The endpoint could change or be restricted without notice
- Usage in a shipped product creates legal exposure depending on jurisdiction

**Required action:** replace `AppleWpsSource.kt` with a wrapper around a
commercially licensed Wi-Fi positioning provider before any customer release.

**Recommended vendors:**

| Vendor | Model | Notes |
|--------|-------|-------|
| [Skyhook](https://www.skyhook.com) | Per-device OEM license | Broad global coverage; lowest-risk for fleet deployments |
| [HERE Positioning](https://developer.here.com) | Enterprise contract | Strong EU coverage; REST API trivial to wrap |
| [Combain](https://combain.com) | Per-query, transparent pricing | IoT-focused; published per-1000-query rates |

The wrapper is ~200 LOC of Kotlin — the `PositioningClient` interface in
`AppleWpsSource.kt` is designed so only the HTTP call + response parsing
needs to change. `FusionEngine` does not need modification.

---

## 2. Provide a hosted cells.db artifact

**File:** `integration/init/nlp-celldb-fetch.sh`

`DUMP_URL` is set to `https://TODO-YOUR-CDN/nlp-celldb/cells.db`. The
first-boot cell-DB fetcher will bail out cleanly until this is replaced with
a real URL.

The `celldb-pipeline/` scripts generate a deployable artifact from
OpenCellID's data. Once you've run `upload-celldb.sh` and have a stable URL:

1. Set `DUMP_URL` in the fetch script
2. Update `integration/init/nlp-celldb-sha256` with the generated SHA
3. Rebuild the system image

See `docs/CELLDB-SETUP.md` for the full pipeline.

---

## 3. Add OpenCellID attribution to your product UI

The OpenCellID dataset is licensed **CC BY-SA 4.0**. Products that ship a
derivative (the cells.db SQLite) must:

- Visibly credit "OpenCelliD" with a link to https://opencellid.org/
- Surface that credit somewhere user-reachable (Settings → About is sufficient)

The attribution string is already defined in `res/values/strings.xml`
(`@string/celldb_attribution`). Wire it into your Settings UI.

---

_All other items (SELinux tuning, soak testing, geocoder, geofencing layer) are
enhancements rather than blockers. The service functions correctly without them._
