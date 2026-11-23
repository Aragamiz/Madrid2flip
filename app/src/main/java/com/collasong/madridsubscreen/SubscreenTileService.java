package com.collasong.madridsubscreen;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心快捷磁贴：展开态一键开启/关闭小米 18 背屏。
 */
public class SubscreenTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        new Thread(() -> {
            int state = readDeviceState();
            if (state == 5) {
                try {
                    sendBroadcast(new Intent(UnfoldOuterDisplayHook.ACTION_EXIT_REAR_SCREEN));
                } catch (Throwable ignored) {
                }
                runRootCmd("cmd device_state state reset");
            } else {
                runRootCmd("cmd device_state state 5 && sleep 0.4 && am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            updateTileState();
        }).start();
    }

    private void updateTileState() {
        new Thread(() -> {
            int state = readDeviceState();
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(state == 5 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setLabel("小米18背屏");
                tile.updateTile();
            }
        }).start();
    }

    private int readDeviceState() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cmd device_state print-state"});
            byte[] buf = new byte[32];
            int n = p.getInputStream().read(buf);
            p.waitFor();
            if (n > 0) {
                return Integer.parseInt(new String(buf, 0, n).trim());
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void runRootCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }
}
