---
name: Bug report
about: Something isn't working
labels: bug
---

## Environment

- Android version: <!-- e.g. 14 (API 34) -->
- SoC / device: <!-- e.g. Qualcomm SM6225, Mediatek MT6879 -->
- Build type: <!-- userdebug / user -->
- Provider in use: <!-- wps / cached / cell — from Location.provider field -->

## Describe the bug

<!-- What happened? What did you expect? -->

## Relevant logs

```
# Capture with:
# adb logcat -s OpenNlpService:V NlpFusion:V NlpWifiObserver:I AppleWpsSource:I NlpLearnedCache:I NlpCellObserver:I

paste logs here
```

## dumpsys location (network provider section)

```
# adb shell dumpsys location | grep -A10 'network provider:'

paste here
```

## Checklist

- [ ] Screen was awake during the test (`stay_on_while_plugged_in=7`)
- [ ] `adb shell getenforce` returns `Enforcing`
- [ ] `config_enableNetworkLocationOverlay` is set to `false` in the overlay
- [ ] `dumpsys location` shows `identity=.../com.github.dustinmcafee.nlp`
