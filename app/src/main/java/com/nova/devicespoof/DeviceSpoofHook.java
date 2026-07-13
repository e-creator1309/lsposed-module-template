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
import de.robv.android.xposed.XC_MethodReplacement;
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

    static volatile XSharedPreferences sPrefs;

    /** Package name → true/false, cached so we only parse prefs once per process. */
    static final ConcurrentHashMap<String, Boolean> sEnabledCache = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Never touch our own companion app or the system server -- this module only
        // ever spoofs regular apps, one process at a time.
        if (COMPANION_PKG.equals(lpparam.packageName)) return;
        if ("android".equals(lpparam.packageName)) return;

        if (!isEnabledForPackage(lpparam.packageName)) return;

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
        XSharedPreferences prefs = sPrefs;
        if (prefs == null) {
            synchronized (DeviceSpoofHook.class) {
                prefs = sPrefs;
                if (prefs == null) {
                    prefs = new XSharedPreferences(COMPANION_PKG, COMPANION_PREFS);
                    sPrefs = prefs;
                }
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
            if (json != null) {
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

    // ── 3. OEM floating-feature flags (Samsung/OneUI-style feature gating) ───

    static void hookFloatingFeatures(ClassLoader cl, Map<String, String> features) {
        // Different OneUI versions expose this under slightly different class names.
        // Try each; a missing class on a given ROM is expected and silently skipped.
        String[] candidates = {
                "com.samsung.android.feature.FloatingFeatureImpl",
                "com.samsung.android.feature.SemFloatingFeature",
                "com.samsung.android.feature.SemCscFeature"
        };
        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, cl);
                hookFeatureGetter(cls, "getString", features, String.class);
                hookFeatureGetter(cls, "getInteger", features, String.class);
                hookFeatureGetter(cls, "getEnableStatus", features, String.class);
                XposedBridge.log(TAG + ": floating-feature hooks attached on " + className);
            } catch (Throwable ignored) {
                // Class not present on this ROM -- normal, not every device is Samsung/OneUI.
            }
        }
    }

    static void hookFeatureGetter(Class<?> cls, String methodName, Map<String, String> features, Class<?>... paramTypes) {
        try {
            Method m = cls.getMethod(methodName, paramTypes);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String key = (String) param.args[0];
                    String spoofed = features.get(key);
                    if (spoofed == null) return;

                    Class<?> returnType = m.getReturnType();
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
            });
        } catch (NoSuchMethodException ignored) {
            // This particular getter overload doesn't exist on this ROM's implementation.
        }
    }
}
