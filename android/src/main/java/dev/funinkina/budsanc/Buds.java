package dev.funinkina.budsanc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.UUID;

/** Same protocol as realme_anc.py: one 'aa' frame over SPP, earbuds ACK it. */
final class Buds {
    static final String MAC = "88:0E:85:C5:FA:87";
    static final String[] NAMES = {"Normal", "Noise Cancellation", "Transparent"};
    private static final byte[] CODES = {0x01, 0x08, 0x02};
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final long TIMEOUT_MS = 6000;

    static int current(Context c) {
        return prefs(c).getInt("mode", 0);
    }

    /** Blocking; call off the main thread. The earbuds don't report mode, so the last one set is remembered. */
    static void set(Context c, int mode) throws IOException {
        BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth is off");
        BluetoothSocket sock = adapter.getRemoteDevice(MAC).createRfcommSocketToServiceRecord(SPP);

        // connect() and read() have no timeout; closing the socket unblocks both.
        Handler main = new Handler(Looper.getMainLooper());
        Runnable kill = () -> {
            try { sock.close(); } catch (IOException ignored) {}
        };
        main.postDelayed(kill, TIMEOUT_MS);
        try {
            sock.connect();
            sock.getOutputStream().write(new byte[]{
                    (byte) 0xAA, 0x0A, 0x00, 0x00, 0x04, 0x04, 0x01, 0x03, 0x00, 0x01, 0x01, CODES[mode]});
            byte[] ack = new byte[64];
            int n = sock.getInputStream().read(ack);
            if (n < 10 || ack[5] != (byte) 0x84 || ack[9] != 0) throw new IOException("Earbuds rejected the command");
        } finally {
            main.removeCallbacks(kill);
            sock.close();
        }
        prefs(c).edit().putInt("mode", mode).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("buds", Context.MODE_PRIVATE);
    }
}
