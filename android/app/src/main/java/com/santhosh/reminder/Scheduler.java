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
import android.location.Location;
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

    /** Start of the next IST hour (9..21) strictly after 'after'. */
    static long nextHourly(long after) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(after);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 30; i++) {
            cal.add(Calendar.HOUR_OF_DAY, 1);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            if (h >= 9 && h <= 21) return cal.getTimeInMillis();
        }
        return after + 3600000L;
    }

    /** Start of the current IST hour if it is an hourly slot (9..21), else -1. */
    static long currentSlot(long now) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return (h >= 9 && h <= 21) ? cal.getTimeInMillis() : -1;
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
        next = Math.min(next, nextCommute(c, now));

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

    static String ymd(Calendar cal) {
        return cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH) + 1) + "-" + cal.get(Calendar.DAY_OF_MONTH);
    }

    static boolean commuteReady(Context c) {
        SharedPreferences sp = p(c);
        return sp.getBoolean("cEnabled", false) && !sp.getString("oLat", "").isEmpty();
    }

    /** Next evening "leave the office?" check: every 15 min from the start time to the end time, once a day. */
    static long nextCommute(Context c, long now) {
        if (!commuteReady(c)) return Long.MAX_VALUE;
        SharedPreferences sp = p(c);
        int start = sp.getInt("cStart", 18 * 60), end = sp.getInt("cEnd", 21 * 60);
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        boolean doneToday = ymd(cal).equals(sp.getString("cDone", ""));
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long mid = cal.getTimeInMillis();
        if (!doneToday) {
            for (int m = start; m <= end; m += 15) {
                long t = mid + m * 60000L;
                if (t > now + 1000) return t;
            }
        }
        return mid + 86400000L + start * 60000L;
    }

    /** Start of the current 15-minute evening-check slot, or -1 when outside the window / already done today. */
    static long commuteSlot(Context c, long now) {
        if (!commuteReady(c)) return -1;
        SharedPreferences sp = p(c);
        int start = sp.getInt("cStart", 18 * 60), end = sp.getInt("cEnd", 21 * 60);
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        if (ymd(cal).equals(sp.getString("cDone", ""))) return -1;
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (mins < start - 1 || mins > end + 1) return -1;
        int slot = start + Math.max(0, ((mins + 1 - start) / 15)) * 15;
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() + slot * 60000L;
    }

    static void commuteDone(Context c) {
        Calendar cal = Calendar.getInstance(IST);
        p(c).edit().putString("cDone", ymd(cal)).apply();
    }

    /** true = at the office, false = away, null = location unknown. Blocking (up to ~6 s). */
    static Boolean atOffice(Context c) {
        try {
            SharedPreferences sp = p(c);
            double lat = Double.parseDouble(sp.getString("oLat", ""));
            double lng = Double.parseDouble(sp.getString("oLng", ""));
            Location l = Loc.current(c, 6000);
            if (l == null) return null;
            float r = Math.max(250f, l.getAccuracy());
            return Loc.meters(lat, lng, l.getLatitude(), l.getLongitude()) <= r;
        } catch (Exception e) {
            return null;
        }
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
        long cSlot = commuteSlot(c, now);
        boolean cDue = cSlot > 0 && cSlot > p(c).getLong("lastCSlot", 0);

        SharedPreferences.Editor ed = p(c).edit();
        ed.putString("rung", r.toString());
        if (slotDue) ed.putLong("lastSlot", slot);
        ed.apply();

        if (ids.length() > 0) {
            p(c).edit().putInt("retryN", 0).putLong("retryAt", 0).apply();
            ring(c, "task", ids, titles);
        } else if (retryDue) {
            String mode = p(c).getString("retryMode", "task");
            if (mode.equals("commute")) {
                Boolean at = atOffice(c);
                if (at != null && !at) {
                    p(c).edit().putLong("retryAt", 0).putInt("retryN", 0).apply(); // already left: no more calls
                    commuteDone(c);
                } else {
                    p(c).edit().putLong("retryAt", 0).apply();
                    ring(c, "commute", new JSONArray(p(c).getString("retryIds", "[]")), titlesFor(c, new JSONArray(p(c).getString("retryIds", "[]"))));
                }
            } else {
                JSONArray rids = pendingOnly(c, p(c).getString("retryIds", "[]"), mode);
                if (rids.length() == 0) {
                    p(c).edit().putLong("retryAt", 0).putInt("retryN", 0).apply();
                } else {
                    p(c).edit().putLong("retryAt", 0).apply(); // keeps retryN so the next gap is 5 min
                    ring(c, mode, rids, titlesFor(c, rids));
                }
            }
        } else {
            boolean hourlyReady = slotDue && hourlyList(c, now).length() > 0;
            boolean cReady = false;
            if (cDue) {
                p(c).edit().putLong("lastCSlot", cSlot).apply();
                Boolean at = atOffice(c);
                cReady = at == null || at; // away = they have left, so no call
            }
            if (cReady) {
                JSONArray hl = hourlyReady ? hourlyList(c, now) : new JSONArray();
                p(c).edit().putInt("retryN", 0).putLong("retryAt", 0).apply();
                ring(c, "commute", hl, titlesFor(c, hl));
            } else if (hourlyReady) {
                p(c).edit().putInt("retryN", 0).putLong("retryAt", 0).apply();
                JSONArray all = hourlyList(c, now);
                ring(c, "hourly", all, titlesFor(c, all));
            }
        }
        schedule(c);
    }

    /** Hourly roll-call covers only tasks with no time or already due; tasks set for a later time ring at their own time. */
    static JSONArray hourlyList(Context c, long now) {
        JSONArray out = new JSONArray();
        JSONArray ts = tasks(c);
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            long due = parse(t.optString("due_at", ""));
            if (due < 0 || due <= now + 30000L) out.put(t.optString("id"));
        }
        return out;
    }

    static JSONArray pendingOnly(Context c, String idsJson, String mode) {
        JSONArray out = new JSONArray();
        try {
            JSONArray want = new JSONArray(idsJson);
            JSONArray ts = tasks(c);
            for (int i = 0; i < want.length(); i++) {
                for (int j = 0; j < ts.length(); j++) {
                    JSONObject tj = ts.getJSONObject(j);
                    if (tj.optString("id").equals(want.getString(i))) {
                        long due = parse(tj.optString("due_at", ""));
                        if (due < 0 || due <= System.currentTimeMillis() + 60000L) out.put(want.getString(i)); // rescheduled for later: no retry
                        break;
                    }
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
        int max = mode.equals("hourly") ? 5 : (mode.equals("commute") ? 3 : 7);
        if (n > max) {
            if (mode.equals("commute")) commuteDone(c);
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
            h.setConnectTimeout(3000);
            h.setReadTimeout(3000);
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
