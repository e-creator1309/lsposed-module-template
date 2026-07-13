# Nova Device Spoof

An LSPosed module that makes chosen apps believe they're running on a newer device or
region, by overriding the values they read from `android.os.Build` / `Build.VERSION`
and OEM "floating feature" flags (the mechanism Samsung/OneUI apps use to gate features
by model or region).

Nothing on the real device is ever touched:

- `/system/build.prop` and `/system/etc/floating_feature.xml` are **not edited**. There
  is no dm-verity/AVB interaction and no bootloop risk from this module.
- The override exists only inside the memory of a target app's own process, applied the
  moment that process starts, before the app's own code runs.
- Only apps you explicitly enable in the companion app are affected. Every other app,
  including `system_server`, is left alone.

## Why this instead of editing build.prop directly

- **Unlocks app-gated features** (AI features, floating window/blur effects, etc.) that
  check `ro.build.version.*` / `ro.product.*` / floating-feature flags before enabling
  themselves.
- **Works around apps that reject OneUI/device-name updates** without needing a real
  OTA or a system-wide prop edit.
- **No bootloop**, because the hook lives inside the target app's process, not the boot
  chain or `system_server`.
- **No thermal/battery cost from a globally spoofed device tier** — only the apps you
  pick see the higher-end profile; the rest of the system reports the real hardware.

## Configuring

1. Install the APK (see Building/Installing below) and enable it as a module in LSPosed
   Manager. Since this module is meant to work against *any* app, it ships with no
   fixed scope — open LSPosed Manager and tick the scope checkbox for each app you plan
   to target.
2. Launch "Nova Device Spoof" (the companion app) and paste your profile:
   - **build.prop profile** — one `KEY=VALUE` per line, e.g.:
     ```
     ro.product.model=SM-S928B
     ro.product.manufacturer=samsung
     ro.build.version.release=15
     ro.build.version.sdk=35
     ```
   - **floating_feature.xml profile** — paste the relevant `<category name="...">` /
     `<config name="...">` entries from a `floating_feature.xml` dump, e.g.:
     ```xml
     <category name="SEC_FLOATING_FEATURE_COMMON_CONFIG_MODEL_NAME">SM-S928B</category>
     ```
3. Tap **Save profile**.
4. Tap **Choose apps** and select which installed apps should see this profile.
5. Force-stop or relaunch the target apps (no reboot needed — the hook applies the next
   time each target app's process starts).

Where do you get a "recent" build.prop / floating_feature.xml? The companion app does
not fetch anything from the internet — you paste in values yourself (e.g. copied from a
newer device's dump, a GSI, or public device-spec references for the model you want to
imitate).

## What gets intercepted

- `android.os.Build` and `android.os.Build.VERSION` static fields, reflectively
  overridden per-process for every key present in your build.prop profile that maps to
  a known field (model, manufacturer, brand, device, product, hardware, board,
  fingerprint, id, display, tags, type, release, sdk, incremental, codename,
  security patch).
- `android.os.SystemProperties.get(String[, String])`, so raw `getprop`-style reads for
  *any* key in your profile are covered too, not just the ones with a matching `Build`
  field.
- Samsung/OneUI floating-feature getters (`FloatingFeatureImpl` / `SemFloatingFeature` /
  `SemCscFeature` — whichever exists on the ROM) for any key present in your
  floating-feature profile. Devices without these classes simply skip this step.

## Building

This repo has no committed Gradle wrapper; the CI workflow (`.github/workflows/build.yml`)
installs Gradle directly and runs:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`, uploaded as a workflow artifact
on every push. Push a tag (e.g. `v1.0.0`) to also attach the APK to a GitHub Release.

Locally, with Android SDK + Gradle installed:

```bash
gradle assembleDebug
```

## Installing

1. Install the APK and enable it in LSPosed Manager.
2. Enable module scope for each app you intend to target (see "Configuring" above).
3. Configure a profile and target apps from the companion app.

## Logs

```
adb logcat -s NovaDeviceSpoof
```

## Compatibility notes (verified against a real Samsung Gallery build)

The floating-feature hook targets were confirmed by decompiling an actual Samsung Gallery
APK with `apktool` rather than guessed. Two real code paths exist and this module hooks
both:

- Modern OneUI devices: `com.samsung.android.feature.SemFloatingFeature`/`SemCscFeature`
  singletons (`getInstance()`), with **instance** getters `getBoolean`/`getInt`/`getString`.
- Fallback path (seen on GED/AOSP-leaning builds): `com.samsung.sesl.feature.SemFloatingFeature`/
  `SemCscFeature` **static** `hidden_getString(key, default)` accessors.

`android.os.Build` / `Build.VERSION` fields and `SystemProperties.get()` reads were also
confirmed safe to override reflectively: real apps read these as plain field/method
lookups (never inlined at compile time), so no recompilation trick is needed on their side.
