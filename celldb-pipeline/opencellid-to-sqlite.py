#!/usr/bin/env python3
"""
opencellid-to-sqlite.py — convert an OpenCellID CSV dump into the indexed
SQLite that OfflineCellDb reads at runtime.

Usage:
    python3 opencellid-to-sqlite.py \\
        --input  cell_towers.csv.gz \\
        --output cells.db \\
        [--max-age-days 540] \\
        [--mcc-allow 310,311,312] \\
        [--min-samples 2]

Inputs:
    OpenCellID CSV columns (from https://wiki.opencellid.org/wiki/Database_format):
        radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,
        created,updated,averageSignal

Output schema (matches open-network-location-provider/src/.../store/OfflineCellDb.kt):
    CREATE TABLE cells (
        radio    TEXT    NOT NULL,
        mcc      INTEGER NOT NULL,
        net      INTEGER NOT NULL,
        area     INTEGER NOT NULL,
        cell     INTEGER NOT NULL,
        lat_e8   INTEGER NOT NULL,
        lng_e8   INTEGER NOT NULL,
        range_m  INTEGER NOT NULL,
        PRIMARY KEY (radio, mcc, net, area, cell)
    );

Filtering knobs (apply in this order):
    --max-age-days  drop towers not 'updated' within the last N days
                    (default 540 = 18 months — matches OpenCellID's own
                    "active" cutoff)
    --mcc-allow     comma-separated list of MCCs to keep. Use this to ship
                    a region-specific DB (e.g. 310,311,312,313,316 for US +
                    Canada). Drops the global DB from ~3.3GB CSV / ~2GB
                    SQLite to ~100MB SQLite for North America.
    --min-samples   drop towers with fewer than N OpenCellID observations
                    (default 2 — single-sample towers are noisy)

Run on a build server with adequate RAM (~4GB). Takes ~15 minutes for the
global dump; ~2 minutes for a single-region subset.

License obligation: any product shipping a derivative of this output MUST
visibly credit "OpenCelliD" with link https://opencellid.org/ in a
user-reachable Settings → About / data attribution screen. CC-BY-SA 4.0.
"""

import argparse
import csv
import gzip
import io
import logging
import sqlite3
import sys
import time

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(message)s",
    level=logging.INFO,
)
log = logging.getLogger("celldb")

SCHEMA = """
CREATE TABLE cells (
    radio   TEXT    NOT NULL,
    mcc     INTEGER NOT NULL,
    net     INTEGER NOT NULL,
    area    INTEGER NOT NULL,
    cell    INTEGER NOT NULL,
    lat_e8  INTEGER NOT NULL,
    lng_e8  INTEGER NOT NULL,
    range_m INTEGER NOT NULL,
    PRIMARY KEY (radio, mcc, net, area, cell)
) WITHOUT ROWID;
"""

# OpenCellID radio strings → what CellObserver emits.
# Keep these in sync with Radio enum in observe/CellObserver.kt.
RADIO_MAP = {
    "GSM": "GSM",
    "UMTS": "WCDMA",
    "CDMA": None,       # not supported on-device
    "LTE": "LTE",
    "NR": "NR",
}


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True,
                   help="OpenCellID CSV (.csv or .csv.gz)")
    p.add_argument("--output", required=True,
                   help="Output SQLite path")
    p.add_argument("--max-age-days", type=int, default=540,
                   help="Drop towers not updated in last N days (default: 540)")
    p.add_argument("--mcc-allow", type=str, default=None,
                   help="Comma-separated MCCs to keep (default: all)")
    p.add_argument("--min-samples", type=int, default=2,
                   help="Drop towers with fewer than N observations (default: 2)")
    args = p.parse_args(argv)

    mcc_allow: set[int] | None = (
        {int(x) for x in args.mcc_allow.split(",")} if args.mcc_allow else None
    )
    cutoff_epoch = int(time.time()) - args.max_age_days * 86400

    # Recreate output DB.
    import os
    if os.path.exists(args.output):
        os.remove(args.output)
    conn = sqlite3.connect(args.output)
    conn.execute("PRAGMA journal_mode=OFF;")
    conn.execute("PRAGMA synchronous=OFF;")
    conn.execute("PRAGMA temp_store=MEMORY;")
    conn.execute("PRAGMA cache_size=-262144;")  # 256 MB
    conn.executescript(SCHEMA)

    log.info("opening %s ...", args.input)
    if args.input.endswith(".gz"):
        fh = io.TextIOWrapper(gzip.open(args.input, "rb"), encoding="utf-8", newline="")
    else:
        fh = open(args.input, encoding="utf-8", newline="")

    reader = csv.DictReader(fh)
    rows = []
    BATCH = 50_000
    inserted = 0
    skipped_radio = 0
    skipped_age = 0
    skipped_mcc = 0
    skipped_samples = 0

    cur = conn.cursor()
    cur.execute("BEGIN")
    for i, r in enumerate(reader):
        try:
            radio_raw = r["radio"]
            mapped = RADIO_MAP.get(radio_raw)
            if mapped is None:
                skipped_radio += 1
                continue
            mcc = int(r["mcc"])
            if mcc_allow and mcc not in mcc_allow:
                skipped_mcc += 1
                continue
            updated = int(r["updated"])
            if updated < cutoff_epoch:
                skipped_age += 1
                continue
            samples = int(r["samples"])
            if samples < args.min_samples:
                skipped_samples += 1
                continue

            net = int(r["net"])
            area = int(r["area"])
            cell = int(r["cell"])
            lat = float(r["lat"])
            lng = float(r["lon"])
            rng = int(float(r["range"]))

            rows.append((
                mapped, mcc, net, area, cell,
                int(lat * 1e8), int(lng * 1e8), rng,
            ))
            if len(rows) >= BATCH:
                cur.executemany(
                    "INSERT OR IGNORE INTO cells VALUES (?,?,?,?,?,?,?,?)",
                    rows,
                )
                inserted += len(rows)
                rows.clear()
                if inserted % 1_000_000 == 0:
                    log.info("inserted %d rows so far ...", inserted)
        except (ValueError, KeyError) as e:
            # Malformed row — OpenCellID occasionally emits these.
            continue

    if rows:
        cur.executemany(
            "INSERT OR IGNORE INTO cells VALUES (?,?,?,?,?,?,?,?)",
            rows,
        )
        inserted += len(rows)

    conn.commit()
    fh.close()

    log.info("vacuuming + analyzing ...")
    conn.execute("PRAGMA journal_mode=DELETE;")
    conn.execute("ANALYZE;")
    conn.commit()
    conn.close()

    # VACUUM in a separate connection (can't run inside transaction).
    conn = sqlite3.connect(args.output)
    conn.execute("VACUUM;")
    conn.close()

    log.info(
        "done. inserted=%d, skipped_radio=%d, skipped_mcc=%d, "
        "skipped_age=%d, skipped_samples=%d. output=%s (%d bytes)",
        inserted, skipped_radio, skipped_mcc, skipped_age, skipped_samples,
        args.output, os.path.getsize(args.output),
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
