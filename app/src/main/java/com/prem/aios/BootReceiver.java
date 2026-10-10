package com.prem.aios;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Re-arms stored reminders after a reboot. Past-due ones are reported as missed. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Reminders.Item> items = Reminders.all(context);
        for (Reminders.Item it : items) {
            if (it.time > now) {
                Reminders.arm(context, it);
            } else {
                ReminderReceiver.post(context, it.id, "Missed reminder",
                        it.text + " (was due " + Reminders.format(it.time) + ")");
                Reminders.remove(context, it.id);
            }
        }
    }
}
