# Nova Sig Spoof

LSPosed module that spoofs app signatures system-wide via a `PackageManagerService` hook.

## How it works

- Hooks `PackageManagerService.getPackageInfo()` inside **system_server** (the `android` process)
- Because all apps call PM via Binder → this single hook covers every caller on the device
- Works alongside **Shamiko** / **Zygisk-Assistant** (those hide root; this spoofs signatures)

## Enabling spoofing for an app (companion app — recommended)

Nova Sig Spoof now ships with a companion app (`MainActivity`) so you don't have to edit
any manifests by hand:

1. Open **Nova Sig Spoof** from your app drawer.
2. Tap **+**, pick the app you want to spoof.
3. Choose how to get a signature:
   - **Clone from an installed app** — picks another app already on the device and copies
     its real signing certificate (e.g. pick a real Google app to make MicroG look signed
     by Google).
   - **Paste a Base64 DER certificate** — for certs you extracted yourself.
4. Save. Toggle the switch on any rule to enable/disable it, or delete it with the trash icon.

Rules take effect the next time the target app's package info is queried — no reboot needed
for most apps, though some callers cache `PackageInfo` and may need a restart.

Under the hood, the companion app writes rules to its own `SharedPreferences` and relaxes
that file's permissions so the LSPosed hook (running inside `system_server`, a different
UID) can read it via `XSharedPreferences`. This is the standard mechanism Xposed provides
for module ↔ companion-app configuration.

## Enabling spoofing for an app (manual, legacy)

You can still declare a fake signature directly in a target app's manifest — the hook
checks the companion app's rules first, then falls back to this:

```xml
<meta-data
    android:name="fake-signature"
    android:value="BASE64_DER_CERT_HERE" />
```

`BASE64_DER_CERT_HERE` is the Base64-encoded DER certificate you want the app to appear signed with.

### Getting the Base64 cert from a real APK

```bash
# Extract cert from an APK
unzip -p RealApp.apk META-INF/*.RSA | openssl pkcs7 -inform DER -print_certs | \
  openssl x509 -outform DER | base64 -w 0
```

### Example: MicroG pretending to be Google

Easiest path: install a real Google app, then in the companion app add a rule for MicroG
and choose "clone from an installed app" → that Google app. No manual cert extraction needed.

## Supported Android versions

| Android | Flags type | SigningDetails location |
|---------|-----------|------------------------|
| 9–12    | `int`     | `PackageParser$SigningDetails` |
| 13–15   | `long`    | `android.content.pm.SigningDetails` |

Both constructor variants are tried at runtime — no need to choose.

## Building

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Installing

1. Flash via Magisk / KernelSU module manager **or** install the APK and enable in LSPosed Manager
2. Scope: `android` (system_server) — already set in the module metadata
3. Reboot

## Logs

```
adb logcat -s NovaSpoof
```

You'll see one line per package where spoofing is applied at first query.
