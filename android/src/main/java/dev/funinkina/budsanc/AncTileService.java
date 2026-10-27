package dev.funinkina.budsanc;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tap cycles Normal → Noise Cancellation → Transparent. Long-press opens MainActivity. Unavailable while the buds are disconnected. */
public class AncTileService extends TileService {
    private static volatile boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean connected = true; // optimistic until checked, so the tile doesn't flash unavailable
    private boolean listening;

    private final BroadcastReceiver a2dp = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || !Buds.MAC.equals(d.getAddress())) return;
            connected = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED;
            refresh();
        }
    };

    @Override
    public void onStartListening() {
        refresh();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        registerReceiver(a2dp, new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED);
        listening = true;
        Buds.checkConnected(this, on -> {
            connected = on;
            refresh();
        });
    }

    @Override
    public void onStopListening() {
        if (listening) unregisterReceiver(a2dp);
        listening = false;
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

    private void refresh() {
        render(busy ? "Switching…" : null);
    }

    private void render(String subtitle) {
        Tile tile = getQsTile();
        if (tile == null) return;
        if (!connected) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle("Not connected");
            tile.updateTile();
            return;
        }
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
