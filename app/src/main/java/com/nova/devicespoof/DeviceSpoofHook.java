package com.nova.devicespoof;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.nova.devicespoof.util.PropParser;

/**
 * Nova Device Spoof -- LSPosed module
 *
 * Makes a chosen set of apps believe they're running on a newer/different device by
 * overriding the values they read for android.os.Build / android.os.Build.VERSION and
 * OEM "floating feature" flags (the mechanism Samsung/OneUI apps use to gate features
 * on model/region). Nothing on the real device is changed:
 *
 *  - The real /system/build.prop and /system/etc/floating_feature.xml are never touched,
 *    so there is no bootloop risk and no interaction with dm-verity / AVB.
 *  - The override only exists inside the memory of the target app's own process, applied
 *    the moment that process starts (handleLoadPackage runs before the app's own code).
 *  - Only apps explicitly enabled from the companion app are affected. Every other app,
 *    including system_server, is left completely alone.
 *
 * Configure everything from the companion app:
 *  1. Paste a build.prop-style profile (KEY=VALUE per line) and/or a floating_feature.xml
 *     dump into the two fields.
 *  2. Pick which installed apps should see that profile.
 *  3. Enable this module's scope for those same apps in LSPosed Manager.
 */
public class DeviceSpoofHook implements IXposedHookLoadPackage {

    static final String TAG = "NovaDeviceSpoof";

    static final String COMPANION_PKG = "com.nova.devicespoof";
    static final String COMPANION_PREFS = "spoof_config";

    // On enforcing SELinux (every Samsung/OneUI device), the companion app's shared_prefs
    // file lives under its own "app_data_file" category, which the target app's process is
    // denied from opening directly even when Unix permissions are wide open -- this is why
    // XSharedPreferences(COMPANION_PKG, COMPANION_PREFS) alone reliably reads back null on
    // these devices. SpoofPrefsStore additionally root-copies the same prefs file out to
    // this neutral, non-app-owned path after every save; reading from here instead sidesteps
    // the per-app SELinux category check entirely. Fall back to the direct path only if the
    // mirror hasn't been created yet (e.g. companion app was never granted root).
    static final String ROOT_MIRROR_PATH = "/data/local/tmp/nova_device_spoof_prefs.xml";

    static volatile XSharedPreferences sPrefs;
    static volatile java.io.File sPrefsSource;

    /** Package name → true/false, cached so we only parse prefs once per process. */
    static final ConcurrentHashMap<String, Boolean> sEnabledCache = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Never touch our own companion app or the system server -- this module only
        // ever spoofs regular apps, one process at a time.
        if (COMPANION_PKG.equals(lpparam.packageName)) return;
        if ("android".equals(lpparam.packageName)) return;

        // Always log that we were even asked about this package, before the enable check --
        // this is the single most useful line for diagnosing "nothing happens": if you don't
        // see this at all for a target app, LSPosed isn't injecting into it (check module
        // scope / Zygisk denylist); if you see it with enabled=false, the companion app's
        // saved rule doesn't match this package or wasn't saved.
        boolean enabled = isEnabledForPackage(lpparam.packageName);
        XposedBridge.log(TAG + ": handleLoadPackage " + lpparam.packageName + " enabled=" + enabled);
        if (!enabled) return;

        XposedBridge.log(TAG + ": profile enabled for " + lpparam.packageName + ", applying spoof");

        Map<String, String> buildProps = PropParser.parseBuildProp(currentProfileBuildProp());
        Map<String, String> floatingFeatures = PropParser.parseFloatingFeatures(currentProfileFloatingFeatures());

        if (!buildProps.isEmpty()) {
            applyBuildFieldOverrides(buildProps);
            hookSystemProperties(lpparam.classLoader, buildProps);
        }
        if (!floatingFeatures.isEmpty()) {
            hookFloatingFeatures(lpparam.classLoader, floatingFeatures);
        }
    }

    // ── Companion config access ──────────────────────────────────────────────

    static XSharedPreferences companionPrefs() {
        java.io.File mirrorFile = new java.io.File(ROOT_MIRROR_PATH);
        boolean mirrorReadable = mirrorFile.exists() && mirrorFile.canRead();
        java.io.File wantedSource = mirrorReadable ? mirrorFile : null; // null == direct COMPANION_PKG path

        XSharedPreferences prefs = sPrefs;
        boolean sourceMismatch = !java.util.Objects.equals(sPrefsSource, wantedSource);
        if (prefs == null || sourceMismatch) {
            synchronized (DeviceSpoofHook.class) {
                if (sPrefs == null || !java.util.Objects.equals(sPrefsSource, wantedSource)) {
                    if (mirrorReadable) {
                        XposedBridge.log(TAG + ": reading companion config from root-mirrored file " + ROOT_MIRROR_PATH);
                        prefs = new XSharedPreferences(mirrorFile);
                    } else {
                        XposedBridge.log(TAG + ": root mirror unavailable at " + ROOT_MIRROR_PATH
                                + " (exists=" + mirrorFile.exists() + ", canRead=" + mirrorFile.canRead()
                                + ") -- falling back to direct XSharedPreferences(" + COMPANION_PKG + ", " + COMPANION_PREFS + ")");
                        prefs = new XSharedPreferences(COMPANION_PKG, COMPANION_PREFS);
                    }
                    sPrefs = prefs;
                    sPrefsSource = wantedSource;
                    sEnabledCache.clear();
                }
                prefs = sPrefs;
            }
        }
        if (prefs.hasFileChanged()) {
            prefs.reload();
            sEnabledCache.clear();
        }
        return prefs;
    }

    static boolean isEnabledForPackage(String pkg) {
        Boolean cached = sEnabledCache.get(pkg);
        if (cached != null) return cached;

        boolean enabled = false;
        try {
            String json = companionPrefs().getString("rules_json", null);
            if (json == null) {
                // Either "Choose apps" was never saved yet, or XSharedPreferences couldn't
                // read the companion app's prefs file at all (permissions/SELinux). Both look
                // identical from here, so this line alone tells you *something* is missing.
                XposedBridge.log(TAG + ": rules_json is null (no profile saved yet, or prefs unreadable)");
            } else {
                JSONArray rules = new JSONArray(json);
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rule = rules.getJSONObject(i);
                    if (pkg.equals(rule.optString("packageName")) && rule.optBoolean("enabled", false)) {
                        enabled = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed reading rules for " + pkg + ": " + t);
        }
        sEnabledCache.put(pkg, enabled);
        return enabled;
    }

    static String currentProfileBuildProp() {
        return companionPrefs().getString("build_prop_text", "");
    }

    static String currentProfileFloatingFeatures() {
        return companionPrefs().getString("floating_features_xml", "");
    }

    // ── 1. android.os.Build / Build.VERSION field overrides ──────────────────

    /**
     * Maps well-known build.prop keys onto the public Build / Build.VERSION fields apps
     * actually read. Anything in the pasted profile that isn't one of these keys still
     * gets served through the SystemProperties hook below.
     */
    static void applyBuildFieldOverrides(Map<String, String> props) {
        setBuildField("MODEL", props.get("ro.product.model"));
        setBuildField("MANUFACTURER", props.get("ro.product.manufacturer"));
        setBuildField("BRAND", props.get("ro.product.brand"));
        setBuildField("DEVICE", props.get("ro.product.device"));
        setBuildField("PRODUCT", props.get("ro.product.name"));
        setBuildField("HARDWARE", props.get("ro.hardware"));
        setBuildField("BOARD", props.get("ro.product.board"));
        setBuildField("FINGERPRINT", props.get("ro.build.fingerprint"));
        setBuildField("ID", props.get("ro.build.id"));
        setBuildField("DISPLAY", props.get("ro.build.display.id"));
        setBuildField("TAGS", props.get("ro.build.tags"));
        setBuildField("TYPE", props.get("ro.build.type"));

        setVersionField("RELEASE", props.get("ro.build.version.release"));
        setVersionField("SDK", props.get("ro.build.version.sdk"));
        setVersionField("INCREMENTAL", props.get("ro.build.version.incremental"));
        setVersionField("CODENAME", props.get("ro.build.version.codename"));
        setVersionField("SECURITY_PATCH", props.get("ro.build.version.security_patch"));

        String sdkInt = props.get("ro.build.version.sdk");
        if (sdkInt != null) {
            try {
                setStaticIntField(Build.VERSION.class, "SDK_INT", Integer.parseInt(sdkInt.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    static void setBuildField(String field, String value) {
        if (value == null) return;
        setStaticStringField(Build.class, field, value);
    }

    static void setVersionField(String field, String value) {
        if (value == null) return;
        setStaticStringField(Build.VERSION.class, field, value);
    }

    static void setStaticStringField(Class<?> cls, String fieldName, String value) {
        try {
            Field f = cls.getField(fieldName);
            f.setAccessible(true);
            XposedHelpers.setStaticObjectField(cls, fieldName, value);
            XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + fieldName + " -> " + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not set " + cls.getSimpleName() + "." + fieldName + ": " + t);
        }
    }

    static void setStaticIntField(Class<?> cls, String fieldName, int value) {
        try {
            XposedHelpers.setStaticIntField(cls, fieldName, value);
            XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + fieldName + " -> " + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not set " + cls.getSimpleName() + "." + fieldName + ": " + t);
        }
    }

    // ── 2. android.os.SystemProperties -- catches raw getprop() reads ────────

    static void hookSystemProperties(ClassLoader cl, Map<String, String> props) {
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, new PropGetHook(props, null));
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, String.class, new PropGetHook(props, null));
            XposedBridge.log(TAG + ": SystemProperties.get hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SystemProperties.get hook failed: " + t);
        }
    }

    static class PropGetHook extends XC_MethodHook {
        private final Map<String, String> props;

        PropGetHook(Map<String, String> props, Void unused) {
            this.props = props;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
            String key = (String) param.args[0];
            String spoofed = props.get(key);
            if (spoofed != null) {
                param.setResult(spoofed);
            }
        }
    }

    // ── 3. OEM floating-feature / CSC flags (Samsung/OneUI-style feature gating) ─
    //
    // Verified 2026-07 by decompiling a real Samsung Gallery build (apktool) rather than
    // guessing: the app never calls static getString()/getInteger() directly on a
    // "FloatingFeatureImpl" class. Two real code paths exist, and we hook both so this
    // works whether the ROM has the full Samsung framework or not:
    //
    //  A. Modern OneUI devices (Sem80ApiCompatImpl-style path):
    //     com.samsung.android.feature.SemFloatingFeature.getInstance() returns a
    //     singleton; callers then use INSTANCE methods getBoolean(String)/getInt(String)/
    //     getString(String). com.samsung.android.feature.SemCscFeature works the same way
    //     but its getters take a default value: getBoolean(String,boolean)/getString(String,String).
    //     We hook the instance methods directly -- Xposed intercepts every call regardless
    //     of which object instance made it, so we don't need to touch getInstance().
    //
    //  B. Fallback path used when (A) throws/isn't available (Sesl reflector path, seen on
    //     GED/AOSP-leaning builds): com.samsung.sesl.feature.SemFloatingFeature /
    //     com.samsung.sesl.feature.SemCscFeature expose STATIC hidden_getString(key, default)
    //     methods (the "hidden_" prefix is Samsung's own naming convention for the reflective
    //     accessor, not something we need to strip).
    //
    // A missing class/method on a given ROM is expected and silently skipped -- not every
    // device is Samsung/OneUI, and not every OneUI version has both paths.

    static void hookFloatingFeatures(ClassLoader cl, Map<String, String> features) {
        // Path A: instance-based Sem*Feature singletons.
        hookInstanceFeatureGetter(cl, "com.samsung.android.feature.SemFloatingFeature",
                "getBoolean", features, boolean.class, String.class);
        hookInstanceFeatureGetter(cl, "com.samsung.android.feature.SemFloatingFeature",
                "getInt", features, int.class, String.class);
        hookInstanceFeatureGetter(cl, "com.samsung.android.feature.SemFloatingFeature",
                "getString", features, String.class, String.class);

        hookInstanceFeatureGetter(cl, "com.samsung.android.feature.SemCscFeature",
                "getBoolean", features, boolean.class, String.class, boolean.class);
        hookInstanceFeatureGetter(cl, "com.samsung.android.feature.SemCscFeature",
                "getString", features, String.class, String.class, String.class);

        // Path B: static Sesl reflector-backed fallback, always takes (key, default).
        hookStaticHiddenGetter(cl, "com.samsung.sesl.feature.SemFloatingFeature",
                "hidden_getString", features, String.class, String.class, String.class);
        hookStaticHiddenGetter(cl, "com.samsung.sesl.feature.SemCscFeature",
                "hidden_getString", features, String.class, String.class, String.class);
    }

    /** Hooks an instance getter like SemFloatingFeature#getString(String) / #getBoolean(String, boolean). */
    static void hookInstanceFeatureGetter(ClassLoader cl, String className, String methodName,
                                           Map<String, String> features, Class<?> returnType, Class<?>... paramTypes) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            Method m = cls.getMethod(methodName, paramTypes);
            XposedBridge.hookMethod(m, featureHook(features, returnType));
            XposedBridge.log(TAG + ": hooked " + className + "#" + methodName + paramTypeSuffix(paramTypes));
        } catch (Throwable ignored) {
            // Class or this getter overload not present on this ROM/OneUI version.
        }
    }

    /** Hooks a static "hidden_getX(key, default)" getter, as used by the Sesl reflector fallback path. */
    static void hookStaticHiddenGetter(ClassLoader cl, String className, String methodName,
                                        Map<String, String> features, Class<?> returnType, Class<?>... paramTypes) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            Method m = cls.getMethod(methodName, paramTypes);
            XposedBridge.hookMethod(m, featureHook(features, returnType));
            XposedBridge.log(TAG + ": hooked " + className + "#" + methodName + paramTypeSuffix(paramTypes));
        } catch (Throwable ignored) {
            // Not present on this ROM -- expected on non-Samsung or newer/older OneUI builds.
        }
    }

    private static String paramTypeSuffix(Class<?>[] paramTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(paramTypes[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /**
     * The key (first String argument) is always the feature name being looked up; every
     * variant above -- instance or static, with or without a default-value argument --
     * follows that convention, so one hook body covers all of them.
     */
    private static XC_MethodHook featureHook(Map<String, String> features, Class<?> returnType) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String key = null;
                for (Object arg : param.args) {
                    if (arg instanceof String) {
                        key = (String) arg;
                        break;
                    }
                }
                if (key == null) return;
                String spoofed = features.get(key);
                if (spoofed == null) return; // not in our profile -- let the real value pass through

                try {
                    if (returnType == int.class || returnType == Integer.class) {
                        param.setResult(Integer.parseInt(spoofed.trim()));
                    } else if (returnType == boolean.class || returnType == Boolean.class) {
                        param.setResult(Boolean.parseBoolean(spoofed.trim()) || "1".equals(spoofed.trim()));
                    } else {
                        param.setResult(spoofed);
                    }
                } catch (NumberFormatException ignored) {
                    // Spoofed value didn't parse as the expected numeric type -- leave the
                    // real call result in place rather than crash the target app.
                }
            }
        };
    }
}
