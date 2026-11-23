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

    // MIX Flip 2 外屏宽度 1208px，双摄宽 398px、高 728px；按小米 18 原版 976:596 比例缩放后为 1208×738
    private static final int TARGET_WIDTH = 1208;
    private static final int TARGET_HEIGHT = 738;
    private static final int CAMERA_SAFE_LEFT_PX = 410;
    private static final float VIEWPORT_CORNER_RADIUS_PX = 100f;
    // 防烧屏三档模式（Settings.Global madrid_rear_burnin_mode）：
    // 0 = 原厂AOD档（60s / 6px / 0~18px）
    // 1 = 平衡档（20s / 24px / 0~180px，默认）
    // 2 = 积极大幅档（12s / 顶部0 ↔ 中部320 ↔ 底部640 大幅轮换整屏位置）
    private static final int[] AGGRESSIVE_POSITIONS = {0, 320, 640, 320};
    private static int sAggressiveIdx = 0;
    private static final int BACK_PROXIMITY_SENSOR_TYPE = 33171123; // TCS3720ALSPRX Proximity Back Sensor

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
    private static android.view.OrientationEventListener sOrientationListener;
    private static int sCurrentContentRotation = 0;
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
                content.animate().translationY(sBurnShiftY).setDuration(duration).start();
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

        // 始终保持 1208×738（按小米 18 原版 976:596 比例），确保应用卡与双摄避让 100% 永不错位
        try {
            XposedHelpers.findAndHookMethod(Display.class, "getRealSize", Point.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Display d = (Display) param.thisObject;
                    if (d != null && (d.getDisplayId() == 1 || d.getDisplayId() == 0) && param.args[0] instanceof Point) {
                        Point p = (Point) param.args[0];
                        if (p.x == TARGET_WIDTH && p.y > TARGET_HEIGHT) {
                            p.set(TARGET_WIDTH, TARGET_HEIGHT);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked Display.getRealSize(displayId=1) -> 1208x738");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Display.getRealSize failed: " + t);
        }

        // 修复应用卡/面板呼出被 Flip 2 左侧双摄（398px）遮挡：确保 getSafeInsetLeft() 返回 >= 410px
        try {
            XposedHelpers.findAndHookMethod(DisplayCutout.class, "getSafeInsetLeft", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
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
                    if (!isSubScreenActivity(act.getClass().getName()) || !isFoldedMode(act)) {
                        return;
                    }
                    try {
                        android.view.Window win = act.getWindow();
                        if (win != null) {
                            win.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
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
                    if (finishIfNotOnSecondaryDisplay(act)) {
                        return;
                    }
                    sTrackedActivityRef = new WeakReference<>(act);
                    sMainHandler.post(() -> {
                        applyCameraAlignedViewport(act, true);
                        resetIdleTimeout(act);
                        registerBackProximity(act);
                        registerGravityAutoRotate(act);
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
                        unregisterBackProximity();
                        unregisterGravityAutoRotate();
                    }
                }
            });

            // 双击下方空白/毛玻璃区域退出背屏；右边缘或下方区域左滑直接打开 Flip 2 原厂外屏自拍；任意触摸重置息屏超时
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity act = (Activity) param.thisObject;
                    if (!isSubScreenActivity(act.getClass().getName())) {
                        return;
                    }
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev != null) {
                        int actMask = ev.getActionMasked();
                        if (actMask == MotionEvent.ACTION_UP) {
                            // 每次滑动/点击结束后 45ms 立即重采样毛玻璃色彩，消除切卡/切主题延迟
                            triggerImmediateGlassRefresh(45L);
                            // 检测左滑打开外屏自拍（右侧边缘起滑 或 下方空白/毛玻璃区左滑，不与上方卡片切换冲突）
                            float dx = sTouchDownX - ev.getRawX();
                            float dy = Math.abs(ev.getRawY() - sTouchDownY);
                            long dt = System.currentTimeMillis() - sTouchDownTime;
                            boolean inSwipeZone = (sTouchDownX >= TARGET_WIDTH - 160)
                                    || (sTouchDownY > TARGET_HEIGHT + sBurnShiftY);
                            if (inSwipeZone && dx > 180f && dy < 180f && dt < 500L && isSwipeCameraEnabled(act)) {
                                XposedBridge.log(TAG + ": left-swipe detected -> launching Flip 2 outer selfie camera");
                                launchOuterSelfieCamera(act);
                                param.setResult(Boolean.TRUE);
                            }
                        } else if (actMask == MotionEvent.ACTION_CANCEL) {
                            triggerImmediateGlassRefresh(120L);
                        } else if (actMask == MotionEvent.ACTION_DOWN) {
                            sTouchDownX = ev.getRawX();
                            sTouchDownY = ev.getRawY();
                            sTouchDownTime = System.currentTimeMillis();
                            resetIdleTimeout(act);
                            if (ev.getRawY() > TARGET_HEIGHT + sBurnShiftY) {
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

            XposedBridge.log(TAG + ": camera-aligned viewport + black-area double-tap exit + burn-in shift armed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity viewport failed: " + t);
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
            Sensor backProx = sm.getDefaultSensor(BACK_PROXIMITY_SENSOR_TYPE, false);
            if (backProx == null) {
                backProx = sm.getDefaultSensor(BACK_PROXIMITY_SENSOR_TYPE, true);
            }
            if (backProx == null) {
                return;
            }
            sSensorManager = sm;
            sBackProxListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event == null || event.values == null || event.values.length == 0) {
                        return;
                    }
                    boolean near = event.values[0] < 1.0f;
                    if (near) {
                        sMainHandler.removeCallbacks(sTableOffRunnable);
                        sMainHandler.postDelayed(sTableOffRunnable, 1500L);
                    } else {
                        sMainHandler.removeCallbacks(sTableOffRunnable);
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(sBackProxListener, backProx, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerBackProximity failed: " + t);
        }
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

    private static void registerGravityAutoRotate(Activity act) {
        unregisterGravityAutoRotate();
        if (act == null) {
            return;
        }
        try {
            if (Settings.Global.getInt(act.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE, 1) != 1) {
                sCurrentContentRotation = 0;
                View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                if (content != null) {
                    content.setRotation(0f);
                }
                return;
            }
            sOrientationListener = new android.view.OrientationEventListener(act, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
                        return;
                    }
                    // 滞回区间：正向(315°..360° / 0°..45°) -> 0°，倒置(135°..225°) -> 180°
                    int target = sCurrentContentRotation;
                    if (orientation >= 140 && orientation <= 220) {
                        target = 180;
                    } else if (orientation <= 40 || orientation >= 320) {
                        target = 0;
                    }
                    if (target != sCurrentContentRotation) {
                        sCurrentContentRotation = target;
                        final int rot = target;
                        sMainHandler.post(() -> {
                            View content = sTrackedContentRef != null ? sTrackedContentRef.get() : null;
                            if (content != null && content.isAttachedToWindow()) {
                                content.setPivotX(content.getWidth() * 0.5f);
                                content.setPivotY(content.getHeight() * 0.5f);
                                content.animate().rotation(rot).setDuration(280L).start();
                                triggerImmediateGlassRefresh(320L);
                            }
                        });
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

    private static boolean isSwipeCameraEnabled(Context ctx) {
        try {
            return ctx != null && Settings.Global.getInt(
                    ctx.getContentResolver(), UnfoldOuterDisplayHook.KEY_REAR_SWIPE_CAMERA, 1) == 1;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void launchOuterSelfieCamera(Activity act) {
        if (act == null) {
            return;
        }
        try {
            int targetDisplayId = 1;
            Display d = act.getDisplay();
            if (d != null) {
                targetDisplayId = d.getDisplayId();
            }
            Intent cam = new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            cam.setPackage("com.android.camera");
            cam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(targetDisplayId);
            act.startActivity(cam, opts.toBundle());
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent();
                fallback.setClassName("com.android.camera", "com.android.camera.Camera");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(fallback);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": launchOuterSelfieCamera failed: " + t2);
            }
        }
    }

    private static void fadeOutAndFinish(Activity act) {
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
                va.setDuration(420L);
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
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Activity a = sTrackedActivityRef != null ? sTrackedActivityRef.get() : null;
                    if (a != null && !a.isFinishing()) {
                        boolean silent = intent != null && intent.getBooleanExtra(UnfoldOuterDisplayHook.EXTRA_SILENT_FINISH, false);
                        XposedBridge.log(TAG + ": received ACTION_FINISH_REAR_LAUNCHER (silent=" + silent + ") -> finish");
                        if (!silent && isFoldedMode(a)) {
                            fadeOutAndFinish(a);
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
                            if (cn.contains("rearScreen") || cn.contains("aiapp") || cn.contains("AiApp")) {
                                param.setResult("madrid");
                                return;
                            }
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(spCls, "get", String.class, deviceSpoofHook);
            XposedHelpers.findAndHookMethod(spCls, "get", String.class, String.class, deviceSpoofHook);
            XposedBridge.log(TAG + ": ThemeManager armed scoped madrid device spoof for RearScreenAiApp");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager device spoof failed: " + t);
        }

        // 修复 AI 应用卡社区列表「当前内容无法展示」：
        // 拦截 ThemeManager OkHttp ParamInterceptor，在 AI_GENERATED_APP / rearScreen / aiapp 请求期间
        // 将 DeviceUtils.y() -> "madrid"、cdj() -> "25098PN5AC"、t8r() -> "MIPHONE"、a() -> true，
        // 仅作用于当前 OkHttp 请求线程，不影响 Flip 2 普通主题商店请求。
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
                        boolean shouldSpoof = url.contains("AI_GENERATED_APP")
                                || url.contains("rearScreen")
                                || url.contains("rearscreen")
                                || url.contains("aiapp")
                                || url.contains("ai_app");
                        if (!shouldSpoof) {
                            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                                String cn = ste.getClassName();
                                if (cn.contains("rearScreen") || cn.contains("aiapp") || cn.contains("AiApp")) {
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
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get())) {
                        param.setResult("madrid");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(devUtilsCls, "cdj", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get())) {
                        param.setResult("25098PN5AC");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(devUtilsCls, "t8r", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get())) {
                        param.setResult("MIPHONE");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(devUtilsCls, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sSpoofMadridNetwork.get())) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            XposedBridge.log(TAG + ": ThemeManager hooked ParamInterceptor + DeviceUtils for AI_GENERATED_APP community list");

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

        // 修复 AI 应用卡详情页预览区高度过大导致底部被裁切：
        // AiAppPreviewContainer 按 RearScreenUtil.x2()（背屏宽高比）计算预览高度；
        // Flip 2 物理比例 1208/1392=0.868 会把预览撑高到 1206px，改为小米 18 的 1208/738=1.637 后恢复 639px 标准比例。
        try {
            Class<?> rsuCls = XposedHelpers.findClass("com.android.thememanager.basemodule.utils.RearScreenUtil", cl);
            XposedHelpers.findAndHookMethod(rsuCls, "x2",
                    XC_MethodReplacement.returnConstant(TARGET_WIDTH / (float) TARGET_HEIGHT));
            XposedBridge.log(TAG + ": ThemeManager hooked RearScreenUtil.x2() -> 1208/738 (fix AiAppDetail bottom clipping)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThemeManager hook RearScreenUtil.x2 failed: " + t);
        }

        // 修复「个性背屏」亮度条（用 Flip 2 动态浮点映射直连 DisplayManager(1)）并隐藏失效的「阳光屏」开关
        hookThemeManagerRearBrightness(cl);
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
