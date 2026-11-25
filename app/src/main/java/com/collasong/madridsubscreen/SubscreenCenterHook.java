package com.collasong.madridsubscreen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 小米 18 背屏中心（Peeko/Lumi AI 萌宠 + 应用卡 + 原生「背屏」设置入口）移植 Hook：
 * - com.xiaomi.subscreencenter：
 *   1. 定向伪装为独立背屏设备（type=6）；
 *   2. 对齐 MIX Flip 2 外屏双摄区域（顶部 1208×738 严格对照小米 18 原版 976×596 比例），下方纯黑 OLED 熄灭；
 *   3. 双击下方纯黑区域（y > 738）一键退出背屏；
 *   4. 防烧屏上下平滑微移（每 60 秒在 0..+24px 区间缓慢往复移动）。
 * - com.android.settings / com.android.thememanager：
 *   仅放开 isIndependentRearDevice()，保留 isFlipDevice()==true，与 Flip 2 原生「外屏」设置共存。
 */
public final class SubscreenCenterHook {

    private static final String TAG = "MadridSubscreen/Center";

    // MIX Flip 2 外屏物理分辨率 1208×1392，双摄宽 398px、高 728px；按小米 18 原版 976:596 比例缩放后为 1208×738
    private static final int TARGET_WIDTH = 1208;
    private static final int TARGET_HEIGHT = 738;
    private static final int OUTER_PHYSICAL_HEIGHT = 1392;
    private static final int CAMERA_SAFE_LEFT_PX = 410;
    private static final float VIEWPORT_CORNER_RADIUS_PX = 100f;
    // 防烧屏三档模式（Settings.Global madrid_rear_burnin_mode）：
    // 0 = 原厂AOD档（60s / 6px / 0~18px）
    // 1 = 平衡档（20s / 24px / 0~180px，默认）
    // 2 = 积极大幅档（12s / 顶部0 ↔ 中部320 ↔ 底部640 大幅轮换整屏位置）
    private static final int[] AGGRESSIVE_POSITIONS = {0, 320, 640, 320};
    private static int sAggressiveIdx = 0;
    private static final int BACK_PROXIMITY_SENSOR_TYPE = 33171123; // TCS3720ALSPRX Proximity Back Sensor（包装值，贴桌易误报 5.0）
    private static final int BACK_PROX_FACTORY_TYPE = 33171124;     // TCS3720ALSPRX Proximity Back Sensor Strm（原始流，贴桌饱和 ~1023）
    private static final float BACK_PROX_FACTORY_NEAR = 900f;       // 原始 ADC 阈值：>=900 视为贴桌遮挡

    private static long sLastBlackTapTime = 0L;
    private static float sTouchDownX = 0f;
    private static float sTouchDownY = 0f;
    private static long sTouchDownTime = 0L;
    private static int sBurnShiftY = 0;
    private static int sBurnDir = 1;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<View> sTrackedContentRef;
    private static WeakReference<Activity> sTrackedActivityRef;
    private static SensorManager sSensorManager;
    private static SensorEventListener sBackProxListener;
    private static volatile Boolean sLastBackProxNear;
    private static android.view.OrientationEventListener sOrientationListener;
    private static int sCurrentContentRotation = 0;
    private static volatile boolean sIsHalfOpen = false;
    private static volatile boolean sFinishReceiverRegistered;
    private static WeakReference<ImageView> sGlassBgRef;
    private static Bitmap sLastGlassBitmap;
    private static final long GLASS_REFRESH_INTERVAL_MS = 650L;

    private static final Runnable sGlassRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
            ImageView glassBg = sGlassBgRef != null ? sGlassBgRef.get() : null;
            if (content != null && glassBg != null && content.isAttachedToWindow() && glassBg.getVisibility() == View.VISIBLE) {
                updateAmbientFrostedGlass(content, glassBg);
                sMainHandler.postDelayed(this, GLASS_REFRESH_INTERVAL_MS);
            }
        }
    };

    private static void triggerImmediateGlassRefresh(long delayMs) {
        sMainHandler.removeCallbacks(sGlassRefreshRunnable);
        sMainHandler.postDelayed(sGlassRefreshRunnable, delayMs);
    }

    private static final Runnable sTimeoutExitRunnable = () -> {
        Activity act = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
        if (act != null && !act.isFinishing()) {
            XposedBridge.log(TAG + ": rear screen idle timeout reached -> exiting state=5");
            try {
                act.sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
            } catch (Throwable ignored) {
            }
        }
    };

    private static final Runnable sTableOffRunnable = () -> {
        Activity act = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
        if (act != null && !act.isFinishing()) {
            XposedBridge.log(TAG + ": rear face-down/table proximity triggered -> exiting state=5");
            try {
                act.sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
            } catch (Throwable ignored) {
            }
        }
    };
    private static int getBurnInMode(Context ctx) {
        try {
            Context c = ctx != null ? ctx : (sTrackedActivityRef != null ? sTrackedActivityRef.get() : null);
            if (c != null) {
                return Settings.Global.getInt(c.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_BURNIN_MODE, 1);
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static long getBurnInIntervalMs(int mode) {
        if (mode == 0) return 60_000L; // 原厂 AOD 档
        if (mode == 2) return 12_000L; // 积极大幅档
        return 20_000L;                // 平衡档
    }

    private static final Runnable sBurnInRunnable = new Runnable() {
        @Override
        public void run() {
            View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
            if (content != null && content.isAttachedToWindow()) {
                int mode = getBurnInMode(content.getContext());
                long duration;
                if (mode == 0) {
                    // 原厂 AOD 档：克制像素微移 0..18px
                    sBurnShiftY += sBurnDir * 6;
                    if (sBurnShiftY >= 18) { sBurnShiftY = 18; sBurnDir = -1; }
                    else if (sBurnShiftY <= 0) { sBurnShiftY = 0; sBurnDir = 1; }
                    duration = 500L;
                } else if (mode == 2) {
                    // 积极大幅档：顶部(0) ↔ 中部(320) ↔ 底部(640) 轮换整块背屏位置
                    sAggressiveIdx = (sAggressiveIdx + 1) % AGGRESSIVE_POSITIONS.length;
                    sBurnShiftY = AGGRESSIVE_POSITIONS[sAggressiveIdx];
                    duration = 700L;
                } else {
                    // 平衡档：0..180px 平滑往复
                    sBurnShiftY += sBurnDir * 24;
                    if (sBurnShiftY >= 180) { sBurnShiftY = 180; sBurnDir = -1; }
                    else if (sBurnShiftY <= 0) { sBurnShiftY = 0; sBurnDir = 1; }
                    duration = 700L;
                }
                float targetX = (sCurrentContentRotation == 180 && getFlipAlignMode(content.getContext()) == 0)
                        ? CAMERA_SAFE_LEFT_PX : 0f;
                float targetY = (sCurrentContentRotation == 180)
                        ? (OUTER_PHYSICAL_HEIGHT - TARGET_HEIGHT - Math.min(sBurnShiftY, 48))
                        : sBurnShiftY;
                content.animate().translationX(targetX).translationY(targetY).setDuration(duration).start();
                sMainHandler.postDelayed(this, getBurnInIntervalMs(mode));
            }
        }
    };

    private SubscreenCenterHook() {
    }

    public static void installSubscreenCenter(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> infoCls = XposedHelpers.findClass("miui.util.MiuiMultiDisplayTypeInfo", cl);
            try {
                XposedHelpers.setStaticIntField(infoCls, "sDeviceType", 6);
            } catch (Throwable ignored) {
            }
            XposedHelpers.findAndHookMethod(infoCls, "isIndependentRearDevice",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedHelpers.findAndHookMethod(infoCls, "getDeviceType",
                    XC_MethodReplacement.returnConstant(6));
            XposedHelpers.findAndHookMethod(infoCls, "isFlipDevice",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.log(TAG + ": spoofed MiuiMultiDisplayTypeInfo -> DEVICE_INDEPENDENT_REAR_PHONE_TYPE (6)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": spoof MiuiMultiDisplayTypeInfo failed: " + t);
        }

        // 始终保持 1208×738（按小米 18 原版 976:596 比例），确保应用卡与双摄避让 100% 永不错位；
        // 开启「背屏全屏模式（原厂视口）」时跳过视口重映射，完整铺满外屏。
        try {
            XposedHelpers.findAndHookMethod(Display.class, "getRealSize", Point.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isStockFullscreen(null)) {
                        return;
                    }
                    Display d = (Display) param.thisObject;
                    if (d != null && (d.getDisplayId() == 1 || d.getDisplayId() == 0) && param.args[0] instanceof Point) {
                        Point p = (Point) param.args[0];
                        if (p.x == TARGET_WIDTH && p.y > TARGET_HEIGHT) {
                            p.set(TARGET_WIDTH, TARGET_HEIGHT);
                        }
                    }
                }
            });
            XC_MethodHook metricsHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isStockFullscreen(null)) {
                        return;
                    }
                    Display d = (Display) param.thisObject;
                    if (d != null && (d.getDisplayId() == 1 || d.getDisplayId() == 0)
                            && param.args[0] instanceof android.util.DisplayMetrics) {
                        android.util.DisplayMetrics dm = (android.util.DisplayMetrics) param.args[0];
                        if (dm.widthPixels == TARGET_WIDTH && dm.heightPixels > TARGET_HEIGHT) {
                            dm.heightPixels = TARGET_HEIGHT;
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Display.class, "getRealMetrics", android.util.DisplayMetrics.class, metricsHook);
            XposedHelpers.findAndHookMethod(Display.class, "getMetrics", android.util.DisplayMetrics.class, metricsHook);
            XposedBridge.log(TAG + ": hooked Display.getRealSize/getRealMetrics/getMetrics(displayId=1) -> 1208x738 (bypassed in stock fullscreen)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Display.getRealSize/Metrics failed: " + t);
        }

        // 修复应用卡/面板呼出被 Flip 2 左侧双摄（398px）遮挡：确保 getSafeInsetLeft() 返回 >= 410px
        try {
            XposedHelpers.findAndHookMethod(DisplayCutout.class, "getSafeInsetLeft", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isStockFullscreen(null)) {
                        return;
                    }
                    int val = (param.getResult() instanceof Integer) ? (Integer) param.getResult() : 0;
                    if (val < CAMERA_SAFE_LEFT_PX) {
                        param.setResult(CAMERA_SAFE_LEFT_PX);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked DisplayCutout.getSafeInsetLeft() >= " + CAMERA_SAFE_LEFT_PX + "px for Flip 2 camera clearance");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook DisplayCutout.getSafeInsetLeft failed: " + t);
        }

        // 限制 SubScreenLauncher / GuideActivity 渲染区在顶部 1208×738，下方纯黑 + 双击黑屏退出 + 防烧屏微移
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (!isSubScreenActivity(act.getClass().getName())) {
                        return;
                    }
                    boolean stockFs = isStockFullscreen(act);
                    if (!stockFs && !isFoldedMode(act)) {
                        return;
                    }
                    try {
                        android.view.Window win = act.getWindow();
                        if (win != null) {
                            if (!stockFs) {
                                win.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                                win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                            }
                            android.view.WindowManager.LayoutParams wlp = win.getAttributes();
                            wlp.alpha = 0f;
                            win.setAttributes(wlp);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    String clsName = act.getClass().getName();
                    if (!isSubScreenActivity(clsName)) {
                        return;
                    }
                    if (isStockFullscreen(act)) {
                        registerFinishReceiver(act);
                        try {
                            android.view.Window win = act.getWindow();
                            if (win != null) {
                                android.view.WindowManager.LayoutParams wlp = win.getAttributes();
                                wlp.alpha = 0f;
                                win.setAttributes(wlp);
                            }
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    // 严防合盖后被 WindowManager 重排到 displayId=0（合盖外屏/主屏）
                    if (finishIfNotOnSecondaryDisplay(act)) {
                        return;
                    }
                    registerFinishReceiver(act);
                    sMainHandler.post(() -> applyCameraAlignedViewport(act, false));
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (!isSubScreenActivity(act.getClass().getName())) {
                        return;
                    }
                    if (isStockFullscreen(act)) {
                        sTrackedActivityRef = new WeakReference<>(act);
                        sMainHandler.post(() -> {
                            fadeInWindow(act, 40L, 320L);
                            resetIdleTimeout(act);
                            registerBackProximity(act);
                            if (getRearVideoPlayMode(act) == 2) {
                                replayTrackedVideos("screen_on_fullscreen");
                            }
                        });
                        return;
                    }
                    if (finishIfNotOnSecondaryDisplay(act)) {
                        return;
                    }
                    sTrackedActivityRef = new WeakReference<>(act);
                    sMainHandler.post(() -> {
                        applyCameraAlignedViewport(act, true);
                        resetIdleTimeout(act);
                        registerBackProximity(act);
                        registerGravityAutoRotate(act);
                        if (getRearVideoPlayMode(act) == 2) {
                            replayTrackedVideos("screen_on_half");
                        }
                    });
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (isSubScreenActivity(act.getClass().getName())) {
                        sMainHandler.removeCallbacks(sBurnInRunnable);
                        sMainHandler.removeCallbacks(sGlassRefreshRunnable);
                        sMainHandler.removeCallbacks(sTimeoutExitRunnable);
                        sMainHandler.removeCallbacks(sTableOffRunnable);
                        stopTouchLockTicker();
                        unregisterBackProximity();
                        unregisterGravityAutoRotate();
                    }
                }
            });

            // 双击下方空白/毛玻璃区域退出背屏；引导页左滑相机步骤轻点直接跳过；任意触摸重置息屏超时
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (!isSubScreenActivity(act.getClass().getName())) {
                        return;
                    }
                    // 背屏触摸锁定：仅在内屏展开且外屏作为背屏显示（displayId=1）时吞掉外屏全部触摸，
                    // 合盖全屏模式（displayId=0）、内屏息屏/支架模式与内屏触控完全不受影响
                    if (isRearTouchLocked(act) && isRearDisplay(act)) {
                        param.setResult(Boolean.TRUE);
                        return;
                    }
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev != null) {
                        int actMask = ev.getActionMasked();
                        if (isStockFullscreen(act)) {
                            // 全屏原厂视口模式下不拦截下方点击，按下重置超时，引导相机步骤轻点跳过，短点按模式重播视频/Live图
                            if (actMask == MotionEvent.ACTION_DOWN) {
                                sTouchDownX = ev.getRawX();
                                sTouchDownY = ev.getRawY();
                                sTouchDownTime = System.currentTimeMillis();
                                resetIdleTimeout(act);
                            } else if (actMask == MotionEvent.ACTION_UP) {
                                if (trySkipGuideCameraStep(act, ev)) {
                                    param.setResult(Boolean.TRUE);
                                    return;
                                }
                                tryReplayStoppedVideoOnTap(act, ev);
                            }
                            return;
                        }
                        boolean isFlipped = (sCurrentContentRotation == 180);
                        boolean inBlankArea = isFlipped
                                ? (ev.getRawY() < OUTER_PHYSICAL_HEIGHT - TARGET_HEIGHT)
                                : (ev.getRawY() > TARGET_HEIGHT + sBurnShiftY);
                        if (actMask == MotionEvent.ACTION_UP) {
                            // 官方新手引导的左滑相机步骤：轻点一下直接跳过到下一步
                            if (trySkipGuideCameraStep(act, ev)) {
                                param.setResult(Boolean.TRUE);
                                return;
                            }
                            // 每次滑动/点击结束后 45ms 立即重采样毛玻璃色彩，消除切卡/切主题延迟
                            triggerImmediateGlassRefresh(45L);
                            // 短点重播已停在尾帧的视频/Live图壁纸（仅在「点击重播」模式）
                            tryReplayStoppedVideoOnTap(act, ev);
                        } else if (actMask == MotionEvent.ACTION_CANCEL) {
                            triggerImmediateGlassRefresh(120L);
                        } else if (actMask == MotionEvent.ACTION_DOWN) {
                            sTouchDownX = ev.getRawX();
                            sTouchDownY = ev.getRawY();
                            sTouchDownTime = System.currentTimeMillis();
                            resetIdleTimeout(act);
                            if (inBlankArea) {
                                long now = System.currentTimeMillis();
                                if (now - sLastBlackTapTime <= 400L) {
                                    sLastBlackTapTime = 0L;
                                    XposedBridge.log(TAG + ": double-tap blank/glass area -> exiting rear screen");
                                    try {
                                        act.sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
                                    } catch (Throwable ignored) {
                                    }
                                    param.setResult(Boolean.TRUE);
                                } else {
                                    sLastBlackTapTime = now;
                                }
                            }
                        }
                    }
                }
            });

            installRearTouchLock(cl);
            installRearWindowTouchable(cl);
            installRearVideoLoop(cl);
            installGuidePanelHook(cl);

            XposedBridge.log(TAG + ": camera-aligned viewport + black-area double-tap exit + burn-in shift + touch-lock + video-loop armed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity viewport failed: " + t);
            // 触摸/视口 hook 失败时仍要保证背屏锁定与壁纸循环可用（其内部逐项 try-catch）
            installRearTouchLock(cl);
            installRearWindowTouchable(cl);
            installRearVideoLoop(cl);
        }
    }

    // ------------------------------------------------------------------
    // 背屏触摸锁定 / 视频壁纸循环（新增，均复用既有设置与 Hook 通道）
    // ------------------------------------------------------------------

    private static final long SETTING_CACHE_MS = 250L;
    private static volatile boolean sTouchLockEnabled = false;
    private static volatile long sTouchLockCheckedAt = 0L;
    private static volatile int sVideoPlayMode = 1; // 0=循环, 1=点击重播(默认), 2=亮屏一次, 3=仅播一次
    private static volatile long sVideoPlayModeCheckedAt = 0L;
    private static volatile boolean sStockFullscreenEnabled = false;
    private static volatile long sStockFullscreenCheckedAt = 0L;

    private static final java.util.ArrayList<WeakReference<Object>> sVideoViews = new java.util.ArrayList<>();

    /** 外屏（displayId=1）上的背屏 Activity：正是「内屏展开 + 外屏作为背屏显示」的形态。 */
    private static boolean isRearDisplay(Activity act) {
        try {
            Display d = act != null ? act.getDisplay() : null;
            return d != null && d.getDisplayId() == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 背屏触摸锁定：内屏展开且外屏作为背屏显示（displayId=1）时吞掉外屏全部触摸。
     * <p>
     * 关键：SubScreenLauncher 自己重写了 dispatchTouchEvent（长按唤出卡片视图、双击熄屏等手势
     * 都在它内部消费），只在父类 Activity 上 Hook 会等到 super 调用时才触发，此时手势早已生效。
     * 因此必须直接 Hook 子类方法，才能在事件进入视图树之前把它吞掉。
     */
    private static void installRearTouchLock(ClassLoader cl) {
        try {
            Class<?> launcherCls = XposedHelpers.findClass("com.xiaomi.subscreencenter.SubScreenLauncher", cl);
            XposedBridge.hookAllMethods(launcherCls, "dispatchTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (act == null) {
                        return;
                    }
                    // 仅在「内屏展开 + 外屏作为背屏显示（displayId=1）」时拦截；
                    // 合盖后 SubScreenLauncher 回到 display 0 作为主外屏，必须保持可触摸。
                    if (isRearDisplay(act) && isRearTouchLocked(act)) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked SubScreenLauncher.dispatchTouchEvent for rear touch lock");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook SubScreenLauncher.dispatchTouchEvent failed: " + t);
        }

        // 兜底：其它背屏 Activity 仍走父类分发的场景
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (act == null || !isSubScreenActivity(act.getClass().getName())) {
                        return;
                    }
                    if (isRearDisplay(act) && isRearTouchLocked(act)) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked Activity.dispatchTouchEvent for rear touch lock (fallback)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook rear touch lock fallback failed: " + t);
        }
    }

    /** 当前背屏 Activity 引用，供窗口可触摸状态切换使用。 */
    private static WeakReference<Activity> sRearActRef;
    private static android.database.ContentObserver sTouchLockObserver;
    private static Handler sTouchLockHandler;
    private static Boolean sLastWindowTouchable;

    /**
     * 锁定开启时把背屏窗口"从触摸命中链里摘出去"。
     * <p>
     * 只 Hook dispatchTouchEvent 是不够的：触摸事件在到达 Activity 之前，会先经
     * InputDispatcher → WindowManagerService 完成窗口命中、焦点与输入法目标更新，
     * 内屏输入法正是在这个阶段因为"用户点了别处"而收起。
     * 给窗口加 FLAG_NOT_TOUCHABLE 后，InputDispatcher 直接跳过该窗口，事件无处可去被丢弃，
     * WindowManager / 输入法层完全看不到这次触摸，输入法不会被收起，
     * 也不需要再去 Hook InputMethodManagerService 这类侵入性更强的点。
     */
    private static void installRearWindowTouchable(ClassLoader cl) {
        try {
            Class<?> launcherCls = XposedHelpers.findClass("com.xiaomi.subscreencenter.SubScreenLauncher", cl);
            XposedBridge.hookAllMethods(launcherCls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (act == null) {
                            return;
                        }
                        sRearActRef = new WeakReference<>(act);
                        // Activity 重建后旧状态失效，强制重新套用一次
                        sLastWindowTouchable = null;
                        bindTouchLockObserver(act);
                        // display 切换与窗口添加有延迟，推迟一拍再套用
                        act.getWindow().getDecorView().postDelayed(
                                () -> applyRearWindowTouchable(act), 300L);
                        startTouchLockTicker();
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked SubScreenLauncher.onResume for window touchability");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook window touchability failed: " + t);
        }
    }

    private static void bindTouchLockObserver(Activity act) {
        if (sTouchLockObserver != null) {
            return;
        }
        try {
            sTouchLockObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    Activity a = sRearActRef != null ? sRearActRef.get() : null;
                    if (a != null) {
                        applyRearWindowTouchable(a);
                    }
                }
            };
            act.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK),
                    false, sTouchLockObserver);
            XposedBridge.log(TAG + ": rear touch lock observer registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": register touch lock observer failed: " + t);
        }
    }

    private static void applyRearWindowTouchable(Activity act) {
        try {
            android.view.Window win = act.getWindow();
            if (win == null || act.isFinishing()) {
                return;
            }
            boolean block = isRearDisplay(act) && isRearTouchLocked(act);
            boolean touchable = !block;
            if (sLastWindowTouchable != null && sLastWindowTouchable == touchable) {
                return;
            }
            if (touchable) {
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            } else {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
            sLastWindowTouchable = touchable;
            XposedBridge.log(TAG + ": rear window touchable=" + touchable);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": apply window touchability failed: " + t);
        }
    }

    private static Runnable sTouchLockTickerRunnable;

    /** 合盖/展开切换不会重建 Activity，用低频轮询兜住 display 变化引起的状态偏差；在 onPause 时自动停止避免后台唤醒。 */
    private static void startTouchLockTicker() {
        if (sTouchLockHandler == null) {
            sTouchLockHandler = new Handler(Looper.getMainLooper());
        }
        if (sTouchLockTickerRunnable == null) {
            sTouchLockTickerRunnable = new Runnable() {
                @Override
                public void run() {
                    Activity a = sRearActRef != null ? sRearActRef.get() : null;
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        applyRearWindowTouchable(a);
                        if (sTouchLockHandler != null) {
                            sTouchLockHandler.postDelayed(this, 1500L);
                        }
                    }
                }
            };
        }
        sTouchLockHandler.removeCallbacks(sTouchLockTickerRunnable);
        sTouchLockHandler.postDelayed(sTouchLockTickerRunnable, 1500L);
    }

    private static void stopTouchLockTicker() {
        if (sTouchLockHandler != null && sTouchLockTickerRunnable != null) {
            sTouchLockHandler.removeCallbacks(sTouchLockTickerRunnable);
        }
    }

    /**
     * 外屏处于「弯折支架模式（内屏已关闭、只留背屏）」时，内屏本身已熄灭不存在误触，
     * 自动放行背屏触摸以便用户正常上下左右滑切卡与交互。
     */
    private static boolean isInnerDisplayOff(Context ctx) {
        try {
            if (ctx == null) {
                return false;
            }
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display d0 = dm.getDisplay(0);
                if (d0 != null && d0.getState() == Display.STATE_OFF) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isRearTouchLocked(Context ctx) {
        if (isInnerDisplayOff(ctx != null ? ctx : currentContext())) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - sTouchLockCheckedAt > SETTING_CACHE_MS) {
            boolean v = false;
            try {
                Context c = ctx != null ? ctx : currentContext();
                if (c != null) {
                    v = Settings.Global.getInt(c.getContentResolver(),
                            UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK, 0) == 1;
                }
            } catch (Throwable ignored) {
            }
            if (v != sTouchLockEnabled) {
                XposedBridge.log(TAG + ": rear touch lock -> " + (v ? "ON (rear touches swallowed)" : "OFF (rear touches restored)"));
            }
            sTouchLockEnabled = v;
            sTouchLockCheckedAt = now;
        }
        return sTouchLockEnabled;
    }

    /**
     * 读取相册轮播/签名视频与 Live 图播放模式：
     * 0 = 循环播放, 1 = 点击重播(默认), 2 = 亮屏播一次, 3 = 仅播一次
     */
    private static int getRearVideoPlayMode(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - sVideoPlayModeCheckedAt > SETTING_CACHE_MS) {
            int mode = 1;
            try {
                Context c = ctx != null ? ctx : currentContext();
                if (c != null) {
                    int m = Settings.Global.getInt(c.getContentResolver(),
                            UnfoldOuterDisplayHook.KEY_REAR_VIDEO_PLAY_MODE, -1);
                    if (m >= 0 && m <= 3) {
                        mode = m;
                    } else {
                        // 兼容旧版 KEY_REAR_VIDEO_LOOP
                        mode = Settings.Global.getInt(c.getContentResolver(),
                                UnfoldOuterDisplayHook.KEY_REAR_VIDEO_LOOP, 0) == 1 ? 0 : 1;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (mode != sVideoPlayMode) {
                XposedBridge.log(TAG + ": rear video play mode -> " + mode
                        + " (0=loop,1=tap,2=screenOn,3=once)");
                boolean loop = (mode == 0);
                for (Object view : snapshotVideoViews()) {
                    applyVideoLooping(view, loop);
                }
            }
            sVideoPlayMode = mode;
            sVideoPlayModeCheckedAt = now;
        }
        return sVideoPlayMode;
    }

    private static boolean isRearVideoLoopEnabled(Context ctx) {
        return getRearVideoPlayMode(ctx) == 0;
    }

    /** VideoElement 自身不持有 Context，通过 mView(IVideoHolder).getView() 取所在 View。 */
    private static Context contextFromVideoElement(Object videoElement) {
        try {
            Object holder = XposedHelpers.getObjectField(videoElement, "mView");
            if (holder != null) {
                Object v = XposedHelpers.callMethod(holder, "getView");
                if (v instanceof View) {
                    Context c = ((View) v).getContext();
                    if (c != null) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return currentContext();
    }

    private static Context currentContext() {
        try {
            Activity a = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
            if (a != null) {
                return a;
            }
            Activity r = sRearActRef != null ? sRearActRef.get() : null;
            if (r != null) {
                return r;
            }
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            return (Context) XposedHelpers.callStaticMethod(atCls, "currentApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isStockFullscreen(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - sStockFullscreenCheckedAt > SETTING_CACHE_MS) {
            boolean v = false;
            try {
                Context c = ctx != null ? ctx : currentContext();
                if (c != null) {
                    v = Settings.Global.getInt(c.getContentResolver(),
                            UnfoldOuterDisplayHook.KEY_REAR_STOCK_FULLSCREEN, 0) == 1;
                }
            } catch (Throwable ignored) {
            }
            if (v != sStockFullscreenEnabled) {
                XposedBridge.log(TAG + ": stock fullscreen viewport -> " + (v ? "ON (native fullscreen)" : "OFF (1208x738 camera-aligned)"));
            }
            sStockFullscreenEnabled = v;
            sStockFullscreenCheckedAt = now;
        }
        return sStockFullscreenEnabled;
    }

    private static void trackVideoView(Object view) {
        if (view == null) {
            return;
        }
        synchronized (sVideoViews) {
            for (int i = sVideoViews.size() - 1; i >= 0; i--) {
                Object v = sVideoViews.get(i).get();
                if (v == null) {
                    sVideoViews.remove(i);
                } else if (v == view) {
                    return;
                }
            }
            sVideoViews.add(new WeakReference<>(view));
        }
    }

    /** 视频/Live图壁纸在「点击重播(mode=1)」模式停在尾帧时，轻点外屏即可从头重新播放一次。 */
    private static void tryReplayStoppedVideoOnTap(Activity act, MotionEvent ev) {
        if (act == null || ev == null || getRearVideoPlayMode(act) != 1) {
            return;
        }
        float dx = Math.abs(ev.getRawX() - sTouchDownX);
        float dy = Math.abs(ev.getRawY() - sTouchDownY);
        long dt = System.currentTimeMillis() - sTouchDownTime;
        if (dt > 300L || dx > 28f || dy > 28f) {
            return;
        }
        replayTrackedVideos("tap");
    }

    private static void replayTrackedVideos(String reason) {
        java.util.List<Object> views = snapshotVideoViews();
        if (views.isEmpty()) {
            return;
        }
        for (Object holder : views) {
            try {
                XposedHelpers.callMethod(holder, "seekTo", 0);
                XposedHelpers.callMethod(holder, "start");
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": " + reason + " -> replayed " + views.size() + " rear video/live wallpaper(s) from head");
    }

    private static java.util.List<Object> snapshotVideoViews() {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        synchronized (sVideoViews) {
            for (int i = sVideoViews.size() - 1; i >= 0; i--) {
                Object v = sVideoViews.get(i).get();
                if (v == null) {
                    sVideoViews.remove(i);
                } else {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 直接对底层 MediaPlayer 设置循环，并同步 MAML 的 mLooping 标记（厂商 setLooping 传参有误，这里按真实意图纠正）。 */
    private static void applyVideoLooping(Object holder, boolean looping) {
        if (holder == null) {
            return;
        }
        try {
            Object mp = XposedHelpers.getObjectField(holder, "mMediaPlayer");
            if (mp instanceof android.media.MediaPlayer) {
                ((android.media.MediaPlayer) mp).setLooping(looping);
            }
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setBooleanField(holder, "mLooping", looping);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 视频壁纸循环播放：
     * 1) 强制 MAML &lt;Video looping="..."&gt; 进入播放器时即为循环（保证首轮就无缝）；
     * 2) onPrepared 后再补一次 setLooping(true)，覆盖 config 未重新执行的情形；
     * 3) onCompletion 兜底 seekTo(0)+start()，确保任何情况下都不会停在最后一帧。
     */
    private static void installRearVideoLoop(ClassLoader cl) {
        try {
            Class<?> veCls = XposedHelpers.findClass("com.miui.maml.elements.video.VideoElement", cl);
            XposedBridge.hookAllMethods(veCls, "config", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Boolean)) {
                        return;
                    }
                    if (!isRearVideoLoopEnabled(contextFromVideoElement(param.thisObject))) {
                        return;
                    }
                    if (!(Boolean) param.args[0]) {
                        param.args[0] = Boolean.TRUE;
                        XposedBridge.log(TAG + ": forced VideoElement.config looping=true for rear wallpaper");
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked VideoElement.config for rear wallpaper looping");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook VideoElement.config failed: " + t);
        }

        hookVideoLoopListeners(cl, "com.miui.maml.elements.video.TextureVideoView");
        hookVideoLoopListeners(cl, "com.miui.maml.elements.video.BaseVideoView");
    }

    private static void hookVideoLoopListeners(ClassLoader cl, String outerName) {
        for (int i = 1; i <= 12; i++) {
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(outerName + "$" + i, cl);
            } catch (Throwable notFound) {
                continue;
            }
            if (cls == null) {
                continue;
            }
            boolean isPrepared = android.media.MediaPlayer.OnPreparedListener.class.isAssignableFrom(cls);
            boolean isCompletion = android.media.MediaPlayer.OnCompletionListener.class.isAssignableFrom(cls);
            if (!isPrepared && !isCompletion) {
                continue;
            }
            XposedBridge.hookAllMethods(cls, isPrepared ? "onPrepared" : "onCompletion", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object holder;
                    try {
                        holder = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    } catch (Throwable ignored) {
                        return;
                    }
                    if (holder == null) {
                        return;
                    }
                    trackVideoView(holder);
                    Context ctx = null;
                    if (holder instanceof View) {
                        try {
                            ctx = ((View) holder).getContext();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!isRearVideoLoopEnabled(ctx)) {
                        return;
                    }
                    // onPrepared / onCompletion 都直接对回调参数里的 MediaPlayer 开启循环：
                    // 只有播放器本身进入 looping，播完才会无缝回到开头（而不是等 onCompletion 再 seek）。
                    try {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof android.media.MediaPlayer) {
                            ((android.media.MediaPlayer) param.args[0]).setLooping(true);
                        }
                    } catch (Throwable ignored) {
                    }
                    applyVideoLooping(holder, true);
                    if (isCompletion) {
                        // onCompletion 结束时播放器停在末尾且 targetState 被置 0，这里立即拉回开头续播
                        try {
                            XposedHelpers.callMethod(holder, "seekTo", 0);
                        } catch (Throwable ignored) {
                        }
                        try {
                            XposedHelpers.callMethod(holder, "start");
                        } catch (Throwable ignored) {
                        }
                        XposedBridge.log(TAG + ": rear wallpaper video ended -> restart from head (looping)");
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + outerName + "$" + i + "." + (isPrepared ? "onPrepared" : "onCompletion") + " for rear wallpaper loop");
        }
    }

    private static boolean isSubScreenActivity(String clsName) {
        return "com.xiaomi.subscreencenter.SubScreenLauncher".equals(clsName)
                || "com.xiaomi.subscreencenter.guide.GuideActivity".equals(clsName);
    }

    private static boolean isFullscreenEnabled(Context ctx) {
        try {
            Context c = ctx;
            if (c == null && sTrackedActivityRef != null) {
                c = sTrackedActivityRef.get();
            }
            if (c != null) {
                return Settings.Global.getInt(c.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN, 0) == 1;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void resetIdleTimeout(Activity act) {
        sMainHandler.removeCallbacks(sTimeoutExitRunnable);
        if (act == null) {
            return;
        }
        try {
            int sec = Settings.Global.getInt(act.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_TIMEOUT_SEC, 0);
            if (sec > 0) {
                sMainHandler.postDelayed(sTimeoutExitRunnable, sec * 1000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void registerBackProximity(Activity act) {
        unregisterBackProximity();
        if (act == null) {
            return;
        }
        try {
            if (Settings.Global.getInt(act.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF, 1) != 1) {
                return;
            }
            SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return;
            }
            // 优先用外屏接近的原始流 33171124：贴桌时 v0≈1023（满量程饱和）；包装值 33171123 仅作回退
            Sensor backProx = findSensorByType(sm, BACK_PROX_FACTORY_TYPE);
            final boolean factoryRaw = backProx != null;
            if (backProx == null) {
                backProx = sm.getDefaultSensor(BACK_PROXIMITY_SENSOR_TYPE, false);
                if (backProx == null) {
                    backProx = sm.getDefaultSensor(BACK_PROXIMITY_SENSOR_TYPE, true);
                }
            }
            if (backProx == null) {
                return;
            }
            sSensorManager = sm;
            sLastBackProxNear = null;
            sBackProxListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event == null || event.values == null || event.values.length == 0) {
                        return;
                    }
                    boolean near = factoryRaw
                            ? event.values[0] >= BACK_PROX_FACTORY_NEAR
                            : event.values[0] < 1.0f;
                    // 原始流为 200ms 连续上报，仅在状态翻转时调度/取消 1.5s 定时器，避免被持续推迟永不触发
                    if (sLastBackProxNear == null || sLastBackProxNear != near) {
                        sLastBackProxNear = near;
                        XposedBridge.log(TAG + ": backProx" + (factoryRaw ? "[raw]" : "[std]")
                                + " v0=" + event.values[0] + " near=" + near
                                + (near ? " -> schedule table-off in 1.5s" : " -> cancel table-off"));
                        if (near) {
                            sMainHandler.removeCallbacks(sTableOffRunnable);
                            sMainHandler.postDelayed(sTableOffRunnable, 1500L);
                        } else {
                            sMainHandler.removeCallbacks(sTableOffRunnable);
                        }
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(sBackProxListener, backProx, SensorManager.SENSOR_DELAY_NORMAL);
            XposedBridge.log(TAG + ": registerBackProximity OK -> " + (factoryRaw ? "RAW " : "STD ") + backProx.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerBackProximity failed: " + t);
        }
    }

    /** factory 隐藏传感器 getDefaultSensor 取不到，需遍历 TYPE_ALL 按 type 精确匹配 */
    private static Sensor findSensorByType(SensorManager sm, int type) {
        try {
            java.util.List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) {
                for (Sensor s : all) {
                    if (s.getType() == type && !s.isWakeUpSensor()) {
                        return s;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": findSensorByType(" + type + ") failed: " + t);
        }
        return null;
    }

    private static void unregisterBackProximity() {
        try {
            if (sSensorManager != null && sBackProxListener != null) {
                sSensorManager.unregisterListener(sBackProxListener);
            }
        } catch (Throwable ignored) {
        }
        sBackProxListener = null;
    }

    private static Runnable sRotateDebounceRunnable;
    private static int sPendingRotationTarget = 0;

    private static int getFlipAlignMode(Context ctx) {
        try {
            Context c = ctx != null ? ctx : currentContext();
            if (c != null) {
                return Settings.Global.getInt(c.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_FLIP_ALIGN, 0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void applyContentRotationAndAlignment(View content, int rot, boolean animate) {
        if (content == null || !content.isAttachedToWindow()) {
            return;
        }
        int alignMode = getFlipAlignMode(content.getContext());
        if (alignMode == 2) {
            rot = 0; // 关闭翻转：保持 0° 正向
        }
        sCurrentContentRotation = rot;
        int cw = content.getWidth();
        int ch = content.getHeight();
        if (cw > 0 && ch > 0) {
            content.setPivotX(cw * 0.5f);
            content.setPivotY(ch * 0.5f);
        }
        // 正向 0°：左侧 410px 留给双摄、内容右贴；
        // 倒置 180°（含弯折时摄像头朝下、内容朝上）：
        //   alignMode==0 (贴左避让): translationX=+410 贴左、右侧留空给双摄；
        //   alignMode==1 (居中满幅): translationX=0 全宽居中。
        float targetX = (rot == 180 && alignMode == 0) ? CAMERA_SAFE_LEFT_PX : 0f;
        float targetY = (rot == 180)
                ? (OUTER_PHYSICAL_HEIGHT - TARGET_HEIGHT)
                : sBurnShiftY;
        if (animate) {
            content.animate().rotation(rot).translationX(targetX).translationY(targetY).setDuration(280L).start();
            triggerImmediateGlassRefresh(320L);
        } else {
            content.animate().cancel();
            content.setRotation(rot);
            content.setTranslationX(targetX);
            content.setTranslationY(targetY);
        }
    }

    private static void registerGravityAutoRotate(Activity act) {
        unregisterGravityAutoRotate();
        if (act == null) {
            return;
        }
        try {
            // 半折状态（弯折支架/桌钟）固定旋转 180°（摄像头朝下，内容自动贴左避让右侧双摄）
            if (sIsHalfOpen) {
                sPendingRotationTarget = 180;
                View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                applyContentRotationAndAlignment(content, 180, false);
                return;
            }
            // 默认关闭重力自动倒置旋转（0），避免倾斜握持时角度抖动误翻转
            if (Settings.Global.getInt(act.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE, 0) != 1) {
                sPendingRotationTarget = 0;
                View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                applyContentRotationAndAlignment(content, 0, false);
                return;
            }
            sOrientationListener = new android.view.OrientationEventListener(act, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (sIsHalfOpen || orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
                        return;
                    }
                    // 严格滞回区间 + 600ms 稳定防抖：正向(325°..360° / 0°..35°) -> 0°，倒置(145°..215°) -> 180°
                    int target = sPendingRotationTarget;
                    if (orientation >= 145 && orientation <= 215) {
                        target = 180;
                    } else if (orientation <= 35 || orientation >= 325) {
                        target = 0;
                    } else {
                        return;
                    }
                    if (target != sPendingRotationTarget) {
                        sPendingRotationTarget = target;
                        if (sRotateDebounceRunnable != null) {
                            sMainHandler.removeCallbacks(sRotateDebounceRunnable);
                        }
                        final int rot = target;
                        sRotateDebounceRunnable = () -> {
                            if (sIsHalfOpen || rot == sCurrentContentRotation) {
                                return;
                            }
                            View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                            applyContentRotationAndAlignment(content, rot, true);
                        };
                        sMainHandler.postDelayed(sRotateDebounceRunnable, 600L);
                    }
                }
            };
            if (sOrientationListener.canDetectOrientation()) {
                sOrientationListener.enable();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerGravityAutoRotate failed: " + t);
        }
    }

    private static void unregisterGravityAutoRotate() {
        if (sRotateDebounceRunnable != null) {
            sMainHandler.removeCallbacks(sRotateDebounceRunnable);
            sRotateDebounceRunnable = null;
        }
        try {
            if (sOrientationListener != null) {
                sOrientationListener.disable();
            }
        } catch (Throwable ignored) {
        }
        sOrientationListener = null;
    }

    private static boolean isFoldedMode(Activity act) {
        try {
            return act != null && act.getIntent() != null
                    && act.getIntent().getBooleanExtra(UnfoldOuterDisplayHook.EXTRA_FOLDED_MODE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 防止 GuidePanel.E() 首次 onResume 时调用 PowerManager.goToSleep() 直接把整机息屏（y0=false），
     * 让用户在 Flip 2 展开态或调试入口拉起官方背屏引导时保持亮屏可交互。
     */
    private static void installGuidePanelHook(ClassLoader cl) {
        try {
            Class<?> gpCls = XposedHelpers.findClass("com.xiaomi.subscreencenter.guide.GuidePanel", cl);
            XposedHelpers.findAndHookMethod(gpCls, "E", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "y0", false);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked GuidePanel.E() to suppress initial goToSleep");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook GuidePanel.E() failed: " + t);
        }
    }

    /**
     * 官方新手引导（GuideActivity）中「左滑开启相机」步骤（GuidePanel.v0 == 0 或 1）：
     * 轻点一下直接隐藏当前引导并跳过到下一步（O() -> N()），避免被 Flip 2 相机限制卡住。
     */
    private static boolean trySkipGuideCameraStep(Activity act, MotionEvent ev) {
        if (act == null || ev == null || !"com.xiaomi.subscreencenter.guide.GuideActivity".equals(act.getClass().getName())) {
            return false;
        }
        float dx = Math.abs(ev.getRawX() - sTouchDownX);
        float dy = Math.abs(ev.getRawY() - sTouchDownY);
        long dt = System.currentTimeMillis() - sTouchDownTime;
        if (dt > 350L || dx > 42f || dy > 42f) {
            return false;
        }
        try {
            Object panel = XposedHelpers.getObjectField(act, "w");
            if (panel == null) {
                return false;
            }
            int stage = XposedHelpers.getIntField(panel, "v0");
            if (stage == 0 || stage == 1) {
                XposedBridge.log(TAG + ": GuideActivity camera stage=" + stage + " tapped -> skipping directly to next stage");
                try {
                    XposedHelpers.callMethod(panel, "O");
                } catch (Throwable ignored) {
                }
                XposedHelpers.callMethod(panel, "N");
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": trySkipGuideCameraStep failed: " + t);
        }
        return false;
    }

    private static void fadeInWindow(Activity act, long delayMs, long durationMs) {
        if (act == null || act.isFinishing()) {
            return;
        }
        final android.view.Window win = act.getWindow();
        if (win == null) {
            return;
        }
        try {
            if (sWindowFadeAnimator != null) {
                sWindowFadeAnimator.cancel();
            }
            android.view.WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.alpha = 0f;
            win.setAttributes(wlp);
            View decor = win.getDecorView();
            Runnable startAnim = () -> {
                if (act.isFinishing()) {
                    return;
                }
                if (sWindowFadeAnimator != null) {
                    sWindowFadeAnimator.cancel();
                }
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(durationMs);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
                va.addUpdateListener(a -> {
                    try {
                        if (act.isFinishing()) {
                            return;
                        }
                        android.view.WindowManager.LayoutParams lp = win.getAttributes();
                        lp.alpha = (Float) a.getAnimatedValue();
                        win.setAttributes(lp);
                    } catch (Throwable ignored) {
                    }
                });
                sWindowFadeAnimator = va;
                va.start();
            };
            if (decor != null) {
                decor.postDelayed(startAnim, delayMs);
            } else {
                sMainHandler.postDelayed(startAnim, delayMs);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fadeOutAndFinish(Activity act) {
        fadeOutAndFinish(act, 320L);
    }

    private static void fadeOutAndFinish(Activity act, long durationMs) {
        if (act == null || act.isFinishing()) {
            return;
        }
        sMainHandler.removeCallbacks(sGlassRefreshRunnable);
        try {
            final android.view.Window win = act.getWindow();
            if (win != null) {
                if (sWindowFadeAnimator != null) {
                    sWindowFadeAnimator.cancel();
                }
                final float startAlpha = win.getAttributes() != null ? win.getAttributes().alpha : 1f;
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(startAlpha, 0f);
                va.setDuration(durationMs);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
                va.addUpdateListener(a -> {
                    try {
                        if (act.isFinishing()) {
                            return;
                        }
                        android.view.WindowManager.LayoutParams lp = win.getAttributes();
                        lp.alpha = (Float) a.getAnimatedValue();
                        win.setAttributes(lp);
                    } catch (Throwable ignored) {
                    }
                });
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        try {
                            act.finishAndRemoveTask();
                            act.overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sWindowFadeAnimator = va;
                va.start();
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            act.finishAndRemoveTask();
        } catch (Throwable ignored) {
        }
    }

    private static android.animation.ValueAnimator sWindowFadeAnimator;

    private static boolean finishIfNotOnSecondaryDisplay(Activity act) {
        try {
            Display d = act.getDisplay();
            if (d != null && d.getDisplayId() != 1) {
                if (isFoldedMode(act)) {
                    // 合盖轻拍指纹键显式启动的模式：允许显示在锁屏之上、延伸至双摄异形区满铺全屏并隐藏状态栏/导航栏
                    if (Build.VERSION.SDK_INT >= 27) {
                        act.setShowWhenLocked(true);
                        act.setTurnScreenOn(true);
                    }
                    if (Build.VERSION.SDK_INT >= 30 && act.getWindow() != null) {
                        act.getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                        android.view.WindowManager.LayoutParams wlp = act.getWindow().getAttributes();
                        wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                        act.getWindow().setAttributes(wlp);
                        android.view.WindowInsetsController ic = act.getWindow().getInsetsController();
                        if (ic != null) {
                            ic.hide(android.view.WindowInsets.Type.statusBars()
                                    | android.view.WindowInsets.Type.navigationBars());
                            ic.setSystemBarsBehavior(
                                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        }
                    }
                    return false;
                }
                XposedBridge.log(TAG + ": SubScreenLauncher appeared on displayId=" + d.getDisplayId() + " -> finishAndRemoveTask()");
                act.finishAndRemoveTask();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void registerFinishReceiver(Activity act) {
        if (sFinishReceiverRegistered || act == null) {
            return;
        }
        try {
            Context appCtx = act.getApplicationContext();
            if (appCtx == null) {
                return;
            }
            IntentFilter filter = new IntentFilter(UnfoldOuterDisplayHook.ACTION_FINISH_REAR_LAUNCHER);
            filter.addAction(UnfoldOuterDisplayHook.ACTION_HALF_OPEN_STATE);
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent != null ? intent.getAction() : null;
                    if (UnfoldOuterDisplayHook.ACTION_HALF_OPEN_STATE.equals(action)) {
                        boolean half = intent.getBooleanExtra(UnfoldOuterDisplayHook.EXTRA_HALF_OPEN, false);
                        sIsHalfOpen = half;
                        XposedBridge.log(TAG + ": half-open state -> " + half + " (lock 180deg=" + half + ")");
                        sMainHandler.post(() -> {
                            Activity a = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
                            View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                            if (half) {
                                applyContentRotationAndAlignment(content, 180, true);
                            } else {
                                applyContentRotationAndAlignment(content, 0, true);
                                if (a != null && !a.isFinishing()) {
                                    registerGravityAutoRotate(a);
                                }
                            }
                        });
                        return;
                    }
                    Activity a = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
                    if (a != null && !a.isFinishing()) {
                        boolean silent = intent != null && intent.getBooleanExtra(UnfoldOuterDisplayHook.EXTRA_SILENT_FINISH, false);
                        XposedBridge.log(TAG + ": received ACTION_FINISH_REAR_LAUNCHER (silent=" + silent + ") -> finish");
                        if (!silent) {
                            fadeOutAndFinish(a, isFoldedMode(a) ? 380L : 220L);
                        } else {
                            sMainHandler.removeCallbacks(sGlassRefreshRunnable);
                            a.finishAndRemoveTask();
                            a.overridePendingTransition(0, 0);
                        }
                    }
                }
            }, filter, 2);
            sFinishReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static void updateAmbientFrostedGlass(View content, ImageView glassBg) {
        try {
            int cw = content.getWidth();
            int ch = content.getHeight();
            if (cw <= 0 || ch <= 0 || content.getAlpha() < 0.95f) {
                return;
            }
            final int sw = 48;
            final int sh = 28;
            Activity act = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
            if (act != null && act.getWindow() != null && act.getWindow().getAttributes() != null
                    && act.getWindow().getAttributes().alpha < 0.95f) {
                return;
            }
            // 优先使用 PixelCopy 从 GPU 合成帧直接采样：兼容 AI 应用卡（MamlView / WebView / SurfaceView）
            if (Build.VERSION.SDK_INT >= 26 && act != null && act.getWindow() != null && !act.isFinishing()) {
                try {
                    int top = Math.max(0, Math.round(content.getY()));
                    android.graphics.Rect srcRect = new android.graphics.Rect(0, top, cw, top + ch);
                    final Bitmap thumb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    android.view.PixelCopy.request(act.getWindow(), srcRect, thumb, copyResult -> {
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            applyMirroredGlassBitmap(thumb, glassBg);
                        } else {
                            if (!thumb.isRecycled()) {
                                thumb.recycle();
                            }
                            fallbackDrawAmbientGlass(content, glassBg, sw, sh);
                        }
                    }, sMainHandler);
                    return;
                } catch (Throwable ignored) {
                }
            }
            fallbackDrawAmbientGlass(content, glassBg, sw, sh);
        } catch (Throwable ignored) {
        }
    }

    private static void fallbackDrawAmbientGlass(View content, ImageView glassBg, int sw, int sh) {
        try {
            int cw = content.getWidth();
            int ch = content.getHeight();
            if (cw <= 0 || ch <= 0) {
                return;
            }
            Bitmap thumb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(thumb);
            c.scale(sw / (float) cw, sh / (float) ch);
            content.draw(c);
            applyMirroredGlassBitmap(thumb, glassBg);
        } catch (Throwable ignored) {
        }
    }

    private static void applyMirroredGlassBitmap(Bitmap thumb, ImageView glassBg) {
        try {
            if (thumb == null || thumb.isRecycled() || glassBg == null) {
                return;
            }
            int sw = thumb.getWidth();
            int sh = thumb.getHeight();
            // 垂直镜像延展，使 738px 底部边缘色彩与下方毛玻璃无缝衔接
            Matrix m = new Matrix();
            m.preScale(1f, -1f);
            Bitmap mirrored = Bitmap.createBitmap(thumb, 0, sh / 3, sw, sh - sh / 3, m, false);
            thumb.recycle();

            Bitmap prev = sLastGlassBitmap;
            sLastGlassBitmap = mirrored;
            saveGlassCacheAsync(glassBg.getContext(), mirrored);
            if (prev != null && prev != mirrored && !prev.isRecycled()) {
                android.content.res.Resources res = glassBg.getResources();
                android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(
                        new android.graphics.drawable.Drawable[]{
                                new android.graphics.drawable.BitmapDrawable(res, prev),
                                new android.graphics.drawable.BitmapDrawable(res, mirrored)
                        });
                td.setCrossFadeEnabled(false);
                glassBg.setImageDrawable(td);
                td.startTransition(260);
            } else {
                glassBg.setImageBitmap(mirrored);
                if (glassBg.getAlpha() < 0.99f) {
                    glassBg.animate().cancel();
                    glassBg.animate()
                            .alpha(1f)
                            .setDuration(320L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
                            .start();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile long sLastGlassSaveTime = 0L;

    private static void saveGlassCacheAsync(Context ctx, Bitmap bmp) {
        if (ctx == null || bmp == null || bmp.isRecycled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastGlassSaveTime < 3000L) {
            return;
        }
        sLastGlassSaveTime = now;
        final java.io.File out = new java.io.File(ctx.getCacheDir(), "madrid_last_glass.png");
        final Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
        new Thread(() -> {
            if (copy == null) {
                return;
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                copy.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } catch (Throwable ignored) {
            } finally {
                if (!copy.isRecycled()) {
                    copy.recycle();
                }
            }
        }, "madrid-glass-cache").start();
    }

    private static Bitmap loadGlassCacheSync(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            java.io.File f = new java.io.File(ctx.getCacheDir(), "madrid_last_glass.png");
            if (f.exists() && f.length() > 0) {
                return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyCameraAlignedViewport(Activity act, boolean fadeIn) {
        try {
            if (act.getWindow() == null) {
                return;
            }
            boolean glassFill = isFullscreenEnabled(act);
            boolean folded = isFoldedMode(act);
            View decor = act.getWindow().getDecorView();
            if (decor != null) {
                // 合盖透明淡入淡出模式下保持透明背景，使退出淡出时直接透出底层原厂锁屏而非淡成黑屏
                decor.setBackgroundColor(folded ? Color.TRANSPARENT : Color.BLACK);
            }
            View content = act.findViewById(android.R.id.content);
            if (content != null) {
                ViewGroup.LayoutParams lp = content.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                    flp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    flp.height = TARGET_HEIGHT;
                    flp.gravity = Gravity.TOP;
                    content.setLayoutParams(flp);
                } else if (lp != null) {
                    lp.height = TARGET_HEIGHT;
                    content.setLayoutParams(lp);
                }
                content.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), VIEWPORT_CORNER_RADIUS_PX);
                    }
                });
                content.setClipToOutline(true);
                content.setTranslationY(sBurnShiftY);
                sMainHandler.removeCallbacks(sBurnInRunnable);
                sMainHandler.postDelayed(sBurnInRunnable, getBurnInIntervalMs(getBurnInMode(act)));
                sTrackedContentRef = new WeakReference<>(content);

                // 实时采样背屏色彩的毛玻璃扩展背板（挂载在 decorView 最底层）
                if (decor instanceof ViewGroup) {
                    ViewGroup decorGroup = (ViewGroup) decor;
                    View existing = decorGroup.findViewWithTag("madrid_glass_ext_container");
                    if (glassFill) {
                        FrameLayout container;
                        ImageView glassBg;
                        if (existing instanceof FrameLayout) {
                            container = (FrameLayout) existing;
                            glassBg = (ImageView) container.findViewWithTag("madrid_glass_ext_img");
                        } else {
                            container = new FrameLayout(act);
                            container.setTag("madrid_glass_ext_container");
                            glassBg = new ImageView(act);
                            glassBg.setTag("madrid_glass_ext_img");
                            glassBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            if (Build.VERSION.SDK_INT >= 31) {
                                // RenderEffect 仅初始化绑定一次，避免每次采样重建 GPU 模糊管线导致掉帧卡顿
                                glassBg.setRenderEffect(RenderEffect.createBlurEffect(55f, 55f, Shader.TileMode.CLAMP));
                            }
                            container.addView(glassBg, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                            View scrim = new View(act);
                            scrim.setBackground(new GradientDrawable(
                                    GradientDrawable.Orientation.TOP_BOTTOM,
                                    new int[]{
                                            Color.argb(90, 0, 0, 0),
                                            Color.argb(145, 0, 0, 0),
                                            Color.argb(205, 0, 0, 0)
                                    }));
                            container.addView(scrim, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                            decorGroup.addView(container, 0, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        }
                        container.setVisibility(View.VISIBLE);
                        if (glassBg != null) {
                            sGlassBgRef = new WeakReference<>(glassBg);
                            // 优先复用内存缓存，其次读取本地 2KB 磁盘缓存，确保重启后首次打开也第 0 帧即带毛玻璃
                            if (sLastGlassBitmap == null || sLastGlassBitmap.isRecycled()) {
                                sLastGlassBitmap = loadGlassCacheSync(act);
                            }
                            if (sLastGlassBitmap != null && !sLastGlassBitmap.isRecycled()) {
                                glassBg.setAlpha(1f);
                                glassBg.setImageBitmap(sLastGlassBitmap);
                            } else {
                                glassBg.setAlpha(0f);
                            }
                            if (fadeIn) {
                                container.animate().cancel();
                                container.setAlpha(1f);
                                container.setTranslationY(0f);
                                // 关键：等 480ms（60ms 首帧就绪 + 420ms 整窗淡入）后再 PixelCopy，避免采到半透明帧导致闪烁
                                triggerImmediateGlassRefresh(540L);
                            } else {
                                container.setAlpha(1f);
                                container.setTranslationY(0f);
                            }
                        }
                    } else if (existing != null) {
                        existing.setVisibility(View.GONE);
                        sMainHandler.removeCallbacks(sGlassRefreshRunnable);
                    }
                }

                if (Build.VERSION.SDK_INT >= 30 && act.getWindow() != null) {
                    android.view.WindowInsetsController ic = act.getWindow().getInsetsController();
                    if (ic != null) {
                        ic.hide(android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                        ic.setSystemBarsBehavior(
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                }

                final android.view.Window win = act.getWindow();
                if (fadeIn && win != null) {
                    if (sWindowFadeAnimator != null) {
                        sWindowFadeAnimator.cancel();
                    }
                    android.view.WindowManager.LayoutParams wlp = win.getAttributes();
                    wlp.alpha = 0f;
                    win.setAttributes(wlp);
                    // 延迟 60ms 等首帧 MamlView + 毛玻璃全部完成绘制后再起手淡入，彻底消除半渲染突跳
                    content.postDelayed(() -> {
                        if (act.isFinishing()) {
                            return;
                        }
                        if (sWindowFadeAnimator != null) {
                            sWindowFadeAnimator.cancel();
                        }
                        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                        va.setDuration(420L);
                        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
                        va.addUpdateListener(a -> {
                            try {
                                if (act.isFinishing()) {
                                    return;
                                }
                                android.view.WindowManager.LayoutParams wlpCur = win.getAttributes();
                                wlpCur.alpha = (Float) a.getAnimatedValue();
                                win.setAttributes(wlpCur);
                            } catch (Throwable ignored) {
                            }
                        });
                        sWindowFadeAnimator = va;
                        va.start();
                    }, 60L);
                } else if (!fadeIn && win != null) {
                    // onCreate 阶段先置整窗 alpha=0，避免 onCreate -> onResume 之间首帧闪现
                    android.view.WindowManager.LayoutParams wlp = win.getAttributes();
                    wlp.alpha = 0f;
                    win.setAttributes(wlp);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyCameraAlignedViewport failed: " + t);
        }
    }

    public static void installSettings(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> infoCls = XposedHelpers.findClass("miui.util.MiuiMultiDisplayTypeInfo", cl);
            // 仅放开 isIndependentRearDevice()，保留 isFlipDevice()==true 以与 Flip 2 原生「外屏」设置共存
            XposedHelpers.findAndHookMethod(infoCls, "isIndependentRearDevice",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log(TAG + ": Settings hooked isIndependentRearDevice() -> true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Settings hook failed: " + t);
        }

        // 在系统「设置 → 更多设置 → 快捷手势 → 轻敲背面」中注入「开启/关闭小米18背屏」选项
        try {
            Class<?> keyCls = XposedHelpers.findClass("com.android.settings.MiuiShortcut$Key", cl);
            XposedBridge.hookAllMethods(keyCls, "initFunctionsAndValues", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 4) {
                        return;
                    }
                    boolean isBackTap = false;
                    for (int i = 3; i < param.args.length; i++) {
                        if ("back_double_tap".equals(param.args[i]) || "back_triple_tap".equals(param.args[i])) {
                            isBackTap = true;
                            break;
                        }
                    }
                    if (isBackTap && param.args[1] instanceof java.util.List && param.args[2] instanceof java.util.List) {
                        java.util.List<String> labels = (java.util.List<String>) param.args[1];
                        java.util.List<String> values = (java.util.List<String>) param.args[2];
                        if (!values.contains(UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR)) {
                            labels.add("开启/关闭小米18背屏");
                            values.add(UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": Settings hooked MiuiShortcut$Key.initFunctionsAndValues for BackTap");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Settings hook MiuiShortcut$Key failed: " + t);
        }
        try {
            Class<?> btFragCls = XposedHelpers.findClass("com.android.settings.BackTapSettingsFragment", cl);
            XposedHelpers.findAndHookMethod(btFragCls, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        appendBackTapOption(XposedHelpers.getObjectField(param.thisObject, "mBackDoubleTap"));
                        appendBackTapOption(XposedHelpers.getObjectField(param.thisObject, "mBackTripleTap"));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": appendBackTapOption failed: " + t);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void appendBackTapOption(Object dropDownPref) {
        if (dropDownPref == null) {
            return;
        }
        CharSequence[] entries = (CharSequence[]) XposedHelpers.callMethod(dropDownPref, "getEntries");
        CharSequence[] values = (CharSequence[]) XposedHelpers.callMethod(dropDownPref, "getEntryValues");
        if (entries == null || values == null) {
            return;
        }
        for (CharSequence v : values) {
            if (UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR.equals(String.valueOf(v))) {
                return;
            }
        }
        CharSequence[] newEntries = new CharSequence[entries.length + 1];
        CharSequence[] newValues = new CharSequence[values.length + 1];
        System.arraycopy(entries, 0, newEntries, 0, entries.length);
        System.arraycopy(values, 0, newValues, 0, values.length);
        newEntries[entries.length] = "开启/关闭小米18背屏";
        newValues[values.length] = UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR;
        XposedHelpers.callMethod(dropDownPref, "setEntries", (Object) newEntries);
        XposedHelpers.callMethod(dropDownPref, "setEntryValues", (Object) newValues);
    }

    private static final ThreadLocal<Boolean> sSpoofMadridNetwork = new ThreadLocal<>();
    private static volatile boolean sInRearScreenThemePage = false;
    private static volatile boolean sThemeEditorFullscreenMode = false;
    private static final String EXTRA_MADRID_FULLSCREEN_CARD = "madrid_fullscreen_card";

    /**
     * HyperOS 4 的「轻敲背面」设置页在 com.miui.securitycore（h2.c Fragment），
     * 其下拉选项数据源来自 f2.t.e(String[] actions, int orderType) 返回的 LinkedHashMap<functionKey, displayName>。
     * 直接在 f2.t.e() 返回前注入「开启/关闭小米18背屏」，使：
     * 1) 原生「轻敲两下 / 轻敲三下」菜单自然出现该选项；
     * 2) h2.c.onResume() 校验 containsKey() 为 true，不再把已绑定的 madrid_toggle_rear_screen 清空为 none！
     */
    public static void installSecurityCore(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> helperCls = XposedHelpers.findClass("f2.t", cl);
            XposedHelpers.findAndHookMethod(helperCls, "e", String[].class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String[] actions = (String[]) param.args[0];
                        Object res = param.getResult();
                        if (actions == null || !(res instanceof java.util.LinkedHashMap)) {
                            return;
                        }
                        boolean isSupportedShortcut = false;
                        for (String a : actions) {
                            if ("fingerprint_double_tap".equals(a)
                                    || "back_double_tap".equals(a)
                                    || "back_triple_tap".equals(a)) {
                                isSupportedShortcut = true;
                                break;
                            }
                        }
                        if (!isSupportedShortcut) {
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        java.util.LinkedHashMap<String, String> orig = (java.util.LinkedHashMap<String, String>) res;
                        if (orig.containsKey(UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR)) {
                            return;
                        }
                        java.util.LinkedHashMap<String, String> updated = new java.util.LinkedHashMap<>();
                        boolean inserted = false;
                        for (java.util.Map.Entry<String, String> entry : orig.entrySet()) {
                            updated.put(entry.getKey(), entry.getValue());
                            if (!inserted && "none".equals(entry.getKey())) {
                                updated.put(UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR, "开启/关闭小米18背屏");
                                inserted = true;
                            }
                        }
                        if (!inserted) {
                            java.util.LinkedHashMap<String, String> prepended = new java.util.LinkedHashMap<>();
                            prepended.put(UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR, "开启/关闭小米18背屏");
                            prepended.putAll(updated);
                            updated = prepended;
                        }
                        param.setResult(updated);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": SecurityCore f2.t.e inject failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": SecurityCore hooked f2.t.e for native BackTap menu & persistence");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SecurityCore hook f2.t.e failed: " + t);
        }
    }

    public static void installThemeManager(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> infoCls = XposedHelpers.findClass("miui.util.MiuiMultiDisplayTypeInfo", cl);
            XposedHelpers.findAndHookMethod(infoCls, "isIndependentRearDevice",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log(TAG + ": ThemeManager hooked isIndependentRearDevice() -> true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook failed: " + t);
        }
        // 解锁小米 18 原厂「应用卡 / AI 轻应用生成」入口（RearScreenSettingModule$Companion.k()）
        try {
            Class<?> compCls = XposedHelpers.findClass("com.personalizedEditor.helper.settings.RearScreenSettingModule$Companion", cl);
            XposedHelpers.findAndHookMethod(compCls, "k", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log(TAG + ": ThemeManager unlocked RearScreenSettingModule$Companion.k() -> true (AI AppCard)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unlock RearScreenSettingModule$Companion failed: " + t);
        }
        try {
            Class<?> aiCtrlCls = XposedHelpers.findClass("com.rearScreen.subscreen.RearScreenAiAppController", cl);
            XposedHelpers.findAndHookMethod(aiCtrlCls, "k", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log(TAG + ": ThemeManager unlocked RearScreenAiAppController.k() -> true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unlock RearScreenAiAppController failed: " + t);
        }
        // 在 aiapp / rearScreen 云端请求栈内定向将设备代号伪装为 madrid，避免云端返回「当前内容无法展示」
        try {
            Class<?> spCls = XposedHelpers.findClass("android.os.SystemProperties", cl);
            XC_MethodHook deviceSpoofHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if ("ro.product.device".equals(key) || "ro.product.mod_device".equals(key)
                            || "ro.product.name".equals(key)) {
                        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                            String cn = ste.getClassName();
                            if (cn.contains("rearScreen") || cn.contains("RearScreen")
                                    || cn.contains("subscreen") || cn.contains("SubScreen")
                                    || cn.contains("aiapp") || cn.contains("AiApp")) {
                                param.setResult("madrid");
                                return;
                            }
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(spCls, "get", String.class, deviceSpoofHook);
            XposedHelpers.findAndHookMethod(spCls, "get", String.class, String.class, deviceSpoofHook);
            XposedBridge.log(TAG + ": ThemeManager armed scoped madrid device spoof for RearScreen");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager device spoof failed: " + t);
        }

        // 追踪用户是否正位于主题商店「个性背屏」相关页面（含相册轮播 / 签名 / AI壁纸 / 萌宠等），
        // 处于这些页面期间，所有 OkHttp 与 OnlineService 请求统一伪装为 madrid，彻底根治「相册轮播网络异常」
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (a != null) {
                        String cn = a.getClass().getName();
                        if (cn.contains("rearScreen") || cn.contains("RearScreen")
                                || cn.contains("subscreen") || cn.contains("SubScreen")
                                || cn.contains("PersonalizedEditor") || cn.contains("personalizedEditor")) {
                            sInRearScreenThemePage = true;
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (a != null) {
                        String cn = a.getClass().getName();
                        if (cn.contains("rearScreen") || cn.contains("RearScreen")
                                || cn.contains("subscreen") || cn.contains("SubScreen")
                                || cn.contains("PersonalizedEditor") || cn.contains("personalizedEditor")) {
                            sInRearScreenThemePage = false;
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 修复背屏个性化（相册轮播 / 签名 / AI 应用卡 / 萌宠等）云端请求「当前内容无法显示 / 网络异常」：
        // 1) DeviceUtils.a() 恒返回 true，使 ParamInterceptor/OnlineService 正确上报 isSupportRearScreen=true 与 rearScreenVersion；
        // 2) 拦截 ParamInterceptor，在背屏相关请求或处于背屏个性化页面期间将 device -> "madrid"、model -> "25098PN5AC"、deviceType -> "MIPHONE"；
        //    不影响 Flip 2 普通主题商店首页请求。
        try {
            Class<?> paramIntCls = XposedHelpers.findClass(
                    "com.android.thememanager.basemodule.network.theme.interceptors.ParamInterceptor", cl);
            XposedBridge.hookAllMethods(paramIntCls, "k", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1 || param.args[0] == null) {
                        return;
                    }
                    try {
                        Object req = XposedHelpers.callMethod(param.args[0], "ld6");
                        Object urlObj = req != null ? XposedHelpers.callMethod(req, "cdj") : null;
                        String url = urlObj != null ? String.valueOf(urlObj) : "";
                        String low = url.toLowerCase();
                        boolean shouldSpoof = sInRearScreenThemePage
                                || low.contains("ai_generated_app")
                                || low.contains("rearscreen")
                                || low.contains("rear_screen")
                                || low.contains("rear_")
                                || low.contains("subscreen")
                                || low.contains("aiapp")
                                || low.contains("ai_app")
                                || low.contains("carousel")
                                || low.contains("signature")
                                || low.contains("cutepet")
                                || low.contains("groupphoto");
                        if (!shouldSpoof) {
                            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                                String cn = ste.getClassName();
                                if (cn.contains("rearScreen") || cn.contains("RearScreen")
                                        || cn.contains("subscreen") || cn.contains("SubScreen")
                                        || cn.contains("aiapp") || cn.contains("AiApp")) {
                                    shouldSpoof = true;
                                    break;
                                }
                            }
                        }
                        if (shouldSpoof) {
                            sSpoofMadridNetwork.set(Boolean.TRUE);
                            XposedBridge.log(TAG + ": ParamInterceptor spoofing device=madrid for " + url);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sSpoofMadridNetwork.remove();
                }
            });

            Class<?> devUtilsCls = XposedHelpers.findClass(
                    "com.android.thememanager.basemodule.utils.DeviceUtils", cl);
            XposedHelpers.findAndHookMethod(devUtilsCls, "y", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get()) || sInRearScreenThemePage) {
                        param.setResult("madrid");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(devUtilsCls, "cdj", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get()) || sInRearScreenThemePage) {
                        param.setResult("25098PN5AC");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(devUtilsCls, "t8r", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get()) || sInRearScreenThemePage) {
                        param.setResult("MIPHONE");
                    }
                }
            });
            // DeviceUtils.a() 表示设备支持独立背屏（isIndependentRearDevice），恒返回 true 避免 rearScreenVersion=0
            XposedHelpers.findAndHookMethod(devUtilsCls, "a",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log(TAG + ": ThemeManager hooked ParamInterceptor + DeviceUtils for RearScreen cloud APIs");

            // 修复 AI 应用卡下载失败（fetchDownloadUrl: apiCode=-1）：
            // fetchDownloadUrl 走 OnlineService.a9() + NetworkHelper，不经过 OkHttp ParamInterceptor；
            // 在 fetchDownloadUrl$2.invokeSuspend 期间同样激活 sSpoofMadridNetwork，确保 OnlineService 传 device=madrid！
            try {
                Class<?> dlCls = XposedHelpers.findClass(
                        "com.rearScreen.aiapp.impl.RearScreenAiAppDownloadProviderImpl$fetchDownloadUrl$2", cl);
                XposedBridge.hookAllMethods(dlCls, "invokeSuspend", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sSpoofMadridNetwork.set(Boolean.TRUE);
                        XposedBridge.log(TAG + ": fetchDownloadUrl spoofing device=madrid for OnlineService.a9");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sSpoofMadridNetwork.remove();
                    }
                });
                XposedBridge.log(TAG + ": ThemeManager hooked fetchDownloadUrl$2 for community AI AppCard download");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ThemeManager hook fetchDownloadUrl$2 failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager ParamInterceptor hook failed: " + t);
        }

        // 修复背屏个性化（相册轮播 / 签名 / AI 应用卡详情预览）因 Flip 2 物理比例 1208/1392=0.868
        // 被 RearScreenUtil 判定为 "unknown" 导致云端返回空（「当前内容无法显示」）：
        // - 云端查询接口（qrj() / p()）始终返回 "3x2"，确保资源列表完整；
        // - 预览与裁切比例（x2() / Companion.y()）按当前选中的卡片动态切换：
        //   「半屏相册轮播和签名」= 1208×738 (1.637)，「全屏相册轮播和签名」= 1208×1392 (0.868)。
        try {
            Class<?> rsuCls = XposedHelpers.findClass("com.android.thememanager.basemodule.utils.RearScreenUtil", cl);
            XC_MethodHook ratioHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    float h = sThemeEditorFullscreenMode ? (float) OUTER_PHYSICAL_HEIGHT : (float) TARGET_HEIGHT;
                    param.setResult(TARGET_WIDTH / h);
                }
            };
            XposedHelpers.findAndHookMethod(rsuCls, "x2", ratioHook);
            XposedHelpers.findAndHookMethod(rsuCls, "qrj",
                    XC_MethodReplacement.returnConstant("3x2"));
            XposedHelpers.findAndHookMethod(rsuCls, "p",
                    XC_MethodReplacement.returnConstant("{\"size\":\"3x2\"}"));
            Class<?> rsuCompCls = XposedHelpers.findClass("com.android.thememanager.basemodule.utils.RearScreenUtil$Companion", cl);
            try {
                XposedHelpers.findAndHookMethod(rsuCompCls, "x2", ratioHook);
            } catch (Throwable ignored) {
            }
            XposedHelpers.findAndHookMethod(rsuCompCls, "y", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof android.util.DisplayMetrics) {
                        android.util.DisplayMetrics dm = (android.util.DisplayMetrics) res;
                        if (dm.widthPixels == TARGET_WIDTH) {
                            dm.heightPixels = sThemeEditorFullscreenMode ? OUTER_PHYSICAL_HEIGHT : TARGET_HEIGHT;
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": ThemeManager hooked RearScreenUtil -> dynamic half(1208x738) / full(1208x1392) preview + 3x2 cloud API");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook RearScreenUtil failed: " + t);
        }

        // 官方背屏个性化卡片增强：
        // 1) 将「壁纸与签名」/「相册轮播」改名为「半屏相册轮播和签名」（预览为半屏 1208×738）；
        // 2) 克隆一组同名模块「全屏相册轮播和签名」（功能完全一致，进入预览/裁切时自动切换为全屏 1208×1392）；
        // 3) 将「选择图片」等文案替换为「选择图片、视频和 Live 图」。
        hookThemeManagerHalfAndFullCards(cl);

        // 解锁官方背屏相册选择器直接选择「视频」与「Live 动态照片」：
        // 1) RearScreenFunction.toq() (isRearScreenSupportVideo) 与 LivePhotoUtils.n() 恒返回 true；
        // 2) ThirdPartyIntentUtils.cdj(activity, imageOnly, ...) 在背屏编辑/相册轮播场景下强制 imageOnly=false，
        //    使相册选择器 MIME 类型变为 "image/*, video/*"，原生支持选照片、Live 实况图与视频。
        try {
            Class<?> rsfCls = XposedHelpers.findClass("com.rearScreen.manager.RearScreenFunction", cl);
            XposedHelpers.findAndHookMethod(rsfCls, "toq", XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook RearScreenFunction.toq failed: " + t);
        }
        try {
            Class<?> lpuCls = XposedHelpers.findClass("com.android.thememanager.util.LivePhotoUtils", cl);
            XposedHelpers.findAndHookMethod(lpuCls, "n", XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook LivePhotoUtils.n failed: " + t);
        }
        try {
            Class<?> tpiCls = XposedHelpers.findClass("com.android.thememanager.basemodule.utils.ThirdPartyIntentUtils", cl);
            XposedBridge.hookAllMethods(tpiCls, "cdj", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2 || !(param.args[1] instanceof Boolean)) {
                        return;
                    }
                    if (!(Boolean) param.args[1]) {
                        return;
                    }
                    boolean isRearPicker = sInRearScreenThemePage;
                    if (!isRearPicker) {
                        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                            String cn = ste.getClassName();
                            if (cn.contains("rearScreen") || cn.contains("RearScreen")
                                    || cn.contains("AlbumPickerHelper") || cn.contains("JumpRouter")) {
                                isRearPicker = true;
                                break;
                            }
                        }
                    }
                    if (isRearPicker) {
                        param.args[1] = Boolean.FALSE; // imageOnly=false -> "image/*, video/*"
                        XposedBridge.log(TAG + ": unlocked video & Live Photo in rear screen gallery picker");
                    }
                }
            });
            XposedBridge.log(TAG + ": ThemeManager hooked ThirdPartyIntentUtils.cdj for video & Live Photo picker");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook ThirdPartyIntentUtils.cdj failed: " + t);
        }

        // 隐藏 MIX Flip 2 硬件不支持的「背屏息屏显示（AodAssistantController）」与「背屏截屏（ScreenshotAssistantController）」入口
        try {
            Class<?> aodCtrlCls = XposedHelpers.findClass("com.rearScreen.subscreen.AodAssistantController", cl);
            XposedHelpers.findAndHookMethod(aodCtrlCls, "k", XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.log(TAG + ": ThemeManager hidden unsupported AodAssistantController (rear AOD)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hide AodAssistantController failed: " + t);
        }
        try {
            Class<?> ssCtrlCls = XposedHelpers.findClass("com.rearScreen.subscreen.ScreenshotAssistantController", cl);
            XposedHelpers.findAndHookMethod(ssCtrlCls, "k", XC_MethodReplacement.returnConstant(Boolean.FALSE));
            XposedBridge.log(TAG + ": ThemeManager hidden ScreenshotAssistantController (rear screenshot)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hide ScreenshotAssistantController failed: " + t);
        }

        // 修复「个性背屏」亮度条（用 Flip 2 动态浮点映射直连 DisplayManager(1)）并隐藏失效的「阳光屏」开关
        hookThemeManagerRearBrightness(cl);
    }

    private static void hookThemeManagerHalfAndFullCards(ClassLoader cl) {
        // 1. Hook RearScreenListViewModel.r8s8(ArrayList)：在本地预设 + 云端在线（含相册轮播）全部合并完成后统一处理：
        //    - 「壁纸与签名」(signature) -> 「半屏壁纸与签名」 + 克隆「全屏壁纸与签名」(categoryType="signature_fullscreen")
        //    - 「相册轮播」(carousel)  -> 「半屏相册轮播」   + 克隆「全屏相册轮播」(categoryType="carousel_fullscreen")
        //    使用独立 categoryType 避免被 ViewModel/DiffUtil 合并覆盖，每张克隆卡片打标 madrid_fullscreen_card=true。
        try {
            Class<?> vmCls = XposedHelpers.findClass("com.rearScreen.viewModel.RearScreenListViewModel", cl);
            XposedBridge.hookAllMethods(vmCls, "r8s8", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof java.util.List)) {
                        return;
                    }
                    java.util.List<Object> list = (java.util.List<Object>) param.args[0];
                    boolean hasFullSig = false;
                    boolean hasFullCar = false;
                    for (Object cat : list) {
                        if (cat == null) continue;
                        try {
                            String t = (String) XposedHelpers.callMethod(cat, "getCategoryType");
                            if ("signature_fullscreen".equals(t)) hasFullSig = true;
                            if ("carousel_fullscreen".equals(t)) hasFullCar = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    java.util.ArrayList<Object> toInsert = new java.util.ArrayList<>();
                    java.util.ArrayList<Integer> insertPositions = new java.util.ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        Object cat = list.get(i);
                        if (cat == null) continue;
                        try {
                            String type = (String) XposedHelpers.callMethod(cat, "getCategoryType");
                            String name = (String) XposedHelpers.callMethod(cat, "getCategoryName");
                            boolean isSig = "signature".equals(type) || (name != null && name.contains("壁纸与签名"));
                            boolean isCar = "carousel".equals(type) || (name != null && name.contains("相册轮播"));
                            if (!isSig && !isCar) {
                                continue;
                            }
                            if (isSig && hasFullSig) continue;
                            if (isCar && hasFullCar) continue;
                            String halfTitle = isCar ? "半屏相册轮播" : "半屏壁纸与签名";
                            String fullTitle = isCar ? "全屏相册轮播" : "全屏壁纸与签名";
                            String fullType = isCar ? "carousel_fullscreen" : "signature_fullscreen";
                            XposedHelpers.setObjectField(cat, "categoryName", halfTitle);
                            Object clonedCat = XposedHelpers.callMethod(cat, "clone");
                            if (clonedCat != null) {
                                XposedHelpers.setObjectField(clonedCat, "categoryType", fullType);
                                XposedHelpers.setObjectField(clonedCat, "categoryName", fullTitle);
                                int pri = (Integer) XposedHelpers.callMethod(cat, "getPriority");
                                XposedHelpers.callMethod(clonedCat, "setPriority", pri + 1);
                                java.util.ArrayList<Object> origItems =
                                        (java.util.ArrayList<Object>) XposedHelpers.callMethod(cat, "getItemList");
                                java.util.ArrayList<Object> fullItems = new java.util.ArrayList<>();
                                if (origItems != null) {
                                    for (Object item : origItems) {
                                        Object copied = cloneRearScreenItemAsFullscreen(item, cl);
                                        if (copied != null) {
                                            fullItems.add(copied);
                                        }
                                    }
                                }
                                if (!fullItems.isEmpty()) {
                                    XposedHelpers.setObjectField(clonedCat, "itemList", fullItems);
                                    toInsert.add(clonedCat);
                                    insertPositions.add(i + 1);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": clone fullscreen category in r8s8 failed: " + t);
                        }
                    }
                    for (int k = toInsert.size() - 1; k >= 0; k--) {
                        int pos = Math.min(list.size(), insertPositions.get(k));
                        list.add(pos, toInsert.get(k));
                    }
                    if (!toInsert.isEmpty()) {
                        XposedBridge.log(TAG + ": ThemeManager r8s8 injected " + toInsert.size() + " fullscreen group(s)");
                    }
                }
            });
            XposedBridge.log(TAG + ": ThemeManager hooked RearScreenListViewModel.r8s8 for half/full carousel & signature");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook RearScreenListViewModel.r8s8 failed: " + t);
        }

        // 2. Hook ViewHolderRearScreenCategoryItem.ch(holder, view)：点击卡片瞬间判断是半屏还是全屏分组，
        //    立刻切换 sThemeEditorFullscreenMode，让进入后的 MAMLViewRearContainer 预览框与裁切器按 1208×1392 全屏显示！
        try {
            Class<?> vhCls = XposedHelpers.findClass("com.rearScreen.viewholder.ViewHolderRearScreenCategoryItem", cl);
            XposedBridge.hookAllMethods(vhCls, "ch", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                        return;
                    }
                    try {
                        Object item = XposedHelpers.getObjectField(param.args[0], "g");
                        boolean isFull = false;
                        if (item != null) {
                            Bundle extra = (Bundle) XposedHelpers.getObjectField(item, "mExtra");
                            isFull = extra != null && extra.getBoolean(EXTRA_MADRID_FULLSCREEN_CARD, false);
                        }
                        sThemeEditorFullscreenMode = isFull;
                        XposedBridge.log(TAG + ": ThemeManager clicked card -> previewMode="
                                + (isFull ? "FULLSCREEN(1208x1392)" : "HALF(1208x738)"));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": ViewHolderRearScreenCategoryItem.ch detect failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": ThemeManager hooked ViewHolderRearScreenCategoryItem.ch for dynamic preview ratio");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook ViewHolderRearScreenCategoryItem.ch failed: " + t);
        }

        // 回到列表页（RearScreenNewListActivity / RearScreenListActivity）时恢复列表页 3x2 缩略图比例
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (a == null) {
                        return;
                    }
                    String cn = a.getClass().getName();
                    if ((cn.contains("rearScreen") || cn.contains("RearScreen"))
                            && (cn.contains("ListActivity") || cn.contains("HomeActivity") || cn.contains("SettingActivity"))) {
                        sThemeEditorFullscreenMode = false;
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 3. 将官方背屏页面中的「选择图片」等文案替换为「选择图片、视频和 Live 图」
        try {
            XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setText",
                    CharSequence.class, android.widget.TextView.BufferType.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sInRearScreenThemePage || param.args == null || param.args.length < 1
                                    || param.args[0] == null) {
                                return;
                            }
                            String s = param.args[0].toString();
                            if ("选择图片".equals(s)) {
                                param.args[0] = "选择图片、视频和 Live 图";
                            } else if ("更换图片".equals(s)) {
                                param.args[0] = "更换图片、视频和 Live 图";
                            } else if ("添加图片".equals(s)) {
                                param.args[0] = "添加图片、视频和 Live 图";
                            } else if ("将喜欢的图片设定在背屏展示".equals(s)) {
                                param.args[0] = "将喜欢的图片、视频或 Live 图设定在背屏展示";
                            }
                        }
                    });
            XposedBridge.log(TAG + ": ThemeManager hooked TextView.setText for video & Live Photo label");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook TextView.setText failed: " + t);
        }
    }

    private static Object cloneRearScreenItemAsFullscreen(Object item, ClassLoader cl) {
        if (item == null || !(item instanceof android.os.Parcelable)) {
            return null;
        }
        // Gson 从 rearScreen.json 反序列化时未提供 applyId（为 null），Kotlin Parcelable.Creator
        // 构造时会校验 @NotNull String 参数，写入 Parcel 前先兜底为 ""。
        String[] nonNullStrFields = {"applyId", "resId", "resName", "resPreviewPath", "resType"};
        for (String f : nonNullStrFields) {
            try {
                if (XposedHelpers.getObjectField(item, f) == null) {
                    XposedHelpers.setObjectField(item, f, "");
                }
            } catch (Throwable ignored) {
            }
        }
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeParcelable((android.os.Parcelable) item, 0);
            parcel.setDataPosition(0);
            Object copy = parcel.readParcelable(cl);
            if (copy != null) {
                Bundle extra = null;
                try {
                    extra = (Bundle) XposedHelpers.getObjectField(copy, "mExtra");
                } catch (Throwable ignored) {
                }
                if (extra == null) {
                    extra = new Bundle();
                    XposedHelpers.setObjectField(copy, "mExtra", extra);
                }
                extra.putBoolean(EXTRA_MADRID_FULLSCREEN_CARD, true);
                return copy;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cloneRearScreenItemAsFullscreen failed: " + t);
        } finally {
            parcel.recycle();
        }
        return null;
    }

    private static void hookThemeManagerRearBrightness(ClassLoader cl) {
        // Flip 2 外屏原厂动态浮点区间（来自 /product/etc/displayconfig/display_id_4630946219955703956.xml）
        final float FLIP2_MIN = 0.000854597f;
        final float FLIP2_MAX = 0.416493713f; // 常规上限 / HBM 切换点 (~500 nits)

        try {
            Class<?> bcCls = XposedHelpers.findClass("com.rearScreen.receiver.BrightnessController", cl);

            // 1. 替换 n7h()：直接读取 displayId=1 的 BrightnessInfo，避免 16777202 返回空数组导致滑块跳回 50%
            XposedHelpers.findAndHookMethod(bcCls, "n7h", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    try {
                        DisplayManager dm = (DisplayManager) XposedHelpers.getObjectField(param.thisObject, "zy");
                        if (dm == null) {
                            return null;
                        }
                        Display d = dm.getDisplay(1);
                        if (d == null) {
                            return null;
                        }
                        Object info = XposedHelpers.callMethod(d, "getBrightnessInfo");
                        if (info != null) {
                            float cur = XposedHelpers.getFloatField(info, "brightness");
                            float min = XposedHelpers.getFloatField(info, "brightnessMinimum");
                            float max = XposedHelpers.getFloatField(info, "brightnessMaximum");
                            if (Float.isNaN(min) || min <= 0f) min = FLIP2_MIN;
                            if (Float.isNaN(max) || max <= min) max = FLIP2_MAX;
                            if (!Float.isNaN(cur) && cur >= 0f) {
                                float norm = Math.max(0f, Math.min(1f, (cur - min) / (max - min)));
                                float pct = (float) Math.pow(norm, 1.0 / 2.2);
                                XposedHelpers.setFloatField(param.thisObject, "ld6", pct);
                                XposedHelpers.setFloatField(param.thisObject, "p", pct);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": BrightnessController.n7h hook failed: " + t);
                    }
                    return null;
                }
            });

            // 2. 替换 o1t(float percent, int txCode)：按 Flip 2 浮点曲线直调 DisplayManager.setTemporaryBrightness / setBrightness
            XposedHelpers.findAndHookMethod(bcCls, "o1t", float.class, int.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    try {
                        float pct = Math.max(0f, Math.min(1f, (Float) param.args[0]));
                        int txCode = (Integer) param.args[1];
                        DisplayManager dm = (DisplayManager) XposedHelpers.getObjectField(param.thisObject, "zy");
                        if (dm == null) {
                            return null;
                        }
                        float min = FLIP2_MIN;
                        float max = FLIP2_MAX;
                        Display d = dm.getDisplay(1);
                        if (d != null) {
                            Object info = XposedHelpers.callMethod(d, "getBrightnessInfo");
                            if (info != null) {
                                float iMin = XposedHelpers.getFloatField(info, "brightnessMinimum");
                                float iMax = XposedHelpers.getFloatField(info, "brightnessMaximum");
                                if (!Float.isNaN(iMin) && iMin > 0f) min = iMin;
                                if (!Float.isNaN(iMax) && iMax > min) max = iMax;
                            }
                        }
                        float linear = min + (max - min) * (float) Math.pow(pct, 2.2);
                        if (txCode == 16777201) {
                            XposedHelpers.callMethod(dm, "setTemporaryBrightness", 1, linear);
                        } else {
                            XposedHelpers.callMethod(dm, "setBrightness", 1, linear);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": BrightnessController.o1t hook failed: " + t);
                    }
                    return null;
                }
            });

            // 3. 隐藏失效的「阳光屏」开关行（viewBinding.s）
            Class<?> bvhCls = XposedHelpers.findClass("com.rearScreen.adapter.RearScreenSettingAdapter$BrightnessViewHolder", cl);
            XC_MethodHook hideSunlightHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object binding = XposedHelpers.getObjectField(param.thisObject, "k");
                        if (binding != null) {
                            View sunlightRow = (View) XposedHelpers.getObjectField(binding, "s");
                            if (sunlightRow != null) {
                                sunlightRow.setVisibility(View.GONE);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(bvhCls, "ni7", hideSunlightHook);
            XposedBridge.hookAllMethods(bvhCls, "s", hideSunlightHook);
            XposedBridge.log(TAG + ": ThemeManager rear brightness slider mapped to Flip 2 float curve & Sunlight Mode hidden");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookThemeManagerRearBrightness failed: " + t);
        }
    }

    private static WeakReference<View> sFlipWatchOverlayRef;
    private static volatile boolean sFlipWatchOverlayHidden = false;
    private static volatile boolean sFlipOverlayReceiverRegistered = false;

    public static void installFlipHome(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    if (v == null) {
                        return;
                    }
                    String name = v.getClass().getSimpleName();
                    if (!"WatchOverlayGroupView".equals(name)) {
                        return;
                    }
                    sFlipWatchOverlayRef = new WeakReference<>(v);
                    if (sFlipWatchOverlayHidden) {
                        v.setAlpha(0f);
                        v.setVisibility(View.INVISIBLE);
                    }
                    if (!sFlipOverlayReceiverRegistered) {
                        try {
                            Context appCtx = v.getContext().getApplicationContext();
                            if (appCtx != null) {
                                IntentFilter f = new IntentFilter(UnfoldOuterDisplayHook.ACTION_FOLDED_REAR_OVERLAY);
                                appCtx.registerReceiver(new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        boolean hide = intent != null && intent.getBooleanExtra(
                                                UnfoldOuterDisplayHook.EXTRA_HIDE_OVERLAY, false);
                                        sFlipWatchOverlayHidden = hide;
                                        View ov = sFlipWatchOverlayRef != null ? sFlipWatchOverlayRef.get() : null;
                                        if (ov != null) {
                                            ov.post(() -> {
                                                ov.setAlpha(hide ? 0f : 1f);
                                                ov.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
                                            });
                                        }
                                    }
                                }, f, 2);
                                sFlipOverlayReceiverRegistered = true;
                                XposedBridge.log(TAG + ": FlipHome registered WatchOverlayGroupView hide receiver");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sFlipWatchOverlayHidden) {
                        return;
                    }
                    View v = (View) param.thisObject;
                    if (v != null && "WatchOverlayGroupView".equals(v.getClass().getSimpleName())) {
                        int vis = (Integer) param.args[0];
                        if (vis == View.VISIBLE) {
                            param.args[0] = View.INVISIBLE;
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": FlipHome hook armed (WatchOverlayGroupView guard)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FlipHome hook failed: " + t);
        }
    }

    public static void installSystemUI(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        XposedBridge.log(TAG + ": SystemUI hook armed");
    }
}
