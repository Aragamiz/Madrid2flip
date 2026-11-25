package com.collasong.madridsubscreen;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 展开态自定义点亮外屏（system_server）：
 * 1. 放行 displayId=1 启动 com.xiaomi.subscreencenter；
 * 2. 修复外屏独立自动亮度与手动亮度调节（解除 DisplayPowerController(1) 对 leadDisplayId=0 的强绑定）；
 * 3. 合盖（baseState=0/1）自动退出 state=5 恢复 Flip 2 原生外屏；
 * 4. 支持「展开自动开启背屏」（Settings.Global madrid_unfold_auto_open=1）与广播一键开关。
 */
public final class UnfoldOuterDisplayHook {

    private static final String TAG = "MadridSubscreen/Unfold";
    private static final String PKG_SUBSCREEN = "com.xiaomi.subscreencenter";
    private static final String CLS_SUBSCREEN_LAUNCHER = "com.xiaomi.subscreencenter.SubScreenLauncher";
    public static final String ACTION_EXIT_REAR_SCREEN = "com.collasong.madridsubscreen.ACTION_EXIT_REAR_SCREEN";
    public static final String ACTION_ENTER_REAR_SCREEN = "com.collasong.madridsubscreen.ACTION_ENTER_REAR_SCREEN";
    public static final String ACTION_FINISH_REAR_LAUNCHER = "com.collasong.madridsubscreen.ACTION_FINISH_REAR_LAUNCHER";
    public static final String EXTRA_SILENT_FINISH = "silent_finish";
    public static final String ACTION_FOLDED_REAR_OVERLAY = "com.collasong.madridsubscreen.ACTION_FOLDED_REAR_OVERLAY";
    public static final String EXTRA_HIDE_OVERLAY = "hide_overlay";
    public static final String BACK_TAP_ACTION_TOGGLE_REAR = "madrid_toggle_rear_screen";
    public static final String KEY_UNFOLD_AUTO_OPEN = "madrid_unfold_auto_open";
    public static final String KEY_HALF_OPEN_REAR = "madrid_half_open_rear";
    public static final String KEY_FOLD_AUTO_CLOSE = "madrid_fold_auto_close";
    public static final String KEY_REAR_BRIGHTNESS_CAP = "madrid_rear_brightness_cap";
    public static final String KEY_REAR_TIMEOUT_SEC = "madrid_rear_timeout_sec";
    public static final String KEY_REAR_TABLE_OFF = "madrid_rear_table_off";
    public static final String KEY_REAR_FULLSCREEN = "madrid_rear_fullscreen";
    public static final String KEY_REAR_BURNIN_MODE = "madrid_rear_burnin_mode";
    public static final String KEY_REAR_AUTO_BRIGHT_SCALE = "madrid_rear_auto_bright_scale";
    public static final String KEY_REAR_AUTO_ROTATE = "madrid_rear_auto_rotate";
    public static final String KEY_FOLDED_FINGERPRINT_REAR = "madrid_folded_fingerprint_rear";
    public static final String KEY_FOLDED_EXIT_TO_LOCK = "madrid_folded_exit_to_lock";
    public static final String KEY_REAR_SWIPE_CAMERA = "madrid_rear_swipe_camera";
    /** 背屏触摸锁定：展开态外屏作为背屏显示时吞掉外屏全部触摸（0/1，默认 0） */
    public static final String KEY_REAR_TOUCH_LOCK = "madrid_rear_touch_lock";
    /** 视频壁纸循环播放：动态壁纸播完立即从头重播，不再停帧（0/1，默认 0） */
    public static final String KEY_REAR_VIDEO_LOOP = "madrid_rear_video_loop";
    public static final String EXTRA_FOLDED_MODE = "madrid_folded_mode";

    private static final float FLIP2_MIN_BRIGHTNESS = 0.000854597f;
    private static final float FLIP2_NORMAL_MAX_BRIGHTNESS = 0.416493713f;

    private static volatile Object sDeviceStateService;
    private static volatile boolean sFoldedRearActive = false;
    private static volatile boolean sWatchOverlayForcedZero = false;
    private static volatile int sCurrentBaseState = -1;
    private static Runnable sPendingEnterRunnable = null;
    private static Runnable sPendingLaunchRunnable = null;
    private static volatile Context sSystemContext;
    private static volatile ClassLoader sSystemClassLoader;
    private static volatile boolean sReceiverRegistered;
    /** 开机后首次进入背屏时复位一次背屏中心进程，确保 LSPosed 作用域已就绪、新增 Hook 一定被注入 */
    private static volatile boolean sSubscreenResetDone = false;
    /** 背屏触摸锁定开关的短缓存，避免焦点切换高频路径上反复读 Settings */
    private static volatile long sRearTouchLockCheckedAt = 0L;
    private static volatile boolean sRearTouchLockCached = false;
    /** 焦点拦截命中日志的限频时间戳 */
    private static volatile long sRearFocusGuardLogAt = 0L;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private UnfoldOuterDisplayHook() {
    }

    public static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        sSystemClassLoader = cl;
        hookAllowDisplay1Launch(cl);
        hookSubDisplayIndependentBrightness(cl);
        hookDeviceStateLifecycle(cl);
        hookBackTapShortcut(cl);
        hookHideFoldedWidgets(cl);
        hookRearTouchFocusGuard(cl);
    }

    /**
     * 背屏触摸防误触（输入法场景）：阻止焦点切换到背屏 display。
     * <p>
     * 现象：内屏展开、正在输入时触摸背屏，内屏输入法面板会被收起。
     * 根因：触摸事件由 InputDispatcher 命中背屏窗口后，WMS 会把焦点切到背屏
     * (DisplayContent.displayId=1)；IMMS 检测到 top focused display 变化，重新计算
     * IME 目标 display 并收起内屏输入法。app 层的 dispatchTouchEvent Hook 发生在
     * 焦点切换之后，无法阻止这一步。
     * <p>
     * 做法：在焦点切换的源头 DisplayContent.updateFocusedWindowLocked 的 before 中，
     * 若该 DisplayContent 是背屏(displayId=1)、设备处于展开态(baseState 非 0/1)
     * 且防误触开关开启，直接 setResult(false) 取消本次焦点切换。
     * Xposed 返回 false 是系统已有的正常提前返回路径（当前焦点仍 focusable 时系统
     * 本身就返回 false），不会破坏 WMS 状态，也不会触发 bootloop。
     * 因为焦点从未交给背屏，输入法目标 display 不变，内屏输入法保持；同时背屏窗口
     * 依旧照常显示、只是拿不到输入焦点，正符合"防误触"语义。
     * 关闭开关或合盖时条件不成立，Hook 完全不干预，行为与原生一致。
     */
    private static void hookRearTouchFocusGuard(ClassLoader cl) {
        try {
            Class<?> dcCls = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(dcCls, "updateFocusedWindowLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object dc = param.thisObject;
                        if (dc == null) {
                            return;
                        }
                        // 只针对背屏所在的 DisplayContent
                        int displayId;
                        try {
                            displayId = XposedHelpers.getIntField(dc, "mDisplayId");
                        } catch (Throwable ignored) {
                            return;
                        }
                        if (displayId != 1) {
                            return;
                        }
                        // 合盖时 display 1 是主外屏，必须允许其正常获取焦点
                        int base = sCurrentBaseState;
                        if (base == 0 || base == 1) {
                            return;
                        }
                        // 防误触开关未开启则不干预（读 Settings 带短缓存）
                        boolean locked = isRearTouchLockedFast();
                        if (!locked) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - sRearFocusGuardLogAt > 10000L) {
                            sRearFocusGuardLogAt = now;
                            XposedBridge.log(TAG + ": rear touch guard BLOCKED focus switch to display 1");
                        }
                        param.setResult(false);
                    } catch (Throwable t) {
                        // 任何异常都放过，绝不影响焦点系统稳定性
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked DisplayContent.updateFocusedWindowLocked for rear touch focus guard");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook rear touch focus guard failed: " + t);
        }
    }

    /** 带 250ms 缓存的防误触开关读取，专供焦点切换高频路径使用。 */
    private static boolean isRearTouchLockedFast() {
        long now = System.currentTimeMillis();
        if (now - sRearTouchLockCheckedAt <= 250L) {
            return sRearTouchLockCached;
        }
        Context ctx = sSystemContext;
        if (ctx == null) {
            return false;
        }
        try {
            sRearTouchLockCached = Settings.Global.getInt(ctx.getContentResolver(),
                    KEY_REAR_TOUCH_LOCK, 0) == 1;
        } catch (Throwable ignored) {
            sRearTouchLockCached = false;
        }
        sRearTouchLockCheckedAt = now;
        return sRearTouchLockCached;
    }

    private static void hookHideFoldedWidgets(ClassLoader cl) {
        try {
            Class<?> wsCls = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedBridge.hookAllMethods(wsCls, "isVisibleByPolicy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sFoldedRearActive) {
                        return;
                    }
                    try {
                        Object tag = XposedHelpers.callMethod(param.thisObject, "getWindowTag");
                        String title = tag != null ? String.valueOf(tag) : "";
                        if ("WatchOverlayGroupView".equals(title) || title.contains("WatchOverlayGroupView")) {
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked WindowState.isVisibleByPolicy to hide WatchOverlayGroupView during folded rear screen");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookHideFoldedWidgets failed: " + t);
        }
        try {
            Class<?> animCls = XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator", cl);
            XposedBridge.hookAllMethods(animCls, "prepareSurfaceLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sFoldedRearActive && !sWatchOverlayForcedZero) {
                        return;
                    }
                    try {
                        Object win = XposedHelpers.getObjectField(param.thisObject, "mWin");
                        if (win == null) {
                            return;
                        }
                        Object tag = XposedHelpers.callMethod(win, "getWindowTag");
                        String title = tag != null ? String.valueOf(tag) : "";
                        if ("WatchOverlayGroupView".equals(title) || title.contains("WatchOverlayGroupView")) {
                            if (sFoldedRearActive) {
                                XposedHelpers.setFloatField(param.thisObject, "mAlpha", 0f);
                                XposedHelpers.setFloatField(param.thisObject, "mShownAlpha", 0f);
                                sWatchOverlayForcedZero = true;
                            } else {
                                XposedHelpers.setFloatField(param.thisObject, "mAlpha", 1f);
                                XposedHelpers.setFloatField(param.thisObject, "mShownAlpha", 1f);
                                sWatchOverlayForcedZero = false;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked WindowStateAnimator.prepareSurfaceLocked to force alpha=0 on WatchOverlayGroupView");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook WindowStateAnimator.prepareSurfaceLocked failed: " + t);
        }
        // 拦截 SubScreenLauncher 的白色 StartingWindow / SplashScreen，彻底消除合盖开启时的瞬间白屏
        try {
            Class<?> arCls = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(arCls, "showStartingWindow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sFoldedRearActive) {
                        return;
                    }
                    try {
                        String rec = String.valueOf(param.thisObject);
                        if (rec.contains(PKG_SUBSCREEN)) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked ActivityRecord.showStartingWindow to block white splash on SubScreenLauncher");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ActivityRecord.showStartingWindow failed: " + t);
        }
    }

    private static void notifyFlipHomeOverlayHide(boolean hide) {
        Context ctx = sSystemContext;
        if (ctx == null) {
            return;
        }
        try {
            Intent it = new Intent(ACTION_FOLDED_REAR_OVERLAY);
            it.setPackage("com.miui.fliphome");
            it.putExtra(EXTRA_HIDE_OVERLAY, hide);
            it.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 接入系统「轻敲背面（两下/三下）」快捷方式：当用户选择「开启/关闭小米18背屏」时，
     * 在非合盖状态下切换 state=5 背屏开关。
     */
    private static void hookBackTapShortcut(ClassLoader cl) {
        try {
            Class<?> btCls = XposedHelpers.findClass("com.miui.server.input.MiuiBackTapGestureService", cl);
            XposedBridge.hookAllMethods(btCls, "postShortcutFunction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String func = (String) param.args[0];
                    if (!BACK_TAP_ACTION_TOGGLE_REAR.equals(func)) {
                        return;
                    }
                    // postShortcutFunction 返回原始 boolean，严禁 setResult(null) 否则自动拆箱 NPE
                    param.setResult(Boolean.TRUE);
                    sMainHandler.post(() -> toggleRearScreenIfUnfolded("back_tap"));
                }
            });
            XposedBridge.log(TAG + ": hooked MiuiBackTapGestureService.postShortcutFunction for " + BACK_TAP_ACTION_TOGGLE_REAR);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook MiuiBackTapGestureService failed: " + t);
        }

        // 统一拦截 HyperOS 所有快捷手势执行入口（含「轻拍两下指纹键 fingerprint_double_tap」等）
        try {
            Class<?> sauCls = XposedHelpers.findClass("com.miui.server.input.util.ShortCutActionsUtils", cl);
            XposedBridge.hookAllMethods(sauCls, "triggerFunction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String func = (String) param.args[0];
                    if (!BACK_TAP_ACTION_TOGGLE_REAR.equals(func)) {
                        return;
                    }
                    String action = (param.args.length >= 2 && param.args[1] instanceof String)
                            ? (String) param.args[1] : "shortcut";
                    param.setResult(Boolean.TRUE);
                    sMainHandler.post(() -> toggleRearScreenIfUnfolded(action));
                }
            });
            XposedBridge.log(TAG + ": hooked ShortCutActionsUtils.triggerFunction for fingerprint_double_tap & shortcuts");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ShortCutActionsUtils failed: " + t);
        }

        // 防止 MiuiKeyShortcutRuleManager 开机初始化时把自定义动作 madrid_toggle_rear_screen 清空为 null
        try {
            XposedHelpers.findAndHookMethod(Settings.System.class, "putStringForUser",
                    android.content.ContentResolver.class, String.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[1];
                            String val = (String) param.args[2];
                            if (("fingerprint_double_tap".equals(key)
                                    || "back_double_tap".equals(key)
                                    || "back_triple_tap".equals(key))
                                    && (val == null || val.isEmpty())) {
                                try {
                                    android.content.ContentResolver cr = (android.content.ContentResolver) param.args[0];
                                    String cur = Settings.System.getString(cr, key);
                                    if (BACK_TAP_ACTION_TOGGLE_REAR.equals(cur)) {
                                        XposedBridge.log(TAG + ": blocked boot reset of " + key + " -> null");
                                        param.setResult(Boolean.TRUE);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Settings.System.putStringForUser to preserve fingerprint_double_tap across reboots");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Settings.System.putStringForUser failed: " + t);
        }
    }

    private static void toggleRearScreenIfUnfolded(String reason) {
        Object service = sDeviceStateService;
        if (service == null) {
            return;
        }
        try {
            Optional<?> baseOpt = (Optional<?>) XposedHelpers.callMethod(service, "getBaseState");
            Optional<?> committedOpt = (Optional<?>) XposedHelpers.callMethod(service, "getCommittedState");
            int baseId = (baseOpt != null && baseOpt.isPresent())
                    ? (int) XposedHelpers.callMethod(baseOpt.get(), "getIdentifier") : -1;
            int committedId = (committedOpt != null && committedOpt.isPresent())
                    ? (int) XposedHelpers.callMethod(committedOpt.get(), "getIdentifier") : -1;
            // 合盖状态（baseId == 0/1）：如果开启了「合盖轻拍指纹键开启背屏」，仅在解锁后的外屏桌面允许调起（锁屏时禁止调起）
            if (baseId == 0 || baseId == 1) {
                sCurrentBaseState = baseId;
                Context ctx = sSystemContext;
                boolean allowFolded = ctx != null
                        && Settings.Global.getInt(ctx.getContentResolver(), KEY_FOLDED_FINGERPRINT_REAR, 0) == 1;
                if (!allowFolded) {
                    return;
                }
                if (sFoldedRearActive) {
                    exitFoldedRearScreen(reason);
                } else {
                    try {
                        android.app.KeyguardManager km = (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                        if (km != null && km.isKeyguardLocked()) {
                            XposedBridge.log(TAG + ": ignored folded rear screen launch while keyguard is locked");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    launchFoldedRearScreen(reason);
                }
                return;
            }
            if (committedId == 5) {
                cancelRearState(reason);
                notifyBackTapFeedback(false);
            } else {
                enterRearState(reason);
                notifyBackTapFeedback(true);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": toggleRearScreenIfUnfolded failed: " + t);
        }
    }

    private static void ensureFlipFullScreenRegistered(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> stubCls = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerServiceStub", cl);
            Object stub = XposedHelpers.callStaticMethod(stubCls, "get");
            if (stub != null) {
                XposedHelpers.callMethod(stub, "addFlipActivityFullScreen", PKG_SUBSCREEN);
                XposedHelpers.callMethod(stub, "addFlipActivityFullScreen", PKG_SUBSCREEN + "/" + CLS_SUBSCREEN_LAUNCHER);
                XposedHelpers.callMethod(stub, "addFlipActivityFullScreen", CLS_SUBSCREEN_LAUNCHER);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": addFlipActivityFullScreen failed: " + t);
        }
    }

    private static void lockKeyguardUnderRearScreen(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> smCls = XposedHelpers.findClass("android.os.ServiceManager", cl);
            Object binder = XposedHelpers.callStaticMethod(smCls, "getService", "window");
            if (binder != null) {
                Class<?> stubCls = XposedHelpers.findClass("android.view.IWindowManager$Stub", cl);
                Object wm = XposedHelpers.callStaticMethod(stubCls, "asInterface", binder);
                if (wm != null) {
                    XposedHelpers.callMethod(wm, "lockNow", (Object) null);
                    XposedBridge.log(TAG + ": folded mode auto-relocked keyguard under SubScreenLauncher");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lockKeyguardUnderRearScreen failed: " + t);
        }
    }

    private static void launchFoldedRearScreen(String reason) {
        Context ctx = sSystemContext;
        if (ctx == null) {
            return;
        }
        try {
            // 1. 注册 Flip 外屏真全屏白名单，突破 Flip 2 非摄像头/小组件竖屏限制，获得完整 1208×1392 画幅
            ensureFlipFullScreenRegistered(sSystemClassLoader);

            Intent launch = new Intent();
            launch.setComponent(new ComponentName(PKG_SUBSCREEN, CLS_SUBSCREEN_LAUNCHER));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            launch.putExtra(EXTRA_FOLDED_MODE, true);
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(ctx, 0, 0);
            opts.setLaunchDisplayId(0);
            sFoldedRearActive = true;
            notifyFlipHomeOverlayHide(true);
            ctx.startActivity(launch, opts.toBundle());
            XposedBridge.log(TAG + ": launched SubScreenLauncher in folded full-screen mode (displayId=0) via " + reason);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": launchFoldedRearScreen failed: " + t);
        }
    }

    private static void goToSleepNow(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                XposedHelpers.callMethod(pm, "goToSleep", android.os.SystemClock.uptimeMillis());
                XposedBridge.log(TAG + ": folded mode exit -> PowerManager.goToSleep()");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": goToSleepNow failed: " + t);
        }
    }

    private static void exitFoldedRearScreen(String reason) {
        Context ctx = sSystemContext;
        int exitMode = ctx != null
                ? Settings.Global.getInt(ctx.getContentResolver(), KEY_FOLDED_EXIT_TO_LOCK, 1)
                : 1;
        if (exitMode == 1) {
            // 1 = 直接息屏：走系统原生平滑息屏淡出动画直达 AOD，彻底无锁屏/桌面闪现；450ms 后息屏完成再静默销毁背屏并恢复小部件
            goToSleepNow(ctx);
            sMainHandler.postDelayed(() -> {
                notifyFinishRearLauncher(true);
                sFoldedRearActive = false;
                notifyFlipHomeOverlayHide(false);
            }, 450L);
            XposedBridge.log(TAG + ": folded mode exit (screen_off) via " + reason);
        } else if (exitMode == 2) {
            // 2 = 回原厂锁屏
            lockKeyguardUnderRearScreen(sSystemClassLoader);
            sMainHandler.postDelayed(() -> notifyFinishRearLauncher(false), 120L);
            sMainHandler.postDelayed(() -> {
                sFoldedRearActive = false;
                notifyFlipHomeOverlayHide(false);
            }, 600L);
            XposedBridge.log(TAG + ": folded mode exit (lockscreen) via " + reason);
        } else {
            // 0 = 回外屏桌面
            notifyFinishRearLauncher(false);
            sMainHandler.postDelayed(() -> {
                sFoldedRearActive = false;
                notifyFlipHomeOverlayHide(false);
            }, 600L);
            XposedBridge.log(TAG + ": folded mode exit (desktop) via " + reason);
        }
    }

    private static void notifyBackTapFeedback(boolean enabled) {
        Context ctx = sSystemContext;
        if (ctx == null) {
            return;
        }
        try {
            Intent it = new Intent(RearScreenNotifyReceiver.ACTION_NOTIFY_REAR_TOGGLED);
            it.setComponent(new ComponentName("io.github.aragamiz.madrid2flip",
                    "io.github.aragamiz.madrid2flip.NoticeReceiver"));
            it.putExtra(RearScreenNotifyReceiver.EXTRA_ENABLED, enabled);
            it.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(it);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": notifyBackTapFeedback failed: " + t);
        }
    }

    private static void hookAllowDisplay1Launch(ClassLoader cl) {
        try {
            Class<?> supCls = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", cl);
            XposedBridge.hookAllMethods(supCls, "isCallerAllowedToLaunchOnDisplay", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 4) {
                        return;
                    }
                    int callingUid = (int) param.args[1];
                    int launchDisplayId = (int) param.args[2];
                    ActivityInfo aInfo = (param.args[3] instanceof ActivityInfo) ? (ActivityInfo) param.args[3] : null;
                    if (launchDisplayId == 1) {
                        boolean isSubscreenTarget = aInfo != null && PKG_SUBSCREEN.equals(aInfo.packageName);
                        boolean isPrivCaller = (callingUid == 0 || callingUid == 1000 || callingUid == 2000);
                        if (isSubscreenTarget || isPrivCaller) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay for displayId=1");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ActivityTaskSupervisor failed: " + t);
        }
    }

    /**
     * 解除 state=5 下外屏 (displayId=1) 对内屏 (leadDisplayId=0) 的亮度跟从锁定，
     * 使 DisplayPowerController(1) 启用自身独立自动亮度与手动亮度滑块。
     */
    private static void hookSubDisplayIndependentBrightness(ClassLoader cl) {
        try {
            // 1. 从 LogicalDisplay 源头解除 displayId=1 对 leadDisplayId=0 的跟从关系
            try {
                Class<?> ldCls = XposedHelpers.findClass("com.android.server.display.LogicalDisplay", cl);
                XposedBridge.hookAllMethods(ldCls, "getLeadDisplayIdLocked", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                            if (displayId == 1) {
                                param.setResult(-1);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook LogicalDisplay.getLeadDisplayIdLocked failed: " + t);
            }

            Class<?> dpcCls = XposedHelpers.findClass("com.android.server.display.DisplayPowerController", cl);
            XposedBridge.hookAllMethods(dpcCls, "onDisplayChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                        if (displayId == 1 && param.args != null && param.args.length >= 2
                                && param.args[1] instanceof Integer) {
                            int leadId = (Integer) param.args[1];
                            if (leadId == 0) {
                                param.args[1] = -1;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            // 2. 拦截 setBrightnessToFollow，彻底避免 mBrightnessReason=follower 覆盖外屏自动亮度
            XposedBridge.hookAllMethods(dpcCls, "setBrightnessToFollow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                        if (displayId == 1) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(dpcCls, "loadAmbientLightSensor", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                        // 仅在非合盖（displayId=1 对应外屏）时绑定背面光感，防止合盖态误改内屏光感
                        if (displayId != 1 || sCurrentBaseState == 0 || sCurrentBaseState == 1) {
                            return;
                        }
                        SensorManager sm = (SensorManager) XposedHelpers.getObjectField(param.thisObject, "mSensorManager");
                        if (sm == null) {
                            return;
                        }
                        // MIX Flip 2 背面环境光传感器：TCS3720ALSPRX Back ALS (xiaomi.sensor.back_lux = 33171055)
                        Sensor backAls = sm.getDefaultSensor(33171055, false);
                        if (backAls == null) {
                            backAls = sm.getDefaultSensor(33171055, true);
                        }
                        if (backAls != null) {
                            XposedHelpers.setObjectField(param.thisObject, "mLightSensor", backAls);
                            XposedBridge.log(TAG + ": bound displayId=1 mLightSensor -> " + backAls.getName() + " (33171055)");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": bind back ALS failed: " + t);
                    }
                }
            });

            // 3. 外屏自动亮度精细系数缩放 + 亮度上限钳位（仅在非合盖展开态 displayId=1 生效）
            XposedBridge.hookAllMethods(dpcCls, "clampScreenBrightness", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                        if (displayId != 1 || sCurrentBaseState == 0 || sCurrentBaseState == 1
                                || !(param.getResult() instanceof Float)) {
                            return;
                        }
                        float val = (Float) param.getResult();
                        if (Float.isNaN(val) || val <= FLIP2_MIN_BRIGHTNESS) {
                            return;
                        }
                        Context ctx = sSystemContext;
                        int scalePct = 100;
                        int capPct = 70;
                        if (ctx != null) {
                            scalePct = Settings.Global.getInt(ctx.getContentResolver(), KEY_REAR_AUTO_BRIGHT_SCALE, 100);
                            capPct = Settings.Global.getInt(ctx.getContentResolver(), KEY_REAR_BRIGHTNESS_CAP, 70);
                        }
                        scalePct = Math.max(40, Math.min(130, scalePct));
                        if (scalePct != 100) {
                            val = Math.max(FLIP2_MIN_BRIGHTNESS, val * (scalePct / 100.0f));
                        }
                        capPct = Math.max(15, Math.min(100, capPct));
                        if (capPct < 100) {
                            float capLinear = FLIP2_MIN_BRIGHTNESS
                                    + (FLIP2_NORMAL_MAX_BRIGHTNESS - FLIP2_MIN_BRIGHTNESS) * (float) Math.pow(capPct / 100.0, 2.2);
                            if (val > capLinear) {
                                val = capLinear;
                            }
                        }
                        param.setResult(val);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked DisplayPowerController for displayId=1 independent brightness + back ALS + cap");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook DisplayPowerController failed: " + t);
        }
    }

    private static void hookDeviceStateLifecycle(ClassLoader cl) {
        try {
            Class<?> implCls = XposedHelpers.findClass("com.android.server.devicestate.DeviceStateManagerServiceImpl", cl);

            XposedBridge.hookAllMethods(implCls, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object service = XposedHelpers.getObjectField(param.thisObject, "mService");
                        if (service != null) {
                            sDeviceStateService = service;
                        }
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (ctx != null) {
                            sSystemContext = ctx;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(implCls, "setBootPhase", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sReceiverRegistered || param.args == null || param.args.length < 1) {
                        return;
                    }
                    int phase = (int) param.args[0];
                    if (phase < 500) {
                        return;
                    }
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        Object service = XposedHelpers.getObjectField(param.thisObject, "mService");
                        if (service != null) {
                            sDeviceStateService = service;
                        }
                        if (ctx != null) {
                            sSystemContext = ctx;
                            IntentFilter filter = new IntentFilter();
                            filter.addAction(ACTION_EXIT_REAR_SCREEN);
                            filter.addAction(ACTION_ENTER_REAR_SCREEN);
                            BroadcastReceiver receiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    String act = intent != null ? intent.getAction() : null;
                                    if (ACTION_EXIT_REAR_SCREEN.equals(act)) {
                                        if (sFoldedRearActive) {
                                            exitFoldedRearScreen("broadcast");
                                        } else {
                                            cancelRearState("broadcast");
                                        }
                                    } else if (ACTION_ENTER_REAR_SCREEN.equals(act)) {
                                        enterRearState("broadcast");
                                    }
                                }
                            };
                            ctx.registerReceiver(receiver, filter, 2);
                            sReceiverRegistered = true;
                            XposedBridge.log(TAG + ": registered ENTER/EXIT rear screen broadcast receiver");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": register receiver failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(implCls, "handleBaseStateChangedLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int lastBaseId = (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer)
                                ? (Integer) param.args[0] : -1;
                        Object service = XposedHelpers.getObjectField(param.thisObject, "mService");
                        if (service == null) {
                            return;
                        }
                        sDeviceStateService = service;
                        Optional<?> baseOpt = (Optional<?>) XposedHelpers.callMethod(service, "getBaseState");
                        Optional<?> committedOpt = (Optional<?>) XposedHelpers.callMethod(service, "getCommittedState");
                        if (baseOpt == null || !baseOpt.isPresent()) {
                            return;
                        }
                        int baseId = (int) XposedHelpers.callMethod(baseOpt.get(), "getIdentifier");
                        sCurrentBaseState = baseId;
                        int committedId = (committedOpt != null && committedOpt.isPresent())
                                ? (int) XposedHelpers.callMethod(committedOpt.get(), "getIdentifier") : -1;

                        // 1. 合盖（0/1）时：立即取消任何排队中的延迟开启任务（根治合盖经过半展开误触发 state=5 导致外屏黑屏卡死！）
                        if (baseId == 0 || baseId == 1) {
                            cancelPendingEnterTasks();
                            if (committedId == 4 || committedId == 5 || committedId == 6) {
                                Context ctx = sSystemContext;
                                boolean foldClose = (ctx == null)
                                        || Settings.Global.getInt(ctx.getContentResolver(), KEY_FOLD_AUTO_CLOSE, 1) == 1;
                                if (foldClose) {
                                    notifyFinishRearLauncher();
                                    XposedHelpers.callMethod(service, "cancelStateRequestInternal");
                                    XposedBridge.log(TAG + ": auto-cancelled state=" + committedId + " on fold closed (baseId=" + baseId + ")");
                                }
                            }
                            return;
                        }

                        // 2. 半展开桌钟模式（baseId == 2 HALF_OPENED）：自动激活背屏（延迟 600ms 防合盖路过误触）
                        if (baseId == 2 && committedId != 5) {
                            Context ctx = sSystemContext;
                            if (ctx != null && Settings.Global.getInt(ctx.getContentResolver(), KEY_HALF_OPEN_REAR, 0) == 1) {
                                scheduleEnterRearState("half_open_desk_clock", 600L);
                                return;
                            }
                        }

                        // 3. 展开（从 0/1 变为 2/3）且用户开启了「展开自动开启背屏」时，自动拉起 state=5 + 背屏
                        boolean wasFolded = (lastBaseId == 0 || lastBaseId == 1);
                        boolean nowUnfolded = (baseId == 2 || baseId == 3);
                        if (wasFolded && nowUnfolded && committedId != 5) {
                            Context ctx = sSystemContext;
                            if (ctx != null && Settings.Global.getInt(ctx.getContentResolver(), KEY_UNFOLD_AUTO_OPEN, 0) == 1) {
                                scheduleEnterRearState("unfold_auto", 500L);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": handleBaseStateChangedLocked check failed: " + t);
                    }
                }
            });

            XposedBridge.log(TAG + ": hooked DeviceStateManagerServiceImpl fold/unfold lifecycle");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook DeviceStateManagerServiceImpl failed: " + t);
        }
    }

    private static void cancelPendingEnterTasks() {
        if (sPendingEnterRunnable != null) {
            sMainHandler.removeCallbacks(sPendingEnterRunnable);
            sPendingEnterRunnable = null;
        }
        if (sPendingLaunchRunnable != null) {
            sMainHandler.removeCallbacks(sPendingLaunchRunnable);
            sPendingLaunchRunnable = null;
        }
    }

    private static void scheduleEnterRearState(String reason, long delayMs) {
        cancelPendingEnterTasks();
        sPendingEnterRunnable = () -> enterRearState(reason);
        sMainHandler.postDelayed(sPendingEnterRunnable, delayMs);
    }

    private static void enterRearState(String reason) {
        Context ctx = sSystemContext;
        ClassLoader cl = sSystemClassLoader;
        if (ctx == null || cl == null) {
            return;
        }
        // 二次防呆：合盖状态下绝不允许拉起 state=5，根治合盖外屏黑屏卡死！
        if (sCurrentBaseState == 0 || sCurrentBaseState == 1) {
            XposedBridge.log(TAG + ": aborted enterRearState(" + reason + ") because device is folded (baseId=" + sCurrentBaseState + ")");
            return;
        }
        ensureSubscreenHookReady(ctx);
        try {
            Class<?> dsmCls = XposedHelpers.findClass("android.hardware.devicestate.DeviceStateManager", cl);
            Object dsm = ctx.getSystemService(dsmCls);
            Class<?> reqCls = XposedHelpers.findClass("android.hardware.devicestate.DeviceStateRequest", cl);
            Object builder = XposedHelpers.callStaticMethod(reqCls, "newBuilder", 5);
            Object req = XposedHelpers.callMethod(builder, "build");
            XposedHelpers.callMethod(dsm, "requestState", req, null, null);
            XposedBridge.log(TAG + ": requested state=5 via " + reason);

            if (sPendingLaunchRunnable != null) {
                sMainHandler.removeCallbacks(sPendingLaunchRunnable);
            }
            sPendingLaunchRunnable = () -> {
                if (sCurrentBaseState == 0 || sCurrentBaseState == 1) {
                    XposedBridge.log(TAG + ": aborted SubScreenLauncher launch because device folded during delay");
                    return;
                }
                try {
                    Intent launch = new Intent();
                    launch.setComponent(new ComponentName(PKG_SUBSCREEN, CLS_SUBSCREEN_LAUNCHER));
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(1);
                    ctx.startActivity(launch, opts.toBundle());
                    XposedBridge.log(TAG + ": launched SubScreenLauncher on displayId=1 (" + reason + ")");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": start SubScreenLauncher failed: " + t);
                }
            };
            sMainHandler.postDelayed(sPendingLaunchRunnable, 450L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enterRearState failed: " + t);
        }
    }

    /**
     * 背屏中心进程在开机阶段往往早于 LSPosed daemon 就绪，导致本次开机内首次进入背屏时
     * 模块作用域尚未生效（背屏锁定/壁纸循环 Hook 不加载）。这里在开机后第一次拉起背屏前
     * 主动结束一次该进程，使其在 daemon 就绪后重新 fork，保证 Hook 一定注入。
     */
    private static void ensureSubscreenHookReady(Context ctx) {
        if (sSubscreenResetDone || ctx == null) {
            return;
        }
        sSubscreenResetDone = true;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                Object ok = XposedHelpers.callMethod(am, "forceStopPackage", PKG_SUBSCREEN);
                XposedBridge.log(TAG + ": reset subscreencenter once after boot to apply module hooks (ret=" + ok + ")");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reset subscreencenter failed: " + t);
        }
    }

    private static void notifyFinishRearLauncher() {
        notifyFinishRearLauncher(false);
    }

    private static void notifyFinishRearLauncher(boolean silent) {
        Context ctx = sSystemContext;
        if (ctx != null) {
            try {
                Intent it = new Intent(ACTION_FINISH_REAR_LAUNCHER);
                if (silent) {
                    it.putExtra(EXTRA_SILENT_FINISH, true);
                }
                ctx.sendBroadcast(it);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void cancelRearState(String reason) {
        sFoldedRearActive = false;
        notifyFinishRearLauncher();
        Object service = sDeviceStateService;
        if (service == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(service, "cancelStateRequestInternal");
            XposedBridge.log(TAG + ": cancelled state override via " + reason);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cancelStateRequestInternal failed: " + t);
        }
    }
}
