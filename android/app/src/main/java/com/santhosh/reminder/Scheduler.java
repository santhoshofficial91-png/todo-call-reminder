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

    /** Start of the next IST hour (10..18) strictly after 'after'. */
    static long nextHourly(long after) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(after);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 30; i++) {
            cal.add(Calendar.HOUR_OF_DAY, 1);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            if (h >= 10 && h <= 18) return cal.getTimeInMillis();
        }
        return after + 3600000L;
    }

    /** Start of the current IST hour if it is an hourly slot (10..18), else -1. */
    static long currentSlot(long now) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return (h >= 10 && h <= 18) ? cal.getTimeInMillis() : -1;
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
        next = Math.min(next, nextRain(c, now));
        next = Math.min(next, nextMaps(c, now));

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

    static final String MAPS_HEALTH_API = "https://nicynemyjdiukkbltjwq.supabase.co/functions/v1/maps-health";

    /** Daily 08:00 IST check that the Google traffic key still works (only when the evening call is on). */
    static long nextMaps(Context c, long now) {
        if (!p(c).getBoolean("cEnabled", false)) return Long.MAX_VALUE;
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long t = cal.getTimeInMillis();
        return t > now + 1000 ? t : t + 86400000L;
    }

    /** Returns the failure code (e.g. PERMISSION_DENIED) if the traffic key is broken, "" if fine or unknown. */
    static String mapsBroken(Context c) {
        HttpURLConnection h = null;
        try {
            String token = p(c).getString("token", "");
            if (token.isEmpty()) return "";
            h = (HttpURLConnection) new URL(MAPS_HEALTH_API).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(8000);
            h.setRequestMethod("POST");
            h.setRequestProperty("content-type", "application/json");
            h.setRequestProperty("x-app-token", token);
            h.setDoOutput(true);
            try (OutputStream os = h.getOutputStream()) { os.write("{}".getBytes("UTF-8")); }
            if (h.getResponseCode() != 200) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            java.io.InputStream in = h.getInputStream();
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            if (j.optBoolean("ok", false)) return "";
            String code = j.optString("code", "UNKNOWN");
            return code.equals("NETWORK") ? "" : code; // a network blip is not worth a call
        } catch (Exception e) {
            return "";
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static final String WEATHER_API = "https://nicynemyjdiukkbltjwq.supabase.co/functions/v1/weather-api";

    /** Rain checks every 30 min between 05:00 and 21:00 IST when the rain alert is on. */
    static long nextRain(Context c, long now) {
        if (!p(c).getBoolean("rEnabled", false)) return Long.MAX_VALUE;
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long mid = cal.getTimeInMillis();
        for (int m = 5 * 60; m <= 21 * 60; m += 30) {
            long t = mid + m * 60000L;
            if (t > now + 1000) return t;
        }
        return mid + 86400000L + 5 * 60 * 60000L;
    }

    static long rainSlot(Context c, long now) {
        if (!p(c).getBoolean("rEnabled", false)) return -1;
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now + 60000L);
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (mins < 5 * 60 || mins > 21 * 60) return -1;
        int slot = mins / 30 * 30;
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() + slot * 60000L;
    }

    /** True when rain is likely (60%+) in the current or next forecast hour at the phone's place or a saved place. */
    static boolean rainSoon(Context c) {
        HttpURLConnection h = null;
        try {
            String token = p(c).getString("token", "");
            if (token.isEmpty()) return false;
            Calendar nc = Calendar.getInstance(IST);
            int nowMin = nc.get(Calendar.HOUR_OF_DAY) * 60 + nc.get(Calendar.MINUTE);
            JSONObject body = new JSONObject().put("hours", 4);
            Location l = Loc.current(c, 6000);
            if (l != null) body.put("here", new JSONObject().put("lat", l.getLatitude()).put("lng", l.getLongitude()));
            h = (HttpURLConnection) new URL(WEATHER_API).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(6000);
            h.setRequestMethod("POST");
            h.setRequestProperty("content-type", "application/json");
            h.setRequestProperty("x-app-token", token);
            h.setDoOutput(true);
            try (OutputStream os = h.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
            if (h.getResponseCode() != 200) return false;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            java.io.InputStream in = h.getInputStream();
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            JSONArray places = j.optJSONArray("places");
            if (places == null) return false;
            for (int i = 0; i < places.length(); i++) {
                JSONArray hr = places.getJSONObject(i).optJSONArray("hourly");
                if (hr == null) continue;
                for (int k = 0; k < hr.length(); k++) {
                    JSONObject e = hr.getJSONObject(k);
                    String tm = e.optString("time", "");
                    if (tm.length() < 5) continue;
                    int tmin = Integer.parseInt(tm.substring(0, 2)) * 60 + Integer.parseInt(tm.substring(3, 5));
                    // rain starting within the next 90 min (early warning), or already in the current hour
                    if (tmin <= nowMin + 90 && (e.optInt("chance_percent", 0) >= 50 || e.optDouble("mm", 0) >= 0.5)) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static boolean nextMapsDue(Context c, long now) {
        if (!p(c).getBoolean("cEnabled", false)) return false;
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now + 60000L);
        return cal.get(Calendar.HOUR_OF_DAY) >= 8 && !ymd(cal).equals(p(c).getString("mapsDay", ""));
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
                    JSONArray cids = new JSONArray();
                    try { cids = new JSONArray(p(c).getString("retryIds", "[]")); } catch (Exception ignored) {}
                    ring(c, "commute", cids, titlesFor(c, cids));
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
            } else if (nextMapsDue(c, now)) {
                String bad = mapsBroken(c);
                String last = p(c).getString("mapsBadCode", "");
                String today = ymd(Calendar.getInstance(IST));
                if (!bad.isEmpty() && !(last.equals(bad) && today.equals(p(c).getString("mapsBadDay", "")))) {
                    p(c).edit().putString("mapsBadCode", bad).putString("mapsBadDay", today).apply();
                    ring(c, "maps", new JSONArray(), new JSONArray());
                }
                p(c).edit().putString("mapsDay", today).apply();
            } else {
                long rs = rainSlot(c, now);
                if (rs > 0 && rs > p(c).getLong("lastRSlot", 0)) {
                    p(c).edit().putLong("lastRSlot", rs).apply();
                    if (now - p(c).getLong("lastRain", 0) > 2 * 3600000L && rainSoon(c)) {
                        p(c).edit().putLong("lastRain", now).apply();
                        ring(c, "rain", new JSONArray(), new JSONArray());
                    }
                }
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
        if (mode.equals("rain") || mode.equals("maps")) { schedule(c); return; } // rain alerts are not retried
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
