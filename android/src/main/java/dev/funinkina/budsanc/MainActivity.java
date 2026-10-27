package dev.funinkina.budsanc;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grants the Bluetooth permission the tile can't request, and offers direct mode controls. */
public class MainActivity extends Activity {
    private static final String[] DETAILS = {"Noise control off", "Blocks outside sound", "Lets outside sound in"};
    private final View[] modes = new View[Buds.NAMES.length];
    private TextView connection, detail;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!hasPermission()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
        setContentView(R.layout.activity_main);
        connection = findViewById(R.id.connection);
        detail = findViewById(R.id.detail);

        LinearLayout row = findViewById(R.id.modes);
        for (int i = 0; i < modes.length; i++) {
            View v = getLayoutInflater().inflate(R.layout.item_mode, row, false);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(Buds.ICONS[i]);
            ((TextView) v.findViewById(R.id.label)).setText(Buds.NAMES[i]);
            int mode = i;
            v.setOnClickListener(x -> set(mode));
            row.addView(v);
            modes[i] = v;
        }
        show(Buds.current(this), null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnection();
        boolean added = Buds.tileAdded(this);
        if (Build.VERSION.SDK_INT >= 33) {
            View add = findViewById(R.id.add_tile);
            add.setVisibility(added ? View.GONE : View.VISIBLE);
            add.setOnClickListener(v -> requestTile(add));
        } else {
            findViewById(R.id.hint).setVisibility(added ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refreshConnection();
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshConnection() {
        if (!hasPermission()) {
            connection.setText("Bluetooth permission needed");
            return;
        }
        Buds.checkConnected(this, on -> connection.setText(on ? "realme Buds T310 · Connected" : "Not connected"));
    }

    private void set(int mode) {
        if (busy) return;
        busy = true;
        show(mode, "Switching…");
        new Thread(() -> {
            String error = null;
            try {
                Buds.set(this, mode);
            } catch (Exception e) {
                error = "Couldn't switch: " + e.getMessage();
            }
            String err = error;
            runOnUiThread(() -> {
                busy = false;
                show(Buds.current(this), err);
            });
        }).start();
    }

    private void show(int mode, String message) {
        for (int i = 0; i < modes.length; i++) modes[i].setSelected(i == mode);
        detail.setText(message != null ? message : DETAILS[mode]);
    }

    @TargetApi(33)
    private void requestTile(View button) {
        getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, AncTileService.class), "Buds ANC",
                Icon.createWithResource(this, R.drawable.ic_buds), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Buds.setTileAdded(this, true);
                        button.setVisibility(View.GONE);
                    }
                });
    }
}
