#!/usr/bin/env bash
#
# upload-celldb.sh — full build-server pipeline:
#   1. Download OpenCellID CSV (requires API token, rate-limited 2/day)
#   2. Convert CSV → indexed SQLite via opencellid-to-sqlite.py
#   3. Compute SHA-256
#   4. Upload SQLite to internal CDN
#   5. Update vendor/<company>/open-network-location-provider/etc/nlp-celldb.sha256 with the new digest
#
# Run on a build server quarterly. Cell tower data drifts slowly; more
# frequent refreshes burn API quota for negligible accuracy gain.
#
# Required env vars:
#   OCID_API_TOKEN  — OpenCellID API token (free at opencellid.org)
#   CDN_PUT_URL — pre-signed PUT URL or rsync target for cells.db
#                     (must match $DUMP_URL in
#                      vendor/<company>/open-network-location-provider/rootdir/nlp-celldb-fetch.sh)
#
# Output:
#   - Updated  vendor/<company>/open-network-location-provider/etc/nlp-celldb.sha256
#   - Uploaded artifact at $CDN_PUT_URL
#   - Local cache at  celldb-pipeline/cache/{cell_towers.csv.gz, cells.db}
#
# Optional: --region us-canada to ship only NA MCCs (~100 MB SQLite vs. ~2 GB
# for the global). Edit MCC_ALLOW below to add/remove regions.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# FILL IN: path to your vendor tree
CACHE_DIR="$SCRIPT_DIR/cache"
SHA_OUT="$VENDOR_DIR/etc/nlp-celldb.sha256"

mkdir -p "$CACHE_DIR"

: "${OCID_API_TOKEN:?set OCID_API_TOKEN — register at opencellid.org}"
: "${CDN_PUT_URL:?set CDN_PUT_URL — destination for cells.db}"

REGION="${1:-global}"
case "$REGION" in
    --region)  REGION="${2:?--region requires a value}";;
    global)    MCC_ALLOW="" ;;
    us-canada) MCC_ALLOW="310,311,312,313,316,302" ;;
    eu)        MCC_ALLOW="208,234,262,222,214,206,204,234,228,232,238,242,244,247,248,250,260,266,268" ;;
    *)         echo "unknown region: $REGION (try: global, us-canada, eu)"; exit 1 ;;
esac

CSV="$CACHE_DIR/cell_towers.csv.gz"
SQL="$CACHE_DIR/cells.db"

OCID_URL="https://opencellid.org/ocid/downloads?token=$OCID_API_TOKEN&type=full&file=cell_towers.csv.gz"

echo "→ downloading OpenCellID CSV (~900 MB) ..."
curl -fL --retry 3 -o "$CSV.part" "$OCID_URL"
mv "$CSV.part" "$CSV"

echo "→ converting CSV → SQLite (region=$REGION) ..."
PY_ARGS=(--input "$CSV" --output "$SQL" --max-age-days 540 --min-samples 2)
[ -n "$MCC_ALLOW" ] && PY_ARGS+=(--mcc-allow "$MCC_ALLOW")
python3 "$SCRIPT_DIR/opencellid-to-sqlite.py" "${PY_ARGS[@]}"

SHA="$(sha256sum "$SQL" | awk '{print $1}')"
SIZE="$(stat -c%s "$SQL")"
echo "→ sha256: $SHA"
echo "→ size:   $SIZE bytes"

echo "→ uploading to $CDN_PUT_URL"
curl -fL --upload-file "$SQL" "$CDN_PUT_URL"

echo "→ writing $SHA_OUT"
printf '%s  cells.db\n' "$SHA" > "$SHA_OUT"

echo
echo "Done. Commit $SHA_OUT (and bump 4th positional arg in the celldb-fetch URL"
echo "if the artifact path changed) and trigger the next NOGMS image build."
echo "Devices pick up the new DB on next boot — sha mismatch triggers refetch."
