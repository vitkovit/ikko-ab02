package com.mw.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Its only purpose is to be an *enabled* listener so the
 * launcher is allowed to call MediaSessionManager.getActiveSessions() and control
 * whatever is playing (YT Music etc.). Enabled once via Settings (or adb).
 */
public class MediaNotifListener extends NotificationListenerService {
}
