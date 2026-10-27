package dev.funinkina.budsanc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * realme 'aa' protocol over SPP (decoded from a realme Link btsnoop capture):
 * aa | len | 00 00 | cmd | type | seq | datalen (LE16) | data. Type 01 = read, 04 = write; replies set the high bit.
 */
final class Buds {
    static final String MAC = "88:0E:85:C5:FA:87";
    static final String[] NAMES = {"Normal", "Noise Cancellation", "Transparent"};
    static final int[] ICONS = {R.drawable.ic_noise_control_off, R.drawable.ic_noise_control_on, R.drawable.ic_noise_aware};
    private static final byte[] CODES = {0x01, 0x08, 0x02};
    private static final int GAME = 0x06; // feature id, from the realme Link capture
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final long TIMEOUT_MS = 6000;

    static int current(Context c) {
        return prefs(c).getInt("mode", 0);
    }

    static boolean game(Context c) {
        return prefs(c).getBoolean("game", false);
    }

    /** Async; cb runs on the main thread. Requires BLUETOOTH_CONNECT. */
    static void checkConnected(Context c, Consumer<Boolean> cb) {
        BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                boolean on = proxy.getConnectionState(adapter.getRemoteDevice(MAC)) == BluetoothProfile.STATE_CONNECTED;
                adapter.closeProfileProxy(profile, proxy);
                cb.accept(on);
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        };
        if (adapter == null || !adapter.isEnabled() || !adapter.getProfileProxy(c, listener, BluetoothProfile.A2DP)) {
            cb.accept(false);
        }
    }

    /** Blocking; call off the main thread. Asks the buds for their mode (it changes when the buds are long-pressed). */
    static synchronized int read(Context c) throws IOException {
        byte[] r = exchange(c, frame(0x0c, 0x01, 0x01, 0x01), 0x81);
        int mode = r.length > 12 && r[9] == 0 ? indexOf(r[12]) : -1;
        if (mode < 0) throw new IOException("Unexpected mode reply");
        prefs(c).edit().putInt("mode", mode).apply();
        return mode;
    }

    /** Blocking; call off the main thread. */
    static synchronized void set(Context c, int mode) throws IOException {
        byte[] r = exchange(c, frame(0x04, 0x04, 0x01, 0x01, CODES[mode]), 0x84);
        if (r[9] != 0) throw new IOException("Earbuds rejected the command");
        prefs(c).edit().putInt("mode", mode).apply();
    }

    /** Blocking; call off the main thread. */
    static synchronized boolean readGame(Context c) throws IOException {
        byte[] r = exchange(c, frame(0x0d, 0x01, 0x01, GAME), 0x81);
        if (r.length < 13 || r[9] != 0 || r[11] != GAME) throw new IOException("Unexpected game mode reply");
        boolean on = r[12] != 0;
        prefs(c).edit().putBoolean("game", on).apply();
        return on;
    }

    /** Blocking; call off the main thread. */
    static synchronized void setGame(Context c, boolean on) throws IOException {
        byte[] r = exchange(c, frame(0x03, 0x04, GAME, on ? 1 : 0), 0x84);
        if (r[9] != 0) throw new IOException("Earbuds rejected the command");
        prefs(c).edit().putBoolean("game", on).apply();
    }

    /** Refreshes the remembered state from the buds in the background, then runs done on the main thread. */
    static void sync(Context c, boolean withGame, Runnable done) {
        new Thread(() -> {
            try {
                read(c);
                if (withGame) readGame(c);
            } catch (Exception ignored) {
                // Keep the last known state.
            }
            new Handler(Looper.getMainLooper()).post(done);
        }).start();
    }

    private static byte[] frame(int cmd, int type, int... data) {
        byte[] f = new byte[9 + data.length];
        f[0] = (byte) 0xAA;
        f[1] = (byte) (7 + data.length);
        f[4] = (byte) cmd;
        f[5] = (byte) type;
        f[6] = 0x01;
        f[7] = (byte) data.length;
        for (int i = 0; i < data.length; i++) f[9 + i] = (byte) data[i];
        return f;
    }

    /** Sends one frame and returns the reply with the same cmd and the given type, skipping notifications. */
    private static byte[] exchange(Context c, byte[] frame, int replyType) throws IOException {
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
            sock.getOutputStream().write(frame);
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[512];
            int len = 0;
            while (true) {
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) throw new IOException("Earbuds closed the connection");
                len += n;
                int i = 0;
                while (len - i >= 2 && len - i >= (buf[i + 1] & 0xff) + 2) {
                    if (buf[i] != (byte) 0xAA) throw new IOException("Unexpected data from earbuds");
                    int size = (buf[i + 1] & 0xff) + 2;
                    if (size >= 10 && buf[i + 4] == frame[4] && (buf[i + 5] & 0xff) == replyType) {
                        return Arrays.copyOfRange(buf, i, i + size);
                    }
                    i += size;
                }
                System.arraycopy(buf, i, buf, 0, len - i);
                len -= i;
                if (len == buf.length) throw new IOException("Reply too long");
            }
        } finally {
            main.removeCallbacks(kill);
            sock.close();
        }
    }

    private static int indexOf(byte code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i] == code) return i;
        return -1;
    }

    static boolean tileAdded(Context c) {
        return prefs(c).getBoolean("tile", false);
    }

    static void setTileAdded(Context c, boolean added) {
        prefs(c).edit().putBoolean("tile", added).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("buds", Context.MODE_PRIVATE);
    }
}
