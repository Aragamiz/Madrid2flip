package com.collasong.madridsubscreen;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小米 18 (madrid) 背屏中心移植模块入口（MIX Flip 2 专用）。
 */
public final class MadridSubscreenModule implements IXposedHookLoadPackage {

    private static final String TAG = "MadridSubscreen";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !lpparam.isFirstApplication) {
            return;
        }
        String pkg = lpparam.packageName;
        // 不同 LSPosed 版本对 system_server 的 packageName 上报不一致：
        // 有的报 "android"，有的报 "system"。两者都要注入，否则系统层 Hook 全部失效。
        if ("android".equals(pkg) || "system".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in system_server (pkg=" + pkg + ")");
            UnfoldOuterDisplayHook.install(lpparam.classLoader);
        } else if ("com.xiaomi.subscreencenter".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.xiaomi.subscreencenter");
            SubscreenCenterHook.installSubscreenCenter(lpparam.classLoader);
        } else if ("com.android.settings".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.android.settings");
            SubscreenCenterHook.installSettings(lpparam.classLoader);
        } else if ("com.miui.securitycore".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.miui.securitycore");
            SubscreenCenterHook.installSecurityCore(lpparam.classLoader);
        } else if ("com.android.thememanager".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.android.thememanager");
            SubscreenCenterHook.installThemeManager(lpparam.classLoader);
        } else if ("com.miui.fliphome".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.miui.fliphome");
            SubscreenCenterHook.installFlipHome(lpparam.classLoader);
        } else if ("com.android.systemui".equals(pkg)) {
            XposedBridge.log(TAG + ": loaded in com.android.systemui");
            SubscreenCenterHook.installSystemUI(lpparam.classLoader);
        }
    }
}
