# Nova Sig Spoof

LSPosed module that spoofs app signatures system-wide via a `PackageManagerService` hook.

## How it works

- Hooks `PackageManagerService.getPackageInfo()` inside **system_server** (the `android` process)
- Because all apps call PM via Binder → this single hook covers every caller on the device
- Works alongside **Shamiko** / **Zygisk-Assistant** (those hide root; this spoofs signatures)

## Enabling spoofing for an app

Add a `<meta-data>` tag to the `<application>` block in the target APK's `AndroidManifest.xml`:

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

Add the GMS cert (Google's release key) to MicroG's manifest.  
The Base64 value for Google's AOSP test cert is already hardcoded in FakeGApps — use that
if you're only targeting MicroG compatibility.

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
