package com.mw.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Foreground service that keeps the launcher process alive enough to react to radio drops, and owns the
 * recurring 60s backstop alarm. The instant reactions come from {@link RadioGuardReceiver} (broadcasts);
 * this tick catches anything that slips through (e.g. Bluetooth toggled without a delivered broadcast).
 * Uses setExactAndAllowWhileIdle so it still fires in Doze — no permanent wakelock, so battery cost is a
 * brief CPU wake once a minute rather than a pinned core.
 */
public class KeepRadiosService extends Service {
    private static final String CH = "keepalive";
    private static final int NOTE_ID = 42;
    private static final long INTERVAL_MS = 60_000L;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepRadiosService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "Signal keepalive",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        startForeground(NOTE_ID, buildNote());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RadioGuard.enforce(this);
        scheduleTick();
        return START_STICKY;
    }

    private Notification buildNote() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Signal keepalive")
                .setContentText("Keeping SIM & Bluetooth active")
                .setOngoing(true)
                .build();
    }

    private void scheduleTick() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(this, RadioGuardReceiver.class).setAction(RadioGuardReceiver.ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        long when = SystemClock.elapsedRealtime() + INTERVAL_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
        else
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
