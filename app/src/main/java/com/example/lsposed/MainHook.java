package com.example.lsposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "bin.mt.plus";

    // Confirmed from Frida trace: these bool keys gate VIP features, read false on free build
    private static final Set<String> VIP_BOOL_KEYS = new HashSet<>(Arrays.asList(
        "enable_auto_signature",
        "enable_bin_convert",
        "enable_oct_convert",
        "def_sign_key_warn",
        "apk_mcp_keep_v1_signature_data"
    ));

    // Confirmed from trace: gkvc=0 ekvc=0 on free; non-zero = VIP activated
    private static final Set<String> VIP_INT_KEYS = new HashSet<>(Arrays.asList(
        "gkvc",
        "ekvc",
        "apk_mcp_session_limit"
    ));

    // a_end_time = 0 on free account → VIP expiry unix ms; set to year 2099
    private static final Set<String> VIP_LONG_KEYS = new HashSet<>(Arrays.asList(
        "a_end_time"
    ));

    // Far-future timestamp: 2099-01-01 00:00:00 UTC in ms
    private static final long VIP_EXPIRY_MS = 4070908800000L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        XposedBridge.log("[MTHook] Injected into " + TARGET_PKG);
        hookSharedPrefs(lpparam.classLoader);
    }

    private void hookSharedPrefs(ClassLoader cl) {
        try {
            Class<?> spi = XposedHelpers.findClass("android.app.SharedPreferencesImpl", cl);

            // ── getLong: a_end_time → far future (VIP expiry) ──────────────
            XposedHelpers.findAndHookMethod(spi, "getLong",
                String.class, long.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        String key = (String) p.args[0];
                        if (VIP_LONG_KEYS.contains(key)) {
                            XposedBridge.log("[MTHook] LONG override: " + key + " -> " + VIP_EXPIRY_MS);
                            p.setResult(VIP_EXPIRY_MS);
                        }
                    }
                });

            // ── getInt: gkvc / ekvc → non-zero VIP version code ────────────
            XposedHelpers.findAndHookMethod(spi, "getInt",
                String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        String key = (String) p.args[0];
                        if (VIP_INT_KEYS.contains(key)) {
                            int override = key.equals("apk_mcp_session_limit") ? 99 : 26070303;
                            XposedBridge.log("[MTHook] INT override: " + key + " -> " + override);
                            p.setResult(override);
                        }
                    }
                });

            // ── getBoolean: force true for all VIP feature gates ────────────
            XposedHelpers.findAndHookMethod(spi, "getBoolean",
                String.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        String key = (String) p.args[0];
                        if (VIP_BOOL_KEYS.contains(key)) {
                            XposedBridge.log("[MTHook] BOOL override: " + key + " -> true");
                            p.setResult(true);
                        }
                    }
                });

            XposedBridge.log("[MTHook] All SharedPrefs hooks installed OK");
        } catch (Throwable t) {
            XposedBridge.log("[MTHook] Hook setup failed: " + t);
        }
    }
}
