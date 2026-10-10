package com.prem.aios;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reminders tool: reminders stored on disk (SharedPreferences) and armed
 * with AlarmManager.setAlarmClock. Re-armed after reboot by BootReceiver.
 */
public class Reminders {

    private static final String PREFS = "ai_os_reminders";
    private static final String KEY = "items";

    public static class Item {
        public long id;
        public long time;
        public String text;
    }

    public static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<Item>();
        SharedPreferences p = c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.getLong("id");
                it.time = o.getLong("time");
                it.text = o.getString("text");
                out.add(it);
            }
        } catch (Exception e) {
            // corrupted store: treated as empty
        }
        return out;
    }

    private static boolean save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                arr.put(new JSONObject().put("id", it.id)
                        .put("time", it.time).put("text", it.text));
            }
        } catch (Exception e) {
            return false;
        }
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).commit();
    }

    public static synchronized boolean add(Context c, Item it) {
        List<Item> items = all(c);
        items.add(it);
        return save(c, items);
    }

    public static synchronized boolean remove(Context c, long id) {
        List<Item> items = all(c);
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                items.remove(i);
                changed = true;
                break;
            }
        }
        return changed && save(c, items);
    }

    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent firePi(Context c, Item it) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra("id", it.id);
        i.putExtra("text", it.text);
        return PendingIntent.getBroadcast(c, (int) (it.id & 0x7fffffff), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arms the alarm. Returns null on success, or an error text. */
    public static String arm(Context c, Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return "AlarmManager unavailable";
        }
        try {
            PendingIntent show = PendingIntent.getActivity(c, 0,
                    new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(it.time, show), firePi(c, it));
            return null;
        } catch (SecurityException e) {
            return "exact alarm permission denied (" + e.getMessage() + ")";
        } catch (Exception e) {
            return "alarm failed (" + e + ")";
        }
    }

    public static void disarm(Context c, Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(firePi(c, it));
        }
    }

    public static String format(long t) {
        return new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(new Date(t));
    }
}
