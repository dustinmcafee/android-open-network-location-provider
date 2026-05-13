#!/bin/sh
#
# shellcheck shell=sh
# shellcheck disable=SC2039,SC2312  # Android builtins: log, getprop, stat -c%s
# nlp-celldb-fetch.sh — on-device first-boot fetcher for the
# OpenCellID-derived SQLite cell tower database.
#
# Lives at /system_ext/bin/nlp-celldb-fetch.sh. Runs once per boot under
# the `system` user via init service `nlp-celldb-fetch`.
#
# Exit conditions:
#   0 — success (downloaded + verified, or already-fresh, or no connectivity)
#   1 — SHA mismatch after download (file removed, will retry next boot)
#
# Idempotent. Safe to invoke manually:
#   adb shell setprop ctl.start nlp-celldb-fetch

set -u

LOG_TAG="nlp-celldb-fetch"
DEST_DIR="/data/misc/nlp-celldb"
DEST_FILE="$DEST_DIR/cells.db"
DEST_TMP="$DEST_DIR/cells.db.part"
SHIPPED_SHA="/system_ext/etc/nlp-celldb.sha256"
STAMP_FILE="$DEST_DIR/.last_refresh_epoch"

# Refresh interval: re-download if local file older than this many seconds (90d).
REFRESH_AFTER_SEC=7776000

# ⚠ TODO: replace with the actual canonical artifact URL once
# vendor/<company>/open-network-location-provider/celldb-pipeline/upload-celldb.sh has
# uploaded it to a stable location (e.g. your internal CDN).
# OpenCellID's public download requires an API token and is rate-limited
# to 2 downloads/day per token, so devices must NOT hit it directly.
DUMP_URL="https://TODO-YOUR-CDN/nlp-celldb/cells.db"

log() { log -t "$LOG_TAG" -p i "$1"; }
err() { log -t "$LOG_TAG" -p e "$1"; }

[ -d "$DEST_DIR" ] || mkdir -p "$DEST_DIR"
chown system:system "$DEST_DIR"
chmod 0775 "$DEST_DIR"

# ─── userdebug-only dev overrides ────────────────────────────────────────
#
# On userdebug builds, allow developers to point the fetcher at a local
# Apache / Python / nginx dev CDN without rebuilding the system image.
# Two override files, both under $DEST_DIR (which is system-only writable
# so a non-root app can't plant them):
#
#   /data/misc/nlp-celldb/.dev_url   one-line URL for cells.db
#   /data/misc/nlp-celldb/.dev_sha   sha256 file (same format as the
#                                    shipped one — first column is hash)
#
# Gated on ro.build.type=userdebug. On user (production) builds these
# files are silently ignored even if they exist, so a leaked override on
# a customer device cannot redirect the fetcher to an attacker-chosen
# URL.
#
# Cleanup (return to production behavior):
#   adb shell rm /data/misc/nlp-celldb/.dev_url /data/misc/nlp-celldb/.dev_sha
if [ "$(getprop ro.build.type)" = "userdebug" ]; then
    if [ -f "$DEST_DIR/.dev_url" ]; then
        DUMP_URL="$(awk 'NF{print; exit}' "$DEST_DIR/.dev_url")"
        log "DEV OVERRIDE: DUMP_URL=$DUMP_URL"
    fi
    if [ -f "$DEST_DIR/.dev_sha" ]; then
        SHIPPED_SHA="$DEST_DIR/.dev_sha"
        log "DEV OVERRIDE: SHIPPED_SHA file=$SHIPPED_SHA"
    fi
fi

# Read shipped expected sha (first whitespace-separated field).
if [ ! -f "$SHIPPED_SHA" ]; then
    err "shipped SHA file missing: $SHIPPED_SHA — aborting"
    exit 0
fi
EXPECTED_SHA="$(awk '{print $1; exit}' "$SHIPPED_SHA")"
case "$EXPECTED_SHA" in
    TODO*|"")
        err "shipped SHA is placeholder ($EXPECTED_SHA) — aborting cleanly. " \
            "Regenerate via vendor/<company>/open-network-location-provider/celldb-pipeline/upload-celldb.sh."
        exit 0 ;;
esac

# Already-have-correct-file fast path.
if [ -f "$DEST_FILE" ]; then
    actual="$(sha256sum "$DEST_FILE" 2>/dev/null | awk '{print $1}')"
    if [ "$actual" = "$EXPECTED_SHA" ]; then
        if [ -f "$STAMP_FILE" ]; then
            now=$(date +%s)
            then_=$(cat "$STAMP_FILE" 2>/dev/null || echo 0)
            if [ $((now - then_)) -lt "$REFRESH_AFTER_SEC" ]; then
                log "cell DB present and fresh — nothing to do"
                exit 0
            fi
            log "cell DB hash matches but is older than refresh window — refetching"
        else
            log "cell DB present and matches shipped SHA — nothing to do"
            date +%s > "$STAMP_FILE"
            exit 0
        fi
    else
        log "cell DB present but hash mismatch (have $actual, want $EXPECTED_SHA) — refetching"
        rm -f "$DEST_FILE"
    fi
fi

# Wait briefly for connectivity (init may start us before network is up).
attempts=0
until ping -c1 -W2 8.8.8.8 >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 30 ]; then
        log "no connectivity after 60s — exiting cleanly, will retry next boot"
        exit 0
    fi
    sleep 2
done

log "downloading cell DB from $DUMP_URL"
rm -f "$DEST_TMP"
if ! curl --silent --show-error --fail --location \
          --max-time 1800 --retry 3 --retry-delay 10 \
          -o "$DEST_TMP" "$DUMP_URL"; then
    err "curl failed — exiting, will retry next boot"
    rm -f "$DEST_TMP"
    exit 0
fi

actual="$(sha256sum "$DEST_TMP" 2>/dev/null | awk '{print $1}')"
if [ "$actual" != "$EXPECTED_SHA" ]; then
    err "SHA mismatch: got $actual, expected $EXPECTED_SHA — discarding"
    rm -f "$DEST_TMP"
    exit 1
fi

mv "$DEST_TMP" "$DEST_FILE"
chown system:system "$DEST_FILE"
chmod 0664 "$DEST_FILE"
date +%s > "$STAMP_FILE"
log "cell DB installed at $DEST_FILE ($(stat -c%s "$DEST_FILE") bytes)"
exit 0
