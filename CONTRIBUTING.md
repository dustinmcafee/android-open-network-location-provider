# Contributing

Contributions are welcome. Please open an issue before large PRs to
discuss the approach.

## Guidelines

- **No vendor-specific code.** The core library must remain device-agnostic.
  Device-specific integration snippets belong in `docs/INTEGRATION.md`,
  not in the library source.
- **No GMS / microG dependencies.** This project targets pure-AOSP builds.
- **Replace `AppleWpsSource` rather than extending it.** The Apple WPS
  endpoint is a ship-blocker (see `SHIP-BLOCKERS.md`). PRs adding wrapper
  implementations for Skyhook, HERE, Combain, or other licensed providers
  are welcome, but the `AppleWpsSource` must remain as the dev/test default.
- **Tests.** Unit tests for `LearnedCacheDb` (Welford's math), `AppleWpsProto`
  (encode/decode roundtrip), and `OfflineCellDbSource` (schema lookup) would
  be valuable additions. The current test coverage is the integration
  `NlpProbe` APK only.
- **Android version support.** The minimum supported version is Android 12
  (API 31). Android 14 (API 34) is the primary target. PRs lowering the
  minimum to API 30 or below are out of scope.

## Building

See `docs/INTEGRATION.md` for how to set up the AOSP build environment.
For a quick standalone Kotlin compilation check (no AOSP tree needed):

```bash
# Install kotlinc if not present
# Then, from repo root:
find src -name '*.kt' | xargs kotlinc -api-version 1.9 -no-stdlib \
  -classpath <path-to-android.jar> -nowarn 2>&1 | grep -i error || echo "no errors"
```

## Commit style

- One logical change per commit
- Subject line ≤ 72 chars, imperative mood
- Reference the relevant source file or component in the body if helpful
