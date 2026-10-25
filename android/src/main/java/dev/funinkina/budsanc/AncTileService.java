package dev.funinkina.budsanc;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tap cycles Normal → Noise Cancellation → Transparent. Long-press opens MainActivity. */
public class AncTileService extends TileService {
    private static volatile boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        render(busy ? "Switching…" : null);
    }

    @Override
    public void onClick() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            openApp();
            return;
        }
        if (busy) return;
        busy = true;
        int next = (Buds.current(this) + 1) % Buds.NAMES.length;
        render("Switching…");
        new Thread(() -> {
            String status = null;
            try {
                Buds.set(this, next);
            } catch (Exception e) {
                status = "Failed";
            }
            busy = false;
            String s = status;
            main.post(() -> render(s));
        }).start();
    }

    private void render(String subtitle) {
        Tile tile = getQsTile();
        if (tile == null) return;
        int mode = Buds.current(this);
        tile.setState(mode == 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setSubtitle(subtitle != null ? subtitle : Buds.NAMES[mode]);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
