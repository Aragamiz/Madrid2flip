package com.collasong.madridsubscreen;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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

    private boolean dark;
    private int cBg, cCard, cStroke, cText, cSub, cDivider, cAccent, cAccentSoft;
    private int cOk, cOkSoft, cDanger, cDangerSoft, cTrack;

    private TextView statusBadge, statusDetail;
    private TextView[] capSegments = new TextView[CAP_VALUES.length];
    private TextView[] scaleSegments = new TextView[SCALE_VALUES.length];
    private TextView[] timeoutSegments = new TextView[TIMEOUT_VALUES.length];
    private TextView[] burninSegments = new TextView[BURNIN_VALUES.length];
    private TextView[] foldedExitSegments = new TextView[FOLDED_EXIT_VALUES.length];

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
        subtitle.setText("v1.0.6 · 小米 18 背屏中心 (OS4.0.31.0) · MIX Flip 2 专用");
        subtitle.setTextSize(13);
        subtitle.setTextColor(cSub);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        // 2. 顶部状态横幅
        LinearLayout banner = roundedCard(root, cOkSoft, cOk, 18, 14);
        statusBadge = new TextView(this);
        statusBadge.setText("●  小米 18 背屏引擎就绪");
        statusBadge.setTextSize(15);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setTextColor(cOk);
        banner.addView(statusBadge);

        statusDetail = new TextView(this);
        statusDetail.setText("双摄对齐 1208×738 · 背面光感自动亮度 · 背面接近传感器防误亮");
        statusDetail.setTextSize(12);
        statusDetail.setTextColor(cSub);
        statusDetail.setPadding(0, dp(4), 0, 0);
        banner.addView(statusDetail);

        // 3. 快捷操作卡片
        addSectionTitle(root, "快捷控制");
        LinearLayout quickCard = roundedCard(root, cCard, cStroke, 18, 14);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addPillButton(row1, "开启展开态背屏", cAccent, Color.WHITE, 0, dp(8), () -> {
            try {
                sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_ENTER_REAR_SCREEN));
            } catch (Throwable ignored) {
            }
            runRootCmd("cmd device_state state 5 && sleep 0.4 && am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher");
            toast("已开启展开态背屏（双击下方区域或合盖可退出）");
        });
        addPillButton(row1, "关闭背屏", cDangerSoft, cDanger, dp(8), 0, () -> {
            try {
                sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
            } catch (Throwable ignored) {
            }
            runRootCmd("cmd device_state state reset");
            toast("已关闭背屏并恢复默认外屏");
        });
        quickCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);
        row2.setLayoutParams(row2Lp);
        addPillButton(row2, "管理官方应用卡", cAccentSoft, cAccent, 0, 0, () -> {
            try {
                Intent it = new Intent("miui.intent.action.REAR_APP_MINE");
                it.setPackage("com.xiaomi.subscreencenter");
                startActivity(it);
            } catch (Throwable t) {
                toast("打开应用卡管理失败: " + t.getMessage());
            }
        });
        quickCard.addView(row2);

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

        addDivider(gestureCard);

        addSwitchRow(gestureCard,
                "背屏左滑直接打开外屏自拍",
                "在背屏右侧边缘或下方毛玻璃/空白区向左滑动，直接拉起 Flip 2 原厂外屏自拍相机",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_SWIPE_CAMERA, 1) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_SWIPE_CAMERA + " " + (checked ? "1" : "0"));
                    toast(checked ? "已开启左滑打开外屏自拍" : "已关闭左滑打开外屏自拍");
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
                "倒握或倒立放置手机时自动平滑旋转背屏 180° 并同步转换触控坐标",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE, 1) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_AUTO_ROTATE + " " + (checked ? "1" : "0"));
                    toast(checked ? "已开启背屏重力倒置旋转" : "已关闭背屏重力倒置旋转");
                });

        addDivider(displayCard);

        addSwitchRow(displayCard,
                "实时取色毛玻璃扩展下方",
                "保持上方 1208×738 应用卡完整不变形，实时采样背屏颜色做毛玻璃填补（防分界线烧屏）",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_FULLSCREEN + " " + (checked ? "1" : "0")
                            + " && am force-stop com.xiaomi.subscreencenter");
                    toast(checked ? "已开启实时取色毛玻璃扩展" : "已切回纯黑 OLED 底部");
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
                "展开使用时外屏不再响应任何触摸（含双击退出、左滑自拍）；内屏触控、背屏画面与壁纸均不受影响，关闭后立即恢复。",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_TOUCH_LOCK + " " + (checked ? "1" : "0"));
                    toast(checked ? "已锁定背屏触摸，外屏不再响应触摸" : "已解除背屏触摸锁定");
                });

        addDivider(lockCard);

        addSwitchRow(lockCard,
                "视频壁纸循环播放",
                "背屏动态壁纸播放到结尾时立即从头重播，不再停在最后一帧；关闭后恢复系统默认的播完暂停。",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_VIDEO_LOOP, 0) == 1,
                (b, checked) -> {
                    runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_VIDEO_LOOP + " " + (checked ? "1" : "0"));
                    toast(checked ? "已开启视频壁纸循环播放" : "已关闭循环，恢复播完暂停");
                });

        // 5. 息屏与自动化卡片
        addSectionTitle(root, "息屏与自动化（方案 A）");
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
                "背面接近传感器（33171123）检测到背面朝下遮挡 1.5 秒后自动熄灭外屏",
                getGlobalInt(UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF, 1) == 1,
                (b, checked) -> runRootCmd("settings put global " + UnfoldOuterDisplayHook.KEY_REAR_TABLE_OFF + " " + (checked ? "1" : "0")));

        // 底部提示
        TextView foot = new TextView(this);
        foot.setText("提示：也可在控制中心编辑添加「小米18背屏」磁贴一键开关；每 15 秒内置 0~180px 积极防烧屏上下微移。");
        foot.setTextSize(12);
        foot.setTextColor(cSub);
        foot.setPadding(dp(4), dp(14), dp(4), 0);
        foot.setLineSpacing(dp(2), 1.0f);
        root.addView(foot);

        setContentView(scroll);
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

    private void addSwitchRow(LinearLayout parent, String title, String summary, boolean checked,
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
        // 优先用 Settings API 直写：不依赖 root 授权，失败率高且无提示的 su 通道只作为兜底。
        if (cmd != null) {
            if (cmd.startsWith("settings put global ")) {
                String rest = cmd.substring("settings put global ".length()).trim();
                int sp = rest.indexOf(' ');
                if (sp > 0) {
                    String key = rest.substring(0, sp);
                    String val = rest.substring(sp + 1).trim();
                    try {
                        Settings.Global.putString(getContentResolver(), key, val);
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
