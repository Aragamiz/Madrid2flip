package com.collasong.madridsubscreen;

import io.github.aragamiz.madridsubscreen.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 接收来自 system_server 的轻敲背面切换成功事件，弹出简洁状态通知与短提示。
 */
public class RearScreenNotifyReceiver extends BroadcastReceiver {

    public static final String ACTION_NOTIFY_REAR_TOGGLED = "com.collasong.madridsubscreen.ACTION_NOTIFY_REAR_TOGGLED";
    public static final String EXTRA_ENABLED = "enabled";
    private static final String CHANNEL_ID = "madrid_rear_backtap_status";
    private static final int NOTIFY_ID = 1801;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_NOTIFY_REAR_TOGGLED.equals(intent.getAction())) {
            return;
        }
        boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, true);
        String title = enabled ? "小米 18 背屏已唤起" : "小米 18 背屏已关闭";
        String body = enabled ? "轻敲背面触发成功 · 双击下方区域或合盖可退出" : "已恢复 MIX Flip 2 默认外屏状态";

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "背屏唤起通知", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
                Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? new Notification.Builder(context, CHANNEL_ID)
                        : new Notification.Builder(context);
                b.setSmallIcon(R.drawable.ic_subscreen_tile)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setTimeoutAfter(3000L);
                nm.notify(NOTIFY_ID, b.build());
            }
        } catch (Throwable ignored) {
        }

        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context.getApplicationContext(), title, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }
}
