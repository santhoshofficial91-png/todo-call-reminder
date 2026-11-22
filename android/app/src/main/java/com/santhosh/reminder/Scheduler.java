package com.santhosh.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.TimeZone;

/** All alarm logic lives here: what rings when, retries, and a best-effort refresh from the server. */
public class Scheduler {
    static final String API = "https://nicynemyjdiukkbltjwq.supabase.co/functions/v1/task-api";
    static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("rem", Context.MODE_PRIVATE);
    }

    static JSONArray tasks(Context c) {
        try { return new JSONArray(p(c).getString("tasks", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject rung(Context c) {
        try { return new JSONObject(p(c).getString("rung", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static long parse(String s) {
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception e) { return -1; }
    }

    static void saveTasks(Context c, String json) {
        try { new JSONArray(json); } catch (Exception e) { return; }
        p(c).edit().putString("tasks", json).apply();
        // forget "already rang" marks for tasks that are gone
        try {
            JSONArray ts = new JSONArray(json);
            JSONObject r = rung(c), keep = new JSONObject();
            for (int i = 0; i < ts.length(); i++) {
                String id = ts.getJSONObject(i).optString("id");
                if (r.has(id)) keep.put(id, r.get(id));
            }
            p(c).edit().putString("rung", keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Start of the next IST hour (8..21) strictly after 'after'. */
    static long nextHourly(long after) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(after);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 30; i++) {
            cal.add(Calendar.HOUR_OF_DAY, 1);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            if (h >= 8 && h <= 21) return cal.getTimeInMillis();
        }
        return after + 3600000L;
    }

    /** Start of the current IST hour if it is an hourly slot (8..21), else -1. */
    static long currentSlot(long now) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return (h >= 8 && h <= 21) ? cal.getTimeInMillis() : -1;
    }

    /** Set the single next system alarm (alarm-clock type: works through Doze and on the lock screen). */
    static void schedule(Context c) {
        long now = System.currentTimeMillis();
        long next = nextHourly(now);
        JSONArray ts = tasks(c);
        JSONObject r = rung(c);
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            String ds = t.optString("due_at", "");
            long due = parse(ds);
            if (due < 0) continue;
            if (ds.equals(r.optString(t.optString("id"), ""))) continue;
            if (due < now - 3600000L) continue;
            next = Math.min(next, Math.max(due, now + 2000));
        }
        long ra = p(c).getLong("retryAt", 0);
        if (ra > 0) next = Math.min(next, Math.max(ra, now + 1500));

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("com.santhosh.reminder.FIRE");
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(c, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(c, 8, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(next, showPi), pi);
        } catch (Exception e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi); } catch (Exception ignored) {}
        }
        p(c).edit().putLong("nextAt", next).apply();
    }

    /** Called when the system alarm fires. Runs on a background thread. */
    static void onFire(Context c) {
        refresh(c);
        long now = System.currentTimeMillis();
        JSONArray ts = tasks(c);
        JSONObject r = rung(c);

        JSONArray ids = new JSONArray();
        JSONArray titles = new JSONArray();
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            String ds = t.optString("due_at", "");
            long due = parse(ds);
            if (due < 0 || ds.equals(r.optString(t.optString("id"), ""))) continue;
            if (due <= now + 30000L && due >= now - 3600000L) {
                ids.put(t.optString("id"));
                titles.put(t.optString("title"));
                try { r.put(t.optString("id"), ds); } catch (Exception ignored) {}
            }
        }
        long slot = currentSlot(now);
        boolean slotDue = slot > 0 && slot > p(c).getLong("lastSlot", 0);
        long ra = p(c).getLong("retryAt", 0);
        boolean retryDue = ra > 0 && now >= ra - 2000;

        SharedPreferences.Editor ed = p(c).edit();
        ed.putString("rung", r.toString());
        if (slotDue) ed.putLong("lastSlot", slot);

        if (ids.length() > 0) {
            ed.putInt("retryN", 0).putLong("retryAt", 0).apply();
            ring(c, "task", ids, titles);
        } else if (retryDue) {
            ed.apply();
            String mode = p(c).getString("retryMode", "task");
            JSONArray rids = pendingOnly(c, p(c).getString("retryIds", "[]"), mode);
            if (rids.length() == 0) {
                p(c).edit().putLong("retryAt", 0).putInt("retryN", 0).apply();
            } else {
                p(c).edit().putLong("retryAt", 0).apply(); // keeps retryN so the next gap is 5 min
                ring(c, mode, rids, titlesFor(c, rids));
            }
        } else if (slotDue && ts.length() > 0) {
            ed.putInt("retryN", 0).putLong("retryAt", 0).apply();
            JSONArray all = new JSONArray();
            for (int i = 0; i < ts.length(); i++) all.put(ts.optJSONObject(i).optString("id"));
            ring(c, "hourly", all, titlesFor(c, all));
        } else {
            ed.apply();
        }
        schedule(c);
    }

    static JSONArray pendingOnly(Context c, String idsJson, String mode) {
        JSONArray out = new JSONArray();
        try {
            JSONArray want = new JSONArray(idsJson);
            JSONArray ts = tasks(c);
            for (int i = 0; i < want.length(); i++) {
                for (int j = 0; j < ts.length(); j++) {
                    if (ts.getJSONObject(j).optString("id").equals(want.getString(i))) { out.put(want.getString(i)); break; }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static JSONArray titlesFor(Context c, JSONArray ids) {
        JSONArray out = new JSONArray();
        JSONArray ts = tasks(c);
        for (int i = 0; i < ids.length(); i++) {
            for (int j = 0; j < ts.length(); j++) {
                JSONObject t = ts.optJSONObject(j);
                if (t != null && t.optString("id").equals(ids.optString(i))) { out.put(t.optString("title")); break; }
            }
        }
        return out;
    }

    static void ring(Context c, String mode, JSONArray ids, JSONArray titles) {
        Intent i = new Intent(c, RingService.class);
        i.putExtra("mode", mode);
        i.putExtra("ids", ids.toString());
        i.putExtra("titles", titles.toString());
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            p(c).edit().putString("lastError", String.valueOf(e)).apply();
        }
    }

    /** Declined / missed / cut: ring again after 1 min, then every 5 min (up to 7 times; hourly up to 5). */
    static void scheduleRetry(Context c, String mode, String idsJson) {
        int n = p(c).getInt("retryN", 0) + 1;
        int max = mode.equals("hourly") ? 5 : 7;
        if (n > max) {
            p(c).edit().putInt("retryN", 0).putLong("retryAt", 0).apply();
        } else {
            long gap = n == 1 ? 60000L : 300000L;
            p(c).edit().putInt("retryN", n).putLong("retryAt", System.currentTimeMillis() + gap)
                    .putString("retryMode", mode).putString("retryIds", idsJson).apply();
        }
        schedule(c);
    }

    static void clearRetry(Context c) {
        p(c).edit().putInt("retryN", 0).putLong("retryAt", 0).apply();
        schedule(c);
    }

    /** Best-effort pull of the pending list from the server (3 s timeouts, ignores failures). */
    static void refresh(Context c) {
        String token = p(c).getString("token", "");
        if (token.isEmpty()) return;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(API).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(5000);
            h.setRequestMethod("POST");
            h.setRequestProperty("content-type", "application/json");
            h.setRequestProperty("x-app-token", token);
            h.setDoOutput(true);
            try (OutputStream os = h.getOutputStream()) { os.write("{\"action\":\"list\"}".getBytes("UTF-8")); }
            if (h.getResponseCode() != 200) return;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            java.io.InputStream in = h.getInputStream();
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            JSONArray pend = j.getJSONArray("pending");
            JSONArray slim = new JSONArray();
            for (int i = 0; i < pend.length(); i++) {
                JSONObject t = pend.getJSONObject(i), o = new JSONObject();
                o.put("id", t.optString("id")); o.put("title", t.optString("title")); o.put("due_at", t.optString("due_at", ""));
                slim.put(o);
            }
            saveTasks(c, slim.toString());
        } catch (Exception ignored) {
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
