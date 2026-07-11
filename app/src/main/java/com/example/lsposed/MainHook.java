package com.example.lsposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"bin.mt.plus".equals(lpparam.packageName)) return;
        XposedBridge.log("[MTHook] Loaded — hooks disabled pending Frida analysis");
    }
}
