package dev.funinkina.budsanc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grants the Bluetooth permission the tile can't request, and offers direct mode buttons. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 0);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 64, 64, 64);

        TextView hint = new TextView(this);
        hint.setText("Add the \"Buds ANC\" tile: pull down Quick Settings → edit (pencil) → drag it in.");
        root.addView(hint);

        TextView status = new TextView(this);
        status.setPadding(0, 48, 0, 24);
        status.setText("Last set: " + Buds.NAMES[Buds.current(this)]);
        root.addView(status);

        for (int i = 0; i < Buds.NAMES.length; i++) {
            int mode = i;
            Button button = new Button(this);
            button.setText(Buds.NAMES[i]);
            button.setOnClickListener(v -> {
                status.setText("Switching…");
                new Thread(() -> {
                    String text;
                    try {
                        Buds.set(this, mode);
                        text = "Last set: " + Buds.NAMES[mode];
                    } catch (Exception e) {
                        text = "Failed: " + e.getMessage();
                    }
                    String t = text;
                    runOnUiThread(() -> status.setText(t));
                }).start();
            });
            root.addView(button);
        }
        setContentView(root);
    }
}
