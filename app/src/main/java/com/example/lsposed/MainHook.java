package com.example.lsposed;

import android.content.SharedPreferences;
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

    // SharedPreferences boolean keys that gate VIP features — force true
    private static final Set<String> VIP_BOOL_KEYS = new HashSet<>(Arrays.asList(
        "enable_auto_signature",
        "enable_bin_convert",
        "enable_oct_convert",
        "def_sign_key_warn",
        "apk_mcp_keep_v1_signature_data"
    ));

    // SharedPreferences int keys — override with permissive values
    // apk_mcp_session_limit: max MCP sessions (99), ekvc/gkvc: VIP version codes
    private static final Set<String> VIP_INT_KEYS_HIGH = new HashSet<>(Arrays.asList(
        "apk_mcp_session_limit",
        "ekvc",
        "gkvc"
    ));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        XposedBridge.log("[MTHook] Loaded into " + TARGET_PKG);

        hookSharedPrefs(lpparam.classLoader);
        hookVipGateReflective(lpparam.classLoader);
    }

    // ── Hook 1: SharedPreferencesImpl ─────────────────────────────────────────
    private void hookSharedPrefs(ClassLoader cl) {
        try {
            Class<?> spiClass = XposedHelpers.findClass(
                "android.app.SharedPreferencesImpl", cl);

            // getBoolean — return true for all known VIP keys
            XposedHelpers.findAndHookMethod(spiClass, "getBoolean",
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

            // getInt — return 99 for session/version code keys
            XposedHelpers.findAndHookMethod(spiClass, "getInt",
                String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        String key = (String) p.args[0];
                        if (VIP_INT_KEYS_HIGH.contains(key)) {
                            XposedBridge.log("[MTHook] INT override: " + key + " -> 99");
                            p.setResult(99);
                        }
                    }
                });

            XposedBridge.log("[MTHook] SharedPreferencesImpl hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("[MTHook] SharedPrefs hook failed: " + t);
        }
    }

    // ── Hook 2: VIP gate — find via class enumeration, hook no-arg boolean ────
    // MT Manager uses a custom ClassLoader; we scan all loaded classes at runtime
    // and force any no-arg boolean method in l.* / bin.mt.* packages to return true.
    private void hookVipGateReflective(ClassLoader cl) {
        // Delay until MT's custom ClassLoader has loaded its classes
        new Thread(() -> {
            try {
                Thread.sleep(6000);
                XposedBridge.log("[MTHook] Starting VIP gate class scan...");

                // Walk the custom ClassLoader chain
                ClassLoader current = cl;
                while (current != null) {
                    try {
                        java.lang.reflect.Field field =
                            current.getClass().getDeclaredField("mCookie");
                        // If field exists, this may be MT's BaseDexClassLoader
                        field.setAccessible(true);
                        XposedBridge.log("[MTHook] Found loader: " + current.getClass().getName());
                    } catch (NoSuchFieldException ignored) {}
                    current = current.getParent();
                }

                // Hook the well-known custom pref class (confirmed from DEX + Frida analysis)
                // Class name: l.ܺ᫴ۘ — non-encrypted wrapper
                String safePrefClass = "l.ܺ᫴ۘ";
                try {
                    Class<?> sp = cl.loadClass(safePrefClass);
                    for (java.lang.reflect.Method m : sp.getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (m.getReturnType() == boolean.class && params.length == 2
                            && params[0] == String.class && params[1] == boolean.class) {
                            final String mName = m.getName();
                            XposedHelpers.findAndHookMethod(sp, mName,
                                String.class, boolean.class, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam p) {
                                        String key = (String) p.args[0];
                                        if (VIP_BOOL_KEYS.contains(key)) {
                                            XposedBridge.log("[MTHook] CPREF override: " + key + " -> true");
                                            p.setResult(true);
                                        }
                                    }
                                });
                            XposedBridge.log("[MTHook] Hooked custom pref method: " + mName);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    XposedBridge.log("[MTHook] Custom pref class not found (may differ in this build): " + e.getMessage());
                }

                XposedBridge.log("[MTHook] VIP gate scan complete");
            } catch (Throwable t) {
                XposedBridge.log("[MTHook] VIP gate scan error: " + t);
            }
        }, "MTHook-VipScan").start();
    }
}
