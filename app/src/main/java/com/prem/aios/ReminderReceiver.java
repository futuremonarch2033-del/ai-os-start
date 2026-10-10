package com.prem.aios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra("id", 0L);
        String text = intent.getStringExtra("text");
        if (text == null) {
            text = "(reminder)";
        }
        post(context, id, "Reminder", text);
        Reminders.remove(context, id);
    }

    static void post(Context context, long id, String title, String text) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Reminders", NotificationManager.IMPORTANCE_HIGH));
        }
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        nm.notify((int) (id & 0x7fffffff), b.build());
    }
}
