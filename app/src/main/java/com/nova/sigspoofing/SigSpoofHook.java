package com.nova.sigspoofing;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Nova Signature Spoof — LSPosed module
 *
 * Hooks PackageManagerService.getPackageInfo() inside system_server so that
 * every Binder caller (any app) receives a spoofed signature for packages that
 * declare the following in their <application> element:
 *
 *   <meta-data
 *       android:name="fake-signature"
 *       android:value="BASE64_DER_CERT" />
 *
 * Works alongside Shamiko / Zygisk-Assistant (those hide root; this spoofs sigs).
 * Supports Android 9–15 (int flags, long flags, SigningDetails variants).
 */
public class SigSpoofHook implements IXposedHookLoadPackage {

    static final String TAG = "NovaSpoof";

    /** Meta-data key an app puts in its manifest to declare a fake cert (Base64 DER). */
    static final String META_FAKE_SIG = "fake-signature";

    /** Sentinel: package was checked and has no fake-signature → skip it. */
    static final byte[] NO_SPOOF = new byte[0];

    /** Package name → DER cert bytes to use, or NO_SPOOF. */
    static final ConcurrentHashMap<String, byte[]> sCache = new ConcurrentHashMap<>();

    /** Prevents re-entrancy when we do a secondary getApplicationInfo() call. */
    static final ThreadLocal<Boolean> sInHook = ThreadLocal.withInitial(() -> false);

    /** Companion app package + prefs file where the UI writes spoof rules. */
    static final String COMPANION_PKG = "com.nova.sigspoofing";
    static final String COMPANION_PREFS = "spoof_config";

    static volatile XSharedPreferences sPrefs;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Only inject into system_server (the "android" package process).
        // PackageManagerService lives here; hooking it covers ALL Binder callers.
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": attaching hooks in system_server");
        hookPMS(lpparam.classLoader);
        hookApplicationPM(lpparam.classLoader);   // belt-and-suspenders for in-process calls
    }

    // ── 1. Server-side: covers every app via Binder ──────────────────────────
    private static void hookPMS(ClassLoader cl) {
        final String PMS = "com.android.server.pm.PackageManagerService";
        final XC_MethodHook h = new PmsHook();

        // Android ≤12: flags = int
        try {
            XposedHelpers.findAndHookMethod(PMS, cl,
                    "getPackageInfo", String.class, int.class, int.class, h);
            XposedBridge.log(TAG + ": PMS(String,int,int) hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PMS(int) miss: " + t);
        }

        // Android 13+: flags = long
        try {
            XposedHelpers.findAndHookMethod(PMS, cl,
                    "getPackageInfo", String.class, long.class, int.class, h);
            XposedBridge.log(TAG + ": PMS(String,long,int) hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PMS(long) miss: " + t);
        }
    }

    // ── 2. Client-side fallback: in-process calls inside system_server ───────
    private static void hookApplicationPM(ClassLoader cl) {
        final String APM = "android.app.ApplicationPackageManager";
        final XC_MethodHook h = new AppPmHook();

        // API <33
        try {
            XposedHelpers.findAndHookMethod(APM, cl,
                    "getPackageInfo", String.class, int.class, h);
            XposedBridge.log(TAG + ": APM(int) hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": APM(int) miss: " + t);
        }

        // API ≥33: PackageInfoFlags wrapper
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Class<?> pif = XposedHelpers.findClass(
                        "android.content.pm.PackageManager$PackageInfoFlags", cl);
                XposedHelpers.findAndHookMethod(APM, cl,
                        "getPackageInfo", String.class, pif, h);
                XposedBridge.log(TAG + ": APM(PackageInfoFlags) hooked");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": APM(PackageInfoFlags) miss: " + t);
            }
        }

        // Archive queries (e.g. installers querying a downloaded APK)
        try {
            XposedHelpers.findAndHookMethod(APM, cl,
                    "getPackageArchiveInfo", String.class, int.class, h);
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook implementations
    // ─────────────────────────────────────────────────────────────────────────

    static class PmsHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(sInHook.get())) return;
            PackageInfo pi = (PackageInfo) param.getResult();
            if (pi == null || pi.packageName == null) return;
            // No PM reference available here; meta-data must be in applicationInfo
            process(pi, null);
        }
    }

    static class AppPmHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(sInHook.get())) return;
            PackageInfo pi = (PackageInfo) param.getResult();
            if (pi == null || pi.packageName == null) return;
            process(pi, (PackageManager) param.thisObject);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core logic
    // ─────────────────────────────────────────────────────────────────────────

    static void process(PackageInfo pi, PackageManager pm) {
        byte[] cert = resolveCert(pi, pm);
        if (cert == null || cert == NO_SPOOF) return;
        applySpoof(pi, cert);
    }

    /** Lazily creates (and hot-reloads) the XSharedPreferences view onto the companion app's config. */
    static XSharedPreferences companionPrefs() {
        XSharedPreferences prefs = sPrefs;
        if (prefs == null) {
            synchronized (SigSpoofHook.class) {
                prefs = sPrefs;
                if (prefs == null) {
                    prefs = new XSharedPreferences(COMPANION_PKG, COMPANION_PREFS);
                    sPrefs = prefs;
                }
            }
        }
        if (prefs.hasFileChanged()) {
            prefs.reload();
            // Rules changed on disk — forget every previous verdict so toggles/edits take effect.
            sCache.clear();
        }
        return prefs;
    }

    /**
     * Looks up an enabled rule for [pkg] written by the companion app's UI.
     * Returns null if there's no such rule (caller should fall back to manifest meta-data).
     */
    static byte[] resolveFromCompanionConfig(String pkg) {
        try {
            String json = companionPrefs().getString("rules_json", null);
            if (json == null) return null;

            JSONArray rules = new JSONArray(json);
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                if (!pkg.equals(rule.optString("packageName"))) continue;
                if (!rule.optBoolean("enabled", true)) return null;

                String b64 = rule.optString("certBase64", null);
                if (b64 == null || b64.isEmpty()) return null;
                return Base64.decode(b64, Base64.DEFAULT);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": companion config read failed: " + t);
        }
        return null;
    }

    /**
     * Returns the DER bytes to spoof for this package, or NO_SPOOF if nothing applies.
     * Companion app rules (configured via the UI) take priority; manifest
     * fake-signature meta-data is kept as a fallback for manual setups.
     */
    static byte[] resolveCert(PackageInfo pi, PackageManager pm) {
        String pkg = pi.packageName;

        // Fast path
        byte[] hit = sCache.get(pkg);
        if (hit != null) return hit;

        byte[] fromCompanion = resolveFromCompanionConfig(pkg);
        if (fromCompanion != null) {
            sCache.put(pkg, fromCompanion);
            XposedBridge.log(TAG + ": registered spoof for " + pkg + " (companion app rule)");
            return fromCompanion;
        }

        // Try meta-data already present in the PackageInfo (only when GET_META_DATA was requested)
        Bundle meta = (pi.applicationInfo != null) ? pi.applicationInfo.metaData : null;

        // Fallback: secondary PM query to fetch meta-data (guarded against recursion)
        if (meta == null && pm != null) {
            sInHook.set(true);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                if (ai != null) meta = ai.metaData;
            } catch (Throwable ignored) {
            } finally {
                sInHook.set(false);
            }
        }

        if (meta == null || !meta.containsKey(META_FAKE_SIG)) {
            sCache.put(pkg, NO_SPOOF);
            return NO_SPOOF;
        }

        String b64 = meta.getString(META_FAKE_SIG);
        if (b64 == null || b64.isEmpty()) {
            sCache.put(pkg, NO_SPOOF);
            return NO_SPOOF;
        }

        try {
            byte[] certBytes = Base64.decode(b64, Base64.DEFAULT);
            sCache.put(pkg, certBytes);
            XposedBridge.log(TAG + ": registered spoof for " + pkg);
            return certBytes;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bad base64 in " + pkg + ": " + t);
            sCache.put(pkg, NO_SPOOF);
            return NO_SPOOF;
        }
    }

    static void applySpoof(PackageInfo pi, byte[] certBytes) {
        try {
            Signature spoofSig = new Signature(certBytes);

            // Legacy signatures array (all Android versions)
            pi.signatures = new Signature[]{spoofSig};

            // Modern SigningInfo (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo si = buildSigningInfo(spoofSig, certBytes);
                if (si != null) pi.signingInfo = si;
            }

            XposedBridge.log(TAG + ": spoofed " + pi.packageName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applySpoof failed for " + pi.packageName + ": " + t);
        }
    }

    /**
     * Builds a SigningInfo wrapping a single spoofed cert.
     * Handles SigningDetails constructor changes across Android 9–15.
     */
    @SuppressWarnings({"JavaReflectionMemberAccess", "unchecked"})
    static SigningInfo buildSigningInfo(Signature spoofSig, byte[] certBytes) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(new ByteArrayInputStream(certBytes));

            ArraySet<Object> pks = new ArraySet<>();
            pks.add(cert.getPublicKey());

            Signature[] sigs = {spoofSig};
            final int V3 = 3; // SIGNING_BLOCK_V3

            // SigningDetails class location varies by Android version
            Class<?> sdCls = findFirstClass(
                    "android.content.pm.SigningDetails",                 // Android 13+
                    "android.content.pm.PackageParser$SigningDetails"    // Android 9–12
            );
            if (sdCls == null) {
                XposedBridge.log(TAG + ": SigningDetails class not found");
                return null;
            }

            // Try known constructor variants (ordered newest→oldest)
            Object sd = tryConstruct(sdCls,
                // Android 13+ / 14 / 15
                new Class[]{Signature[].class, int.class, ArraySet.class, Signature[].class},
                new Object[]{sigs, V3, pks, null},
                // Android 9 added pastSigningCertificatesFlags (int[])
                new Class[]{Signature[].class, int.class, ArraySet.class, Signature[].class, int[].class},
                new Object[]{sigs, V3, pks, null, null}
            );
            if (sd == null) {
                XposedBridge.log(TAG + ": no matching SigningDetails constructor");
                return null;
            }

            Constructor<SigningInfo> ctor = SigningInfo.class.getDeclaredConstructor(sdCls);
            ctor.setAccessible(true);
            return ctor.newInstance(sd);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildSigningInfo failed: " + t);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reflection helpers
    // ─────────────────────────────────────────────────────────────────────────

    static Class<?> findFirstClass(String... names) {
        for (String n : names) {
            try { return Class.forName(n); } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> T tryConstruct(Class<?> cls, Object... paramArgPairs) {
        // paramArgPairs: alternating (Class<?>[], Object[]) pairs
        for (int i = 0; i + 1 < paramArgPairs.length; i += 2) {
            Class<?>[] types = (Class<?>[]) paramArgPairs[i];
            Object[]   args  = (Object[])   paramArgPairs[i + 1];
            try {
                Constructor<?> c = cls.getDeclaredConstructor(types);
                c.setAccessible(true);
                return (T) c.newInstance(args);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": constructor attempt failed: " + t);
            }
        }
        return null;
    }
}
