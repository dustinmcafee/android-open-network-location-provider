# Cell-DB Setup

How to build and distribute the OpenCellID-derived SQLite for offline
cell-tower positioning.

## Overview

The cell-tower database is NOT shipped in the system image (adding 150–500 MB
to every OTA delta is impractical). Instead:

1. **Build server** (quarterly): downloads OpenCellID CSV, converts to
   indexed SQLite, uploads artifact to your CDN, computes SHA-256.
2. **System image**: ships the SHA-256 digest and a download URL pointing
   at your CDN.
3. **Device (first boot)**: init service `nlp-celldb-fetch` downloads the
   SQLite, verifies SHA, installs to `/data/misc/nlp-celldb/cells.db`.

---

## Build-server pipeline

### Prerequisites

- Python 3.10+
- `curl`, `sha256sum`
- OpenCellID API token (free — register at https://opencellid.org)
- An HTTPS-accessible artifact host (your internal CDN, S3 bucket, etc.)

### Step 1 — Run the pipeline

```bash
export OCID_API_TOKEN="your-token-here"
export CDN_PUT_URL="https://your-cdn/path/to/cells.db"  # PUT endpoint

celldb-pipeline/upload-celldb.sh [--region global|us-canada|eu]
```

Region presets:

| Flag | MCCs included | Approximate DB size |
|------|---------------|---------------------|
| `global` (default) | All | ~150–200 MB SQLite |
| `us-canada` | 310,311,312,313,316,302 | ~30 MB |
| `eu` | Major EU MNCs | ~50 MB |

The script will:
1. Download the OpenCellID CSV (~100–900 MB compressed, depending on region)
2. Filter by `--max-age-days 540` and `--min-samples 2`
3. Convert to indexed SQLite via `opencellid-to-sqlite.py`
4. Upload to `$CDN_PUT_URL`
5. Write the SHA-256 to `integration/nlp-celldb.sha256`

Commit the updated `integration/nlp-celldb.sha256` and rebuild the system
image to distribute the new digest to devices.

### Step 2 — Update the fetch script URL

Edit `integration/init/nlp-celldb-fetch.sh`, line:

```bash
DUMP_URL="https://TODO-YOUR-CDN/nlp-celldb/cells.db"
```

Replace with your actual CDN URL. Rebuild and reflash.

---

## On-device fetcher behavior

The init service `nlp-celldb-fetch` runs once per boot at `sys.boot_completed=1`.
It is idempotent:

- If `cells.db` is present and its SHA matches the shipped digest, the
  service exits immediately (no download).
- If `cells.db` is absent, SHA mismatches, or is older than 90 days, the
  service downloads a fresh copy.
- If the CDN is unreachable (no connectivity), the service exits cleanly
  and retries on next boot.

---

## Dev-testing CDN override (userdebug builds only)

On `ro.build.type=userdebug` images you can redirect the fetcher to a local
HTTP server without rebuilding:

```bash
# Start a dev CDN on the build machine
python3 -m http.server 8080 --directory /path/to/dir/containing/cells.db &

# Point device at it (adb reverse tunnels the build machine's port)
adb reverse tcp:8080 tcp:8080
adb shell "echo 'http://localhost:8080/cells.db' > /data/misc/nlp-celldb/.dev_url"
adb shell "echo '<sha256>  cells.db' > /data/misc/nlp-celldb/.dev_sha"

# Trigger a fresh download
adb shell rm -f /data/misc/nlp-celldb/cells.db /data/misc/nlp-celldb/.last_refresh_epoch
adb shell setprop ctl.start nlp-celldb-fetch
adb logcat -s nlp-celldb-fetch:V
```

The `.dev_url` / `.dev_sha` overrides are **silently ignored on user builds**,
so they cannot redirect fleet devices even if the files are somehow planted.

---

## Attribution (required)

The OpenCellID dataset is licensed **CC BY-SA 4.0**. You must:

1. Visibly credit "OpenCelliD" with a link to https://opencellid.org/ in any
   product or documentation that ships a derivative of this dataset.
2. The attribution string `@string/celldb_attribution` in `res/values/strings.xml`
   is provided for this purpose — surface it in your Settings → About screen or
   equivalent.

Failure to attribute is a license violation.
