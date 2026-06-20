package com.mw.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.ikkoaudio.core.serialport.jni.SerialPort;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Sends earbud noise-cancelling commands to the buds over /dev/ttyS1, reusing the
 * stock IKKO native serial library. Protocol reverse-engineered from the stock
 * launcher (com.ikkoaudio.launcher):
 *
 *   frame  = A5 | lenHi | lenLo | payload... | crc8
 *   len    = payload.length + 1   (covers payload + the crc byte)
 *   crc8   = Dallas/Maxim CRC-8, poly 0x8C, init 0x00, over every byte except the crc
 *   ANC payload = { 0x12, 0x01, seq, 0x00, 0x01, mode }
 *     seq  = rolling per-command counter, 0,1,2,… (mod 256)
 *     mode = 0 Standard, 1 ANC, 2 Ambient
 *
 *   -> wire: A5 00 07 12 01 <seq> 00 01 <mode> <crc8>
 *
 * /dev/ttyS1 is mode 0666 and SELinux is permissive on this device, so a normal app
 * can open it directly.
 */
public final class AncController {

    private static final String TAG = "AncController";
    private static final String DEVICE = "/dev/ttyS1";
    private static final int BAUD = 9600;

    public static final int STANDARD = 0, ANC = 1, AMBIENT = 2;

    // Buds BT control channel (worn buds): RFCOMM/SPP, reused from the stock launcher.
    private static final UUID SPP_UUID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final Handler io;
    private SerialPort port;
    private boolean failed;
    private int seq;   // rolling counter for ANC command key 0x0c
    private int seqHs; // rolling counter for the handshake (key 0x01)
    private BluetoothSocket btSocket;
    private OutputStream btOut;

    public AncController() {
        HandlerThread t = new HandlerThread("anc-serial");
        t.start();
        io = new Handler(t.getLooper());
    }

    /** Switch the buds to the given mode. Non-blocking; runs on the worker thread. */
    public void setMode(int mode) {
        io.post(() -> {
            byte[] frame = buildAncFrame(mode);
            // Worn buds -> Bluetooth RFCOMM; docked buds -> serial. Try BT first.
            if (sendBt(frame)) {
                Log.i(TAG, "sent (BT) mode " + mode + " -> " + hex(frame));
                return;
            }
            try {
                ensureOpen();
                port.writeBytes(frame);
                Log.i(TAG, "sent (serial) mode " + mode + " -> " + hex(frame));
            } catch (Throwable e) {
                Log.w(TAG, "setMode(" + mode + ") failed on both transports", e);
            }
        });
    }

    private boolean sendBt(byte[] frame) {
        try {
            if (btOut == null || btSocket == null || !btSocket.isConnected()) {
                closeBt();
                BluetoothDevice buds = findBuds();
                if (buds == null) { Log.w(TAG, "no bonded buds device"); return false; }
                Log.i(TAG, "connecting RFCOMM to " + buds.getName() + " / " + buds.getAddress());
                btSocket = buds.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                btOut = btSocket.getOutputStream();
                Log.i(TAG, "RFCOMM connected");
                startBtReader(btSocket.getInputStream());
                // Session-init the buds expect before honoring control (type 0x01),
                // exactly as the stock launcher's ConnectBudsFragment does on connect.
                byte[] hs = frame(new byte[]{0x01, 0x01, (byte) (seqHs++ & 0xFF), 0x00, 0x00});
                btOut.write(hs); btOut.flush();
                Log.i(TAG, "sent handshake -> " + hex(hs));
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            btOut.write(frame);
            btOut.flush();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "BT send failed: " + e.getMessage());
            closeBt();
            return false;
        }
    }

    private BluetoothDevice findBuds() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return null;
            BluetoothDevice fallback = null;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName() == null ? "" : d.getName();
                if (n.toLowerCase().contains("activebuds") && !n.toLowerCase().contains("case")) return d;
                if (n.toLowerCase().contains("buds")) fallback = d;
            }
            return fallback;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Logs anything the buds send back — an ACK/status proves they processed our channel. */
    private void startBtReader(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] got = new byte[n];
                    System.arraycopy(buf, 0, got, 0, n);
                    Log.i(TAG, "BT RX <- " + hex(got));
                }
            } catch (Throwable e) {
                Log.w(TAG, "BT reader ended: " + e.getMessage());
            }
        }, "anc-bt-reader");
        t.setDaemon(true);
        t.start();
    }

    private void closeBt() {
        try { if (btSocket != null) btSocket.close(); } catch (Throwable ignored) {}
        btSocket = null; btOut = null;
    }

    private void ensureOpen() throws Exception {
        if (port != null || failed) {
            if (failed) throw new IllegalStateException("serial unavailable");
            return;
        }
        try {
            port = new SerialPort(new File(DEVICE), BAUD);
            Log.i(TAG, "opened " + DEVICE + " @ " + BAUD);
        } catch (Throwable e) {
            failed = true;
            Log.w(TAG, "open " + DEVICE + " failed", e);
            throw e;
        }
    }

    private byte[] buildAncFrame(int mode) {
        int s = seq;
        seq = (seq + 1) & 0xFF;
        byte[] payload = { 0x12, 0x01, (byte) s, 0x00, 0x01, (byte) mode };
        return frame(payload);
    }

    /** Wrap a payload: A5 | len(2) | payload | crc8. */
    static byte[] frame(byte[] payload) {
        int len = payload.length + 1;                  // payload + crc
        byte[] out = new byte[payload.length + 4];     // A5 + len(2) + payload + crc
        out[0] = (byte) 0xA5;
        out[1] = (byte) ((len >> 8) & 0xFF);
        out[2] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, out, 3, payload.length);
        out[out.length - 1] = crc8(out, out.length - 1);
        return out;
    }

    /** Dallas/Maxim CRC-8, polynomial 0x8C (reflected), init 0x00, over data[0..len-1]. */
    static byte crc8(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                int mix = (crc ^ b) & 1;
                crc >>= 1;
                if (mix != 0) crc ^= 0x8C;
                b >>= 1;
            }
        }
        return (byte) crc;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}
