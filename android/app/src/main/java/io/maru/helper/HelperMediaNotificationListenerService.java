package io.maru.helper;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class HelperMediaNotificationListenerService extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        HelperMediaSessionMonitor.getInstance().onListenerConnected(getApplicationContext());
    }

    @Override
    public void onListenerDisconnected() {
        HelperMediaSessionMonitor.getInstance().onListenerDisconnected();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        HelperMediaSessionMonitor.getInstance().onListenerConnected(getApplicationContext());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        HelperMediaSessionMonitor.getInstance().onListenerConnected(getApplicationContext());
    }
}
