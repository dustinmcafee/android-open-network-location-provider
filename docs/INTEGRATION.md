# Integration Guide

How to integrate `open-network-location-provider` into an AOSP device tree.

## Requirements

- Android 14 (API 34) AOSP source tree
- Soong build system (`android_app` module support)
- A privileged system_ext partition
- `adb` for testing

The service works on Android 12–14. Android 14's `LocationProviderBase`
API (used as the base class) is public-API (`@SystemApi`) and referenced
via `sdk_version: "system_current"` in the `Android.bp`.

---

## Step 1 — Verify the AOSP framework binding contract

Before integrating, confirm the action string your tree uses:

```bash
grep -r 'NetworkLocationProvider' \
  frameworks/base/location/java/android/location/provider/ | grep ACTION_
```

Android 14 uses `com.android.location.service.v3.NetworkLocationProvider`.
If your tree uses v2, update `AndroidManifest.xml` accordingly.

Also confirm `config_networkLocationProviderPackageName` exists:

```bash
grep -n 'config_networkLocationProviderPackageName' \
  frameworks/base/core/res/res/values/config.xml
```

---

## Step 2 — Place the source files in your vendor tree

Copy or symlink this repository into your vendor tree, e.g.:

```
vendor/<company>/open-network-location-provider/   ← repo root
```

The Soong module name is `OpenNetworkLocation` (in `Android.bp`). Rename
if it conflicts with anything in your tree.

---

## Step 3 — Add the framework overlay

The overlay at `integration/overlay/` overrides two critical framework
resource strings:

```xml
<bool name="config_enableNetworkLocationOverlay">false</bool>
<string name="config_networkLocationProviderPackageName">com.github.dustinmcafee.nlp</string>
```

**`config_enableNetworkLocationOverlay` MUST be `false`.**  When `true`
(the AOSP default), the framework ignores the package name string and scans
every installed system app that declares the v3 NLP action. Your platform's
vendor NLP (Qualcomm IZat, MediaTek Location, etc.) will win. Setting it
to `false` forces exclusive use of the explicit package name.

Add the overlay to your device makefile:

```makefile
PRODUCT_PACKAGE_OVERLAYS += vendor/<company>/open-network-location-provider/integration/overlay
```

If you gate this provider on a build flag (e.g. to ship both GMS and
non-GMS variants from the same tree), place it in a separate overlay dir:

```makefile
ifneq ($(strip $(BUILD_PRODUCT_GMS)),true)
    PRODUCT_PACKAGE_OVERLAYS += vendor/<company>/open-network-location-provider/integration/overlay
endif
```

---

## Step 4 — Add to PRODUCT_PACKAGES

```makefile
# device.mk or product.mk
PRODUCT_PACKAGES += OpenNetworkLocation
```

---

## Step 5 — Add privapp-permissions

```makefile
PRODUCT_COPY_FILES += \
    vendor/<company>/open-network-location-provider/integration/privapp-permissions-nlp.xml:\
    system_ext/etc/permissions/privapp-permissions-nlp.xml
```

---

## Step 6 — Wire the SELinux policy

Append the integration policy to your `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`:

```makefile
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += \
    vendor/<company>/open-network-location-provider/integration/sepolicy
```

**Important:** the `file_contexts` file in `integration/sepolicy/` contains
two entries that must be appended to your device's existing `file_contexts`.
Either add them directly, or include the file via your sepolicy build rules.
Without these, `restorecon` will mislabel the cell-DB files and the service
will AVC-deny on read.

---

## Step 7 — Ship the cell-DB fetcher (optional but recommended)

The cell-DB fetcher is an init service that downloads an OpenCellID-derived
SQLite to `/data/misc/nlp-celldb/cells.db` on first boot.

```makefile
# Add the init service + script
PRODUCT_PACKAGES += nlp-celldb-fetch.rc
PRODUCT_PACKAGES += nlp-celldb-fetch.sh

# Add the BUILD_PREBUILT entries (Android.mk) or Soong targets for the
# RC file and shell script. Example Android.mk pattern:

# include $(CLEAR_VARS)
# LOCAL_MODULE        := nlp-celldb-fetch.rc
# LOCAL_MODULE_CLASS  := ETC
# LOCAL_SRC_FILES     := integration/init/nlp-celldb-fetch.rc
# LOCAL_MODULE_PATH   := $(TARGET_OUT_SYSTEM_EXT_ETC)/init
# include $(BUILD_PREBUILT)

# include $(CLEAR_VARS)
# LOCAL_MODULE        := nlp-celldb-fetch.sh
# LOCAL_MODULE_CLASS  := EXECUTABLES
# LOCAL_SRC_FILES     := integration/init/nlp-celldb-fetch.sh
# LOCAL_MODULE_PATH   := $(PRODUCT_OUT)/system_ext/bin
# LOCAL_MODULE_SUFFIX :=
# include $(BUILD_PREBUILT)

# Ship the SHA digest (placeholder until you run the pipeline)
PRODUCT_COPY_FILES += \
    vendor/<company>/open-network-location-provider/integration/nlp-celldb.sha256:\
    system_ext/etc/nlp-celldb.sha256
```

Create the placeholder SHA file:

```
TODO_RUN_CELLDB_PIPELINE  cells.db
```

Replace this with the real SHA after running `celldb-pipeline/upload-celldb.sh`.
See `docs/CELLDB-SETUP.md`.

---

## Step 8 — Build and validate

```bash
# Build
m OpenNetworkLocation -j$(nproc)

# Install without reflash (first time must be full flash to get priv-app +
# overlay + perms + sepolicy; subsequent iterations use adb install -r)
adb install -r out/target/product/<device>/system_ext/priv-app/OpenNetworkLocation/OpenNetworkLocation.apk

# Validate service is bound
adb shell dumpsys location | grep -A5 'network provider:'
# Expect: identity=.../com.github.dustinmcafee.nlp
#         properties=ProviderProperties[..., requires=network]
```

For the full validation procedure including the NlpProbe test harness, see
the probe's `Android.bp` and the main `README.md`.

---

## Common pitfalls

| Symptom | Cause | Fix |
|---------|-------|-----|
| `network provider` still shows platform NLP (e.g. `com.qualcomm.location`) | `config_enableNetworkLocationOverlay` is still `true` | Set to `false` in the overlay |
| `ProviderRequest[OFF]` even when client is requesting | Screen is off / app backgrounded | Keep screen awake during testing (`stay_on_while_plugged_in=7`); the framework throttles background-only apps to a 30-minute interval |
| `avc: denied { search } ... nlp_celldb_data_file` | SELinux file_contexts entries not applied | Run `restorecon -R /data/misc/nlp-celldb/` or rebuild with the file_contexts patch |
| 0 BSSIDs delivered after Wi-Fi off | `getAllCellInfo()` returning empty (modem not registered on a tower) | Expected on IWLAN-only devices; confirm SIM carrier has LTE coverage at that location |
