package com.mw.launcher;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Keepalive policy for the AB02: this device is expected to receive calls at any moment, so it must
 * never sit in airplane mode and Bluetooth (earbud audio for calls) must stay on. The stock firmware's
 * "Standby / auto-off after N min idle" drops the radios after inactivity — this class undoes that the
 * instant it happens. Driven by {@link RadioGuardReceiver} (airplane/BT broadcasts + a 60s backstop tick).
 *
 * Flipping airplane mode needs WRITE_SECURE_SETTINGS (granted out-of-band via
 * `adb shell pm grant com.mw.launcher android.permission.WRITE_SECURE_SETTINGS`); without it the
 * Settings.Global write throws SecurityException and we simply no-op on the airplane half.
 */
final class RadioGuard {
    private RadioGuard() {}

    static void enforce(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();

        // 1) Force airplane mode OFF — keeps the SIM/modem registered so calls land.
        try {
            if (Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
                Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0);
                Intent i = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);  // "android.intent.action.AIRPLANE_MODE"
                i.putExtra("state", false);
                ctx.sendBroadcast(i);   // tell the framework to bring the radios back up
            }
        } catch (Exception ignored) {}

        // 2) Keep Bluetooth enabled — earbud audio path for taking calls.
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && !bt.isEnabled()) bt.enable();
        } catch (Exception ignored) {}
    }
}
