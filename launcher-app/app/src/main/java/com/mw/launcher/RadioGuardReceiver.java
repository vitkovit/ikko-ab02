package com.mw.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reacts the instant a radio is dropped: airplane-mode changes, Bluetooth state changes, boot, and our
 * own 60s backstop tick all funnel through here. Every event runs {@link RadioGuard#enforce} and makes
 * sure the keepalive service (which owns the recurring tick) is alive.
 */
public class RadioGuardReceiver extends BroadcastReceiver {
    static final String ACTION_TICK = "com.mw.launcher.RADIO_TICK";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        RadioGuard.enforce(ctx);
        KeepRadiosService.start(ctx);   // (re)start the service / reschedule the next tick
    }
}
