package com.collasong.madridsubscreen;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 小米 18 背屏移植模块控制面板（对标 flipglasslock 现代 HyperOS 卡片化风格）：
 * - 自适应深浅色模式 + 沉浸式状态栏/导航栏；
 * - 顶部状态横幅 + 快捷动作双列卡片 + 胶囊分段选择器（亮度上限 / 息屏超时）+ 分组 Switch 卡片。
 */
public class SettingsActivity extends Activity {

    private static final int[] CAP_VALUES = {30, 50, 70, 85, 100};
    private static final String[] CAP_LABELS = {"30%", "50%", "70%", "85%", "100%"};

    private static final int[] SCALE_VALUES = {60, 75, 85, 100, 115};
    private static final String[] SCALE_LABELS = {"0.6x", "0.75x", "0.85x", "1.0x", "1.15x"};

    private static final int[] TIMEOUT_VALUES = {0, 15, 30, 60, 120};
    private static final String[] TIMEOUT_LABELS = {"关闭", "15s", "30s", "60s", "2m"};

    private static final int[] BURNIN_VALUES = {0, 1, 2};
    private static final String[] BURNIN_LABELS = {"原厂AOD", "平衡", "积极大幅"};

    private static final int[] FOLDED_EXIT_VALUES = {1, 2, 0};
    private static final String[] FOLDED_EXIT_LABELS = {"直接息屏", "回原厂锁屏", "回外屏桌面"};

    private static final int[] FLIP_ALIGN_VALUES = {0, 1, 2};
    private static final String[] FLIP_ALIGN_LABELS = {"贴左避让", "居中满幅", "关闭翻转"};

    private static final int[] VIDEO_MODE_VALUES = {0, 1, 2, 3};
    private static final String[] VIDEO_MODE_LABELS = {"循环", "点击重播", "亮屏一次", "仅播一次"};

    private boolean dark;
    private int cBg, cCard, cStroke, cText, cSub, cDivider, cAccent, cAccentSoft;
    private int cOk, cOkSoft, cWarn, cWarnSoft, cDanger, cDangerSoft, cTrack;

    private LinearLayout statusBanner;
    private TextView statusBadge, statusDetail;
    private Switch swEngine, swRearDisplay;
    private boolean updatingQuickSwitches = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private SensorEventListener backAlsListener, backProxListener;
    private volatile Float liveBackLux = null;
    private volatile Boolean liveBackProxNear = null;
    private final Runnable bannerTicker = new Runnable() {
        @Override
        public void run() {
            refreshBannerStatus();
            uiHandler.postDelayed(this, 1200L);
        }
    };
    private TextView[] capSegments = new TextView[CAP_VALUES.length];
    private TextView[] scaleSegments = new TextView[SCALE_VALUES.length];
    private TextView[] timeoutSegments = new TextView[TIMEOUT_VALUES.length];
    private TextView[] burninSegments = new TextView[BURNIN_VALUES.length];
    private TextView[] foldedExitSegments = new TextView[FOLDED_EXIT_VALUES.length];
    private TextView[] flipAlignSegments = new TextView[FLIP_ALIGN_VALUES.length];
    private TextView[] videoModeSegments = new TextView[VIDEO_MODE_VALUES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initTheme();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int hPad = dp(18);
        root.setPadding(hPad, dp(22), hPad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 1. 顶部标题区
        TextView title = new TextView(this);
        title.setText("Madrid(小米18)背屏移植flip2");
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(cText);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("v2.0.0 · 小米 18 背屏中心 (OS4.0.31.0) · MIX Flip 2 专用");
        subtitle.setTextSize(13);
        subtitle.setTextColor(cSub);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        // 2. 顶部状态横幅（未开启背屏显示黄色「模块就绪，等待开启背屏」，已开启背屏显示绿色）
        statusBanner = roundedCard(root, cWarnSoft, cWarn, 18, 14);
        statusBadge = new TextView(this);
        statusBadge.setText("●  模块就绪，等待开启背屏");
        statusBadge.setTextSize(15);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setTextColor(cWarn);
        statusBanner.addView(statusBadge);

        statusDetail = new TextView(this);
        statusDetail.setText("双摄对齐 1208×738 · 背面光感自动亮度 · 背面接近传感器防误亮");
        statusDetail.setTextSize(12);
        statusDetail.setTextColor(cSub);
        statusDetail.setPadding(0, dp(4), 0, 0);
        statusBanner.addView(statusDetail);

        // 3. 快捷控制卡片（背屏引擎总开关 + 背屏显示开关）
        addSectionTitle(root, "快捷控制");
        LinearLayout quickCard = roundedCard(root, cCard, cStroke, 18, 16);

        boolean engineOnInit = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_ENGINE_ENABLED, 1) == 1;
        swEngine = addSwitchRow(quickCard,
                "背屏引擎开关",
                "开启后启用小米 18 背屏调度、手势与自动化；关闭后立即停用背屏并恢复原厂外屏逻辑",
                engineOnInit,
                (b, checked) -> {
                    if (updatingQuickSwitches) {
                        return;
                    }
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_ENGINE_ENABLED + " " + (checked ? "1" : "0"));
                    if (!checked) {
                        try {
                            sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
                        } catch (Throwable ignored) {
                        }
                        runRootCmd("cmd device_state state reset");
                        updatingQuickSwitches = true;
                        if (swRearDisplay != null) {
                            swRearDisplay.setChecked(false);
                        }
                        updatingQuickSwitches = false;
                        toast("已关闭背屏引擎并恢复原厂外屏");
                    } else {
                        toast("已开启背屏引擎，等待开启背屏");
                    }
                    refreshBannerStatus();
                    statusBanner.postDelayed(this::refreshBannerStatus, 700L);
                });

        addDivider(quickCard);

        swRearDisplay = addSwitchRow(quickCard,
                "背屏显示开关",
                "实时开启或关闭展开态小米 18 背屏（双击下方区域或合盖亦可退出）",
                isRearDisplayOn(),
                (b, checked) -> {
                    if (updatingQuickSwitches) {
                        return;
                    }
                    if (checked) {
                        if (getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_ENGINE_ENABLED, 1) != 1) {
                            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_ENGINE_ENABLED + " 1");
                            updatingQuickSwitches = true;
                            if (swEngine != null) {
                                swEngine.setChecked(true);
                            }
                            updatingQuickSwitches = false;
                        }
                        try {
                            sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_ENTER_REAR_SCREEN));
                        } catch (Throwable ignored) {
                        }
                        runRootCmd("cmd device_state state 5 && sleep 0.4 && am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher");
                        toast("已开启展开态背屏");
                        refreshBannerStatus();
                        statusBanner.postDelayed(this::refreshBannerStatus, 900L);
                    } else {
                        try {
                            sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
                        } catch (Throwable ignored) {
                        }
                        runRootCmd("cmd device_state state reset");
                        toast("已关闭背屏");
                        refreshBannerStatus();
                        statusBanner.postDelayed(this::refreshBannerStatus, 700L);
                    }
                });

        // 3.5 按键与手势触发卡片
        addSectionTitle(root, "按键与手势触发");
        LinearLayout gestureCard = roundedCard(root, cCard, cStroke, 18, 16);

        boolean fpBound = UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR.equals(
                getSystemString("fingerprint_double_tap"));
        addSwitchRow(gestureCard,
                "指纹键轻拍两下激活/关闭背屏",
                "展开或半展开状态下，轻拍两下侧边指纹键快速开关小米 18 背屏",
                fpBound,
                (b, checked) -> {
                    String val = checked ? UnfoldOuterDisplayHook.BACK_TAP_ACTION_TOGGLE_REAR : "none";
                    runRootCmd("settings put system fingerprint_double_tap " + val);
                    toast(checked ? "已开启：轻拍两下指纹键开关背屏" : "已关闭指纹键轻拍两下绑定");
                });

        addDivider(gestureCard);

        addSwitchRow(gestureCard,
                "合盖时轻拍两下指纹键开启背屏",
                "合盖解锁进外屏桌面后，轻拍两下指纹键全屏淡入小米 18 背屏（锁屏时防误触禁止调起）；双击下方空白处淡出退出。",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_FOLDED_FINGERPRINT_REAR, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_FOLDED_FINGERPRINT_REAR + " " + (checked ? "1" : "0"));
                    toast(checked ? "已开启合盖轻拍指纹键背屏" : "已关闭合盖轻拍指纹键背屏");
                });

        addDivider(gestureCard);

        TextView feTitle = new TextView(this);
        feTitle.setText("合盖背屏双击空白处退出后返回");
        feTitle.setTextSize(14);
        feTitle.setTypeface(Typeface.DEFAULT_BOLD);
        feTitle.setTextColor(cText);
        gestureCard.addView(feTitle);

        TextView feSub = new TextView(this);
        feSub.setText("「直接息屏」走系统原生息屏淡出直达 AOD（无闪屏，推荐）；也可选淡出回原厂锁屏或外屏桌面");
        feSub.setTextSize(12);
        feSub.setTextColor(cSub);
        feSub.setPadding(0, dp(2), 0, dp(10));
        gestureCard.addView(feSub);

        int curFoldedExit = getGlobalInt(UnfoldOuterDisplayHook.KEY_FOLDED_EXIT_TO_LOCK, 1);
        addSegmentBar(gestureCard, FOLDED_EXIT_LABELS, foldedExitSegments, indexOf(FOLDED_EXIT_VALUES, curFoldedExit, 0), idx -> {
            int val = FOLDED_EXIT_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_FOLDED_EXIT_TO_LOCK + " " + val);
            updateSegmentSelection(foldedExitSegments, idx);
            toast("合盖背屏退出后：" + FOLDED_EXIT_LABELS[idx]);
        });

        // 4. 亮度与显示卡片
        addSectionTitle(root, "亮度与视觉");
        LinearLayout displayCard = roundedCard(root, cCard, cStroke, 18, 16);

        TextView capTitle = new TextView(this);
        capTitle.setText("背屏亮度上限（同时限制自动亮度与手动滑块）");
        capTitle.setTextSize(14);
        capTitle.setTypeface(Typeface.DEFAULT_BOLD);
        capTitle.setTextColor(cText);
        displayCard.addView(capTitle);

        TextView capSub = new TextView(this);
        capSub.setText("背面光感在室内偏亮时，可调低上限防止外屏比内屏亮");
        capSub.setTextSize(12);
        capSub.setTextColor(cSub);
        capSub.setPadding(0, dp(2), 0, dp(10));
        displayCard.addView(capSub);

        int curCap = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_BRIGHTNESS_CAP, 70);
        addSegmentBar(displayCard, CAP_LABELS, capSegments, indexOf(CAP_VALUES, curCap, 2), idx -> {
            int val = CAP_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_BRIGHTNESS_CAP + " " + val);
            updateSegmentSelection(capSegments, idx);
            toast("背屏亮度上限已设为 " + val + "%");
        });

        addDivider(displayCard);

        TextView scTitle = new TextView(this);
        scTitle.setText("自动亮度室内精细系数");
        scTitle.setTextSize(14);
        scTitle.setTypeface(Typeface.DEFAULT_BOLD);
        scTitle.setTextColor(cText);
        displayCard.addView(scTitle);

        TextView scSub = new TextView(this);
        scSub.setText("微调背面光感自动亮度整体偏暗或偏亮（0.6x 夜间护眼 ↔ 1.15x 室内提亮）");
        scSub.setTextSize(12);
        scSub.setTextColor(cSub);
        scSub.setPadding(0, dp(2), 0, dp(10));
        displayCard.addView(scSub);

        int curScale = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_AUTO_BRIGHT_SCALE, 100);
        addSegmentBar(displayCard, SCALE_LABELS, scaleSegments, indexOf(SCALE_VALUES, curScale, 3), idx -> {
            int val = SCALE_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_AUTO_BRIGHT_SCALE + " " + val);
            updateSegmentSelection(scaleSegments, idx);
            toast("自动亮度系数已设为 " + SCALE_LABELS[idx]);
        });

        addDivider(displayCard);

        addSwitchRow(displayCard,
                "背屏随重力自动倒置旋转（0° / 180°）",
                "倒握或倒立放置手机时自动平滑旋转背屏 180°（默认关闭，避免半折立放时角度抖动误翻转）",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE + " " + (checked ? "1" : "0"));
                    toast(checked ? "已开启背屏重力倒置旋转" : "已关闭背屏重力倒置旋转");
                });

        addDivider(displayCard);

        TextView faTitle = new TextView(this);
        faTitle.setText("翻转 / 弯折时双摄避让方式");
        faTitle.setTextSize(14);
        faTitle.setTypeface(Typeface.DEFAULT_BOLD);
        faTitle.setTextColor(cText);
        displayCard.addView(faTitle);

        TextView faSub = new TextView(this);
        faSub.setText("贴左避让=把左侧空隙挤到右侧留给双摄；居中满幅=180°居中不偏移；关闭翻转=保持0°正向");
        faSub.setTextSize(12);
        faSub.setTextColor(cSub);
        faSub.setPadding(0, dp(2), 0, dp(10));
        displayCard.addView(faSub);

        int curFlipAlign = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_FLIP_ALIGN, 0);
        addSegmentBar(displayCard, FLIP_ALIGN_LABELS, flipAlignSegments, indexOf(FLIP_ALIGN_VALUES, curFlipAlign, 0), idx -> {
            int val = FLIP_ALIGN_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_FLIP_ALIGN + " " + val);
            updateSegmentSelection(flipAlignSegments, idx);
            toast("翻转/弯折避让方式：" + FLIP_ALIGN_LABELS[idx]);
        });

        addDivider(displayCard);

        addSwitchRow(displayCard,
                "背屏全屏模式（原厂视口）",
                "跳过 1208×738 裁切与毛玻璃，满铺展示自定义照片/视频壁纸；使用萌宠或应用卡时请关闭以保持 1:1 正确缩放",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_STOCK_FULLSCREEN, 0) == 1,
                (b, checked) -> {
                    int v = checked ? 1 : 0;
                    Intent it = new Intent(UnfoldOuterDisplayHook.ACTION_RELOAD_REAR_SCREEN);
                    it.putExtra(UnfoldOuterDisplayHook.EXTRA_SETTING_KEY, UnfoldOuterDisplayHook.KEY_REAR_STOCK_FULLSCREEN);
                    it.putExtra(UnfoldOuterDisplayHook.EXTRA_SETTING_VAL, v);
                    try {
                        sendBroadcast(it);
                    } catch (Throwable ignored) {
                    }
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_STOCK_FULLSCREEN + " " + v);
                    toast(checked ? "已切换背屏全屏模式，正在重载外屏" : "已切回 1208×738 双摄对齐模式，正在重载外屏");
                });

        addDivider(displayCard);

        addSwitchRow(displayCard,
                "实时取色毛玻璃扩展下方",
                "保持上方 1208×738 应用卡完整不变形，实时采样背屏颜色做毛玻璃填补（防分界线烧屏）",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN, 0) == 1,
                (b, checked) -> {
                    int v = checked ? 1 : 0;
                    Intent it = new Intent(UnfoldOuterDisplayHook.ACTION_RELOAD_REAR_SCREEN);
                    it.putExtra(UnfoldOuterDisplayHook.EXTRA_SETTING_KEY, UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN);
                    it.putExtra(UnfoldOuterDisplayHook.EXTRA_SETTING_VAL, v);
                    try {
                        sendBroadcast(it);
                    } catch (Throwable ignored) {
                    }
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN + " " + v);
                    toast(checked ? "已开启实时取色毛玻璃扩展，正在重载外屏" : "已切回纯黑 OLED 底部，正在重载外屏");
                });

        addDivider(displayCard);

        TextView biTitle = new TextView(this);
        biTitle.setText("防烧屏位移档位");
        biTitle.setTextSize(14);
        biTitle.setTypeface(Typeface.DEFAULT_BOLD);
        biTitle.setTextColor(cText);
        displayCard.addView(biTitle);

        TextView biSub = new TextView(this);
        biSub.setText("原厂AOD=60s微移18px；平衡=20s往复180px；积极大幅=12s顶/中/底大幅轮换");
        biSub.setTextSize(12);
        biSub.setTextColor(cSub);
        biSub.setPadding(0, dp(2), 0, dp(10));
        displayCard.addView(biSub);

        int curBurnIn = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_BURNIN_MODE, 1);
        addSegmentBar(displayCard, BURNIN_LABELS, burninSegments, indexOf(BURNIN_VALUES, curBurnIn, 1), idx -> {
            int val = BURNIN_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_BURNIN_MODE + " " + val);
            updateSegmentSelection(burninSegments, idx);
            toast("防烧屏模式：" + BURNIN_LABELS[idx]);
        });

        // 4.5 背屏锁定与壁纸
        addSectionTitle(root, "背屏锁定与壁纸");
        LinearLayout lockCard = roundedCard(root, cCard, cStroke, 18, 16);

        addSwitchRow(lockCard,
                "背屏触摸锁定（防误触）",
                "展开使用时外屏不再响应任何触摸（含双击退出）；内屏触控、背屏画面与壁纸均不受影响，关闭后立即恢复。",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK + " " + (checked ? "1" : "0"));
                    toast(checked ? "已锁定背屏触摸，外屏不再响应触摸" : "已解除背屏触摸锁定");
                });

        addDivider(lockCard);

        TextView vmTitle = new TextView(this);
        vmTitle.setText("相册轮播与签名视频 / Live 图播放模式");
        vmTitle.setTextSize(14);
        vmTitle.setTypeface(Typeface.DEFAULT_BOLD);
        vmTitle.setTextColor(cText);
        lockCard.addView(vmTitle);

        TextView vmSub = new TextView(this);
        vmSub.setText("循环=无限循环播放；点击重播=停在尾帧，轻点再播；亮屏一次=每次亮屏播放一次；仅播一次=只播一次停在尾帧");
        vmSub.setTextSize(12);
        vmSub.setTextColor(cSub);
        vmSub.setPadding(0, dp(2), 0, dp(10));
        lockCard.addView(vmSub);

        int curVideoMode = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_VIDEO_PLAY_MODE, -1);
        if (curVideoMode < 0) {
            curVideoMode = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_VIDEO_LOOP, 0) == 1 ? 0 : 1;
        }
        addSegmentBar(lockCard, VIDEO_MODE_LABELS, videoModeSegments, indexOf(VIDEO_MODE_VALUES, curVideoMode, 1), idx -> {
            int val = VIDEO_MODE_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_VIDEO_PLAY_MODE + " " + val);
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_VIDEO_LOOP + " " + (val == 0 ? "1" : "0"));
            updateSegmentSelection(videoModeSegments, idx);
            toast("相册轮播/签名播放模式：" + VIDEO_MODE_LABELS[idx]);
        });

        addDivider(lockCard);

        addSwitchRow(lockCard,
                "弯折支架模式（关内屏只留背屏）",
                "半展开当支架时自动拉起背屏并关闭内屏、只亮背屏展示壁纸；展开、合盖或关闭本开关后立即恢复内屏。",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_HALF_OPEN_INNER_OFF, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_HALF_OPEN_INNER_OFF + " " + (checked ? "1" : "0"),
                            () -> {
                                try {
                                    sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_APPLY_STAND_MODE));
                                } catch (Throwable ignored) {
                                }
                            });
                    toast(checked ? "已开启弯折支架模式，半展开将关闭内屏" : "已关闭弯折支架模式，内屏恢复");
                });

        // 5. 息屏与自动化卡片
        addSectionTitle(root, "息屏与自动化");
        LinearLayout autoCard = roundedCard(root, cCard, cStroke, 18, 16);

        TextView toTitle = new TextView(this);
        toTitle.setText("外屏无操作超时自动关屏");
        toTitle.setTextSize(14);
        toTitle.setTypeface(Typeface.DEFAULT_BOLD);
        toTitle.setTextColor(cText);
        autoCard.addView(toTitle);

        TextView toSub = new TextView(this);
        toSub.setText("超时后直接硬件熄灭外屏（0 功耗），不影响内屏继续使用");
        toSub.setTextSize(12);
        toSub.setTextColor(cSub);
        toSub.setPadding(0, dp(2), 0, dp(10));
        autoCard.addView(toSub);

        int curTimeout = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TIMEOUT_SEC, 0);
        addSegmentBar(autoCard, TIMEOUT_LABELS, timeoutSegments, indexOf(TIMEOUT_VALUES, curTimeout, 0), idx -> {
            int val = TIMEOUT_VALUES[idx];
            runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_TIMEOUT_SEC + " " + val);
            updateSegmentSelection(timeoutSegments, idx);
            toast("外屏超时关屏：" + TIMEOUT_LABELS[idx]);
        });

        addDivider(autoCard);

        addSwitchRow(autoCard,
                "展开时自动开启背屏",
                "从合盖完全展开到内屏时自动点亮外屏并拉起小米 18 背屏",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_UNFOLD_AUTO_OPEN, 0) == 1,
                (b, checked) -> runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_UNFOLD_AUTO_OPEN + " " + (checked ? "1" : "0")));

        addDivider(autoCard);

        addSwitchRow(autoCard,
                "半展开桌钟模式（类 Surface Duo）",
                "半展开（HALF_OPENED）立于桌面时自动激活背屏并平滑淡入",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_HALF_OPEN_REAR, 0) == 1,
                (b, checked) -> runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_HALF_OPEN_REAR + " " + (checked ? "1" : "0")));

        addDivider(autoCard);

        addSwitchRow(autoCard,
                "合盖自动关闭背屏（恢复原厂外屏 / AOD）",
                "合盖自动销毁背屏任务并恢复 MIX Flip 2 原厂外屏与原厂外屏 AOD",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_FOLD_AUTO_CLOSE, 1) == 1,
                (b, checked) -> runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_FOLD_AUTO_CLOSE + " " + (checked ? "1" : "0")));

        addDivider(autoCard);

        addSwitchRow(autoCard,
                "平放桌面自动关闭外屏",
                "背面接近传感器（33171124 原始流 / 33171123 兜底）检测到背面朝下遮挡 1.5 秒后自动熄灭外屏",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF, 1) == 1,
                (b, checked) -> runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF + " " + (checked ? "1" : "0")));

        // 底部提示
        TextView foot = new TextView(this);
        foot.setText("提示：也可在控制中心编辑添加「小米18背屏」磁贴一键开关；支持三档防烧屏位移、弯折支架模式与官方背屏个性化（半屏/全屏相册轮播与签名、视频/Live图）。");
        foot.setTextSize(12);
        foot.setTextColor(cSub);
        foot.setPadding(dp(4), dp(14), dp(4), 0);
        foot.setLineSpacing(dp(2), 1.0f);
        root.addView(foot);

        setContentView(scroll);
        refreshBannerStatus();
        checkEnvironmentAndGuide();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerLiveStatusSensors();
        refreshBannerStatus();
        uiHandler.removeCallbacks(bannerTicker);
        uiHandler.postDelayed(bannerTicker, 1200L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(bannerTicker);
        unregisterLiveStatusSensors();
    }

    private boolean isRearDisplayOn() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) {
                android.view.Display d1 = dm.getDisplay(1);
                if (d1 != null && d1.getState() == android.view.Display.STATE_ON) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void registerLiveStatusSensors() {
        unregisterLiveStatusSensors();
        try {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensorManager == null) {
                return;
            }
            Sensor backAls = findSensorByType(sensorManager, 33171055);
            if (backAls == null) {
                backAls = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            }
            if (backAls != null) {
                backAlsListener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        if (event != null && event.values != null && event.values.length > 0) {
                            liveBackLux = event.values[0];
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    }
                };
                sensorManager.registerListener(backAlsListener, backAls, SensorManager.SENSOR_DELAY_UI);
            }
            Sensor backProx = findSensorByType(sensorManager, 33171124);
            final boolean rawProx = backProx != null;
            if (backProx == null) {
                backProx = findSensorByType(sensorManager, 33171123);
            }
            if (backProx != null) {
                backProxListener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        if (event != null && event.values != null && event.values.length > 0) {
                            liveBackProxNear = rawProx ? (event.values[0] >= 900f) : (event.values[0] < 1.0f);
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    }
                };
                sensorManager.registerListener(backProxListener, backProx, SensorManager.SENSOR_DELAY_UI);
            }
        } catch (Throwable ignored) {
        }
    }

    private void unregisterLiveStatusSensors() {
        try {
            if (sensorManager != null) {
                if (backAlsListener != null) {
                    sensorManager.unregisterListener(backAlsListener);
                }
                if (backProxListener != null) {
                    sensorManager.unregisterListener(backProxListener);
                }
            }
        } catch (Throwable ignored) {
        }
        backAlsListener = null;
        backProxListener = null;
    }

    private Sensor findSensorByType(SensorManager sm, int type) {
        try {
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (s.getType() == type && !s.isWakeUpSensor()) {
                    return s;
                }
            }
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (s.getType() == type) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void refreshBannerStatus() {
        if (statusBanner == null || statusBadge == null || statusDetail == null) {
            return;
        }
        boolean engineOn = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_ENGINE_ENABLED, 1) == 1;
        boolean rearOn = isRearDisplayOn();
        boolean stockFs = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_STOCK_FULLSCREEN, 0) == 1;
        boolean glassExt = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN, 0) == 1;
        boolean touchLocked = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK, 0) == 1;
        boolean tableOff = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF, 1) == 1;
        int capPct = getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_BRIGHTNESS_CAP, 70);

        updatingQuickSwitches = true;
        if (swEngine != null && swEngine.isChecked() != engineOn) {
            swEngine.setChecked(engineOn);
        }
        if (swRearDisplay != null && swRearDisplay.isChecked() != rearOn) {
            swRearDisplay.setChecked(rearOn);
        }
        updatingQuickSwitches = false;

        int fill;
        int accent;
        String title;
        if (!engineOn) {
            fill = cDangerSoft;
            accent = cDanger;
            title = "●  背屏引擎已关闭";
        } else if (rearOn) {
            fill = cOkSoft;
            accent = cOk;
            title = "●  小米 18 背屏运行中";
        } else {
            fill = cWarnSoft;
            accent = cWarn;
            title = "●  背屏引擎就绪，等待开启背屏";
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), accent);
        statusBanner.setBackground(bg);
        statusBadge.setTextColor(accent);
        statusBadge.setText(title);

        String viewportText = stockFs ? "全屏 1208×1392" : (glassExt ? "半屏 1208×738·毛玻璃" : "半屏 1208×738");
        String touchText = touchLocked ? "已锁定" : "可触摸";
        String alsText = liveBackLux != null ? (Math.round(liveBackLux) + " lx / 上限" + capPct + "%") : ("上限" + capPct + "%");
        String proxState = liveBackProxNear == null ? "待采样" : (liveBackProxNear ? "遮挡" : "无遮挡");
        String proxText = proxState + (tableOff ? "(贴桌关屏开)" : "(贴桌关屏关)");
        statusDetail.setText("视口：" + viewportText + " · 触摸：" + touchText + "\n背面光感：" + alsText + " · 背面接近：" + proxText);
    }

    /** 检测是否缺少配套 KSU 资源模块（com.xiaomi.subscreencenter）或缺少 Root 权限，弹窗引导安装与授权。 */
    private void checkEnvironmentAndGuide() {
        new Thread(() -> {
            boolean hasSubscreenPkg = false;
            try {
                getPackageManager().getPackageInfo("com.xiaomi.subscreencenter", 0);
                hasSubscreenPkg = true;
            } catch (Throwable ignored) {
            }
            boolean hasRoot = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                hasRoot = (p.waitFor() == 0);
            } catch (Throwable ignored) {
            }
            if (!hasSubscreenPkg || !hasRoot) {
                final boolean missingKsu = !hasSubscreenPkg;
                final boolean missingRoot = !hasRoot;
                runOnUiThread(() -> showSetupGuideDialog(missingKsu, missingRoot));
            }
        }).start();
    }

    private void showSetupGuideDialog(boolean missingKsu, boolean missingRoot) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("检测到当前环境尚未配置完整：\n\n");
        if (missingKsu) {
            sb.append("1. 未检测到小米 18 背屏中心（com.xiaomi.subscreencenter）\n")
              .append("   仅安装 LSPosed 模块无法点亮小米 18 背屏，请先在 KernelSU / Magisk 中刷入配套资源模块 madrid_subscreen_port_v1.0.0.zip 并重启手机。\n\n");
        }
        if (missingRoot) {
            sb.append(missingKsu ? "2. " : "1. ")
              .append("未授予本应用 Root 权限\n")
              .append("   请在 KernelSU 超级用户列表中为「Madrid(小米18)背屏移植flip2」开启 Root 授权，以确保快捷键绑定、全屏/半屏切换与背屏启停正常工作。");
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("环境安装与权限引导")
                .setMessage(sb.toString().trim())
                .setPositiveButton("我知道了", null);
        if (missingRoot) {
            b.setNeutralButton("申请 Root 权限", (d, w) -> runRootCmd("id", () -> toast("Root 权限已就绪")));
        }
        try {
            b.show();
        } catch (Throwable ignored) {
        }
    }

    private void initTheme() {
        int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = (nightMask == Configuration.UI_MODE_NIGHT_YES);

        cBg = Color.parseColor(dark ? "#0B0F17" : "#F4F6F9");
        cCard = Color.parseColor(dark ? "#151B26" : "#FFFFFF");
        cStroke = Color.parseColor(dark ? "#242E42" : "#E5EAF2");
        cText = Color.parseColor(dark ? "#F3F4F6" : "#111827");
        cSub = Color.parseColor(dark ? "#9CA3AF" : "#6B7280");
        cDivider = Color.parseColor(dark ? "#1F2937" : "#EEF2F7");
        cAccent = Color.parseColor(dark ? "#3B82F6" : "#2563EB");
        cAccentSoft = Color.parseColor(dark ? "#172554" : "#EFF6FF");
        cOk = Color.parseColor(dark ? "#34D399" : "#059669");
        cOkSoft = Color.parseColor(dark ? "#064E3B" : "#ECFDF5");
        cWarn = Color.parseColor(dark ? "#FBBF24" : "#D97706");
        cWarnSoft = Color.parseColor(dark ? "#422006" : "#FFFBEB");
        cDanger = Color.parseColor(dark ? "#F87171" : "#DC2626");
        cDangerSoft = Color.parseColor(dark ? "#450A0A" : "#FEF2F2");
        cTrack = Color.parseColor(dark ? "#1E293B" : "#EEF2F7");

        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(cBg);
            w.setNavigationBarColor(cBg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decor = w.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (!dark) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        }
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(cSub);
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        parent.addView(tv);
    }

    private LinearLayout roundedCard(LinearLayout parent, int fillColor, int strokeColor, int radiusDp, int padDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(dp(1), strokeColor);
        card.setBackground(bg);
        int p = dp(padDp);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);
        parent.addView(card);
        return card;
    }

    private void addPillButton(LinearLayout row, String label, int bgColor, int textColor,
                               int leftMargin, int rightMargin, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        row.addView(btn);
    }

    private interface OnSelectListener {
        void onSelect(int idx);
    }

    private void addSegmentBar(LinearLayout parent, String[] labels, TextView[] refs, int selectedIdx, OnSelectListener listener) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(cTrack);
        trackBg.setCornerRadius(dp(12));
        bar.setBackground(trackBg);
        int p = dp(3);
        bar.setPadding(p, p, p, p);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView seg = new TextView(this);
            seg.setText(labels[i]);
            seg.setTextSize(13);
            seg.setGravity(Gravity.CENTER);
            seg.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            seg.setLayoutParams(lp);
            seg.setOnClickListener(v -> listener.onSelect(idx));
            refs[i] = seg;
            bar.addView(seg);
        }
        updateSegmentSelection(refs, selectedIdx);
        parent.addView(bar);
    }

    private void updateSegmentSelection(TextView[] refs, int selectedIdx) {
        for (int i = 0; i < refs.length; i++) {
            TextView tv = refs[i];
            if (tv == null) continue;
            if (i == selectedIdx) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(cAccent);
                pill.setCornerRadius(dp(9));
                tv.setBackground(pill);
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                tv.setBackground(null);
                tv.setTextColor(cSub);
                tv.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void addDivider(LinearLayout parent) {
        View div = new View(this);
        div.setBackgroundColor(cDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        div.setLayoutParams(lp);
        parent.addView(div);
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String summary, boolean checked,
                                android.widget.CompoundButton.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpText = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lpText.rightMargin = dp(12);
        textCol.setLayoutParams(lpText);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(cText);
        textCol.addView(tvTitle);

        TextView tvSum = new TextView(this);
        tvSum.setText(summary);
        tvSum.setTextSize(12);
        tvSum.setTextColor(cSub);
        tvSum.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvSum);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sw.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            sw.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{cAccent, cStroke}
            ));
        }
        sw.setOnCheckedChangeListener(onChange);

        row.addView(textCol);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private int indexOf(int[] arr, int val, int defIdx) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == val) return i;
        }
        return defIdx;
    }

    private int getGlobalInt(String key, int defVal) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, defVal);
        } catch (Throwable ignored) {
            return defVal;
        }
    }

    private String getSystemString(String key) {
        try {
            return Settings.System.getString(getContentResolver(), key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void runRootCmd(final String cmd) {
        runRootCmd(cmd, null);
    }

    private void runRootCmd(final String cmd, final Runnable onDone) {
        // 优先用 Settings API 直写：不依赖 root 授权，失败率高且无提示的 su 通道只作为兜底。
        if (cmd != null && !cmd.contains("&&")) {
            if (cmd.startsWith("settings put global ")) {
                String rest = cmd.substring("settings put global ".length()).trim();
                int sp = rest.indexOf(' ');
                if (sp > 0) {
                    String key = rest.substring(0, sp);
                    String val = rest.substring(sp + 1).trim();
                    try {
                        Settings.Global.putString(getContentResolver(), key, val);
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    } catch (Throwable t) {
                        android.util.Log.w("MadridSettings", "putGlobal " + key + " failed, fallback to su: " + t);
                    }
                }
            } else if (cmd.startsWith("settings put system ")) {
                String rest = cmd.substring("settings put system ".length()).trim();
                int sp = rest.indexOf(' ');
                if (sp > 0) {
                    String key = rest.substring(0, sp);
                    String val = rest.substring(sp + 1).trim();
                    try {
                        Settings.System.putString(getContentResolver(), key, val);
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    } catch (Throwable t) {
                        android.util.Log.w("MadridSettings", "putSystem " + key + " failed: " + t);
                    }
                }
            }
        }
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int code = p.waitFor();
                if (code != 0) {
                    runOnUiThread(() -> toast("命令执行失败，请确认已在 KernelSU 中授予本应用 root 权限"));
                } else if (onDone != null) {
                    runOnUiThread(onDone);
                }
            } catch (Throwable t) {
                runOnUiThread(() -> toast("需要 root 权限才能生效，请在 KernelSU 中授权本应用"));
            }
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
