package com.santhosh.reminder;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import android.content.SharedPreferences;
import android.location.Location;

/** Hosts the existing web app (reminder UI + Gemini voice call) and bridges it to native alarms. */
public class MainActivity extends Activity {
    static final String BASE = "https://santhoshofficial91-png.github.io/todo-call-reminder/";
    WebView wv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) r.grant(r.getResources());
                        else r.deny();
                    }
                });
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        handle(getIntent());
        Scheduler.schedule(this);
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handle(in);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1 && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    void handle(Intent in) {
        String url = BASE + "?native=1&t=" + System.currentTimeMillis(); // always fetch the newest page
        if (in != null && in.getBooleanExtra("answer", false)) {
            RingService.stop(this);
            Scheduler.clearRetry(this);
            String mode = in.getStringExtra("mode");
            if ("commute".equals(mode)) { Scheduler.commuteDone(this); Scheduler.schedule(this); }
            String ids = in.getStringExtra("ids");
            try {
                url += "&answer=1&mode=" + Uri.encode(mode == null ? "task" : mode)
                        + "&ids=" + Uri.encode(ids == null ? "[]" : ids) + "&t=" + System.currentTimeMillis();
            } catch (Exception ignored) {}
            in.removeExtra("answer");
        } else if (wv.getUrl() != null) {
            return; // already showing the app
        }
        wv.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
    }

    boolean hasBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    class Bridge {
        @JavascriptInterface
        public void syncTasks(String json) {
            Scheduler.saveTasks(MainActivity.this, json);
            Scheduler.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void setToken(String t) {
            Scheduler.p(MainActivity.this).edit().putString("token", t == null ? "" : t).apply();
        }

        @JavascriptInterface
        public void testRing() {
            JSONArray ids = new JSONArray().put("test");
            JSONArray titles = new JSONArray().put("Test reminder");
            Scheduler.ring(MainActivity.this, "task", ids, titles);
        }

        @JavascriptInterface
        public String status() {
            JSONObject o = new JSONObject();
            try {
                o.put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                o.put("notif", nm.areNotificationsEnabled());
                o.put("fullscreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
                o.put("overlay", Settings.canDrawOverlays(MainActivity.this));
                o.put("battery", hasBattery());
                o.put("nextAt", Scheduler.p(MainActivity.this).getLong("nextAt", 0));
                o.put("loc", Loc.granted(MainActivity.this));
                o.put("locBg", Loc.background(MainActivity.this));
                o.put("office", !Scheduler.p(MainActivity.this).getString("oLat", "").isEmpty());
                o.put("home", !Scheduler.p(MainActivity.this).getString("hLat", "").isEmpty());
                o.put("rEnabled", Scheduler.p(MainActivity.this).getBoolean("rEnabled", false));
                o.put("cEnabled", Scheduler.p(MainActivity.this).getBoolean("cEnabled", false));
                o.put("cStart", Scheduler.p(MainActivity.this).getInt("cStart", 18 * 60));
                o.put("cEnd", Scheduler.p(MainActivity.this).getInt("cEnd", 21 * 60));
                o.put("lastError", Scheduler.p(MainActivity.this).getString("lastError", ""));
            } catch (Exception ignored) {}
            return o.toString();
        }

        /** Opens the system screen for the first missing permission. */
        @JavascriptInterface
        public void fixNext() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        String pkg = getPackageName();
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                        } else if (!nm.areNotificationsEnabled()) {
                            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                            startActivity(i);
                        } else if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg)));
                        } else if (!Settings.canDrawOverlays(MainActivity.this)) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)));
                        } else if (!hasBattery()) {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                        } else if (!Loc.granted(MainActivity.this)) {
                            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
                        } else if (!Loc.background(MainActivity.this)) {
                            if (Build.VERSION.SDK_INT == 29) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 4);
                            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); // Permissions > Location > Allow all the time
                        } else {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                        }
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                    }
                }
            });
        }

        /** Saves the current GPS position as "office" or "home". Result comes back through window.onLocSaved(kind, message). */
        @JavascriptInterface
        public void saveLocation(final String kind) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String msg;
                    try {
                        Location l = Loc.current(MainActivity.this, 15000);
                        if (l == null) {
                            msg = Loc.granted(MainActivity.this) ? "Could not get a GPS fix. Go near a window and try again." : "Location permission is not allowed yet. Tap Fix permissions.";
                        } else {
                            String k = "home".equals(kind) ? "h" : "o";
                            Scheduler.p(MainActivity.this).edit().putString(k + "Lat", String.valueOf(l.getLatitude())).putString(k + "Lng", String.valueOf(l.getLongitude())).apply();
                            Scheduler.schedule(MainActivity.this);
                            msg = "Saved (accuracy " + Math.round(l.getAccuracy()) + " m)";
                        }
                    } catch (Exception e) { msg = "Error: " + e; }
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            wv.evaluateJavascript("window.onLocSaved && window.onLocSaved(" + JSONObject.quote(kind) + "," + JSONObject.quote(m) + ")", null);
                        }
                    });
                }
            }).start();
        }

        /** Saved office/home coordinates as JSON, used to tell the server for traffic lookups. */
        @JavascriptInterface
        public String places() {
            try {
                SharedPreferences sp = Scheduler.p(MainActivity.this);
                JSONObject o = new JSONObject();
                if (!sp.getString("oLat", "").isEmpty()) o.put("office", new JSONObject().put("lat", Double.parseDouble(sp.getString("oLat", "0"))).put("lng", Double.parseDouble(sp.getString("oLng", "0"))));
                if (!sp.getString("hLat", "").isEmpty()) o.put("home", new JSONObject().put("lat", Double.parseDouble(sp.getString("hLat", "0"))).put("lng", Double.parseDouble(sp.getString("hLng", "0"))));
                o.put("routes", new org.json.JSONArray(sp.getString("routes", "[]")));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Appends the current GPS position as a named point on a named route (max 3 routes, 5 points). Callback window.onRouteSaved(message). */
        @JavascriptInterface
        public void addRoutePoint(final String route, final String point) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String msg;
                    try {
                        Location l = Loc.current(MainActivity.this, 15000);
                        if (l == null) {
                            msg = Loc.granted(MainActivity.this) ? "Could not get a GPS fix. Go near an open area and try again." : "Location permission is not allowed yet.";
                        } else {
                            SharedPreferences sp = Scheduler.p(MainActivity.this);
                            org.json.JSONArray rs = new org.json.JSONArray(sp.getString("routes", "[]"));
                            JSONObject target = null;
                            for (int i = 0; i < rs.length(); i++) if (route.equals(rs.getJSONObject(i).optString("name"))) target = rs.getJSONObject(i);
                            if (target == null) {
                                if (rs.length() >= 3) { target = null; }
                                else { target = new JSONObject().put("name", route).put("points", new org.json.JSONArray()); rs.put(target); }
                            }
                            if (target == null) msg = "Only 3 routes allowed. Delete one first.";
                            else {
                                org.json.JSONArray pts = target.getJSONArray("points");
                                if (pts.length() >= 5) msg = "Max 5 points per route.";
                                else {
                                    pts.put(new JSONObject().put("name", point).put("lat", l.getLatitude()).put("lng", l.getLongitude()));
                                    sp.edit().putString("routes", rs.toString()).apply();
                                    msg = "Saved '" + point + "' on " + route + " (accuracy " + Math.round(l.getAccuracy()) + " m)";
                                }
                            }
                        }
                    } catch (Exception e) { msg = "Error: " + e; }
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { wv.evaluateJavascript("window.onRouteSaved && window.onRouteSaved(" + JSONObject.quote(m) + ")", null); }
                    });
                }
            }).start();
        }


        /** Saves office/home from given coordinates (pasted by the user). Returns a short message. */
        @JavascriptInterface
        public String setPlaceAt(String kind, double lat, double lng) {
            if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180)) return "Invalid coordinates";
            String k = "home".equals(kind) ? "h" : "o";
            Scheduler.p(MainActivity.this).edit().putString(k + "Lat", String.valueOf(lat)).putString(k + "Lng", String.valueOf(lng)).apply();
            Scheduler.schedule(MainActivity.this);
            return "Saved";
        }

        /** Appends given coordinates as a named point on a named route (max 3 routes, 5 points). Returns a short message. */
        @JavascriptInterface
        public String addRoutePointAt(String route, String point, double lat, double lng) {
            try {
                if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180)) return "Invalid coordinates";
                SharedPreferences sp = Scheduler.p(MainActivity.this);
                org.json.JSONArray rs = new org.json.JSONArray(sp.getString("routes", "[]"));
                JSONObject target = null;
                for (int i = 0; i < rs.length(); i++) if (route.equals(rs.getJSONObject(i).optString("name"))) target = rs.getJSONObject(i);
                if (target == null) {
                    if (rs.length() >= 3) return "Only 3 routes allowed. Delete one first.";
                    target = new JSONObject().put("name", route).put("points", new org.json.JSONArray());
                    rs.put(target);
                }
                org.json.JSONArray pts = target.getJSONArray("points");
                if (pts.length() >= 5) return "Max 5 points per route.";
                pts.put(new JSONObject().put("name", point).put("lat", lat).put("lng", lng));
                sp.edit().putString("routes", rs.toString()).apply();
                return "Saved '" + point + "' on " + route;
            } catch (Exception e) { return "Error: " + e; }
        }

        /** After a reinstall: fills only what is empty on the phone from the server copy. Route points are in travel order (office to home). */
        @JavascriptInterface
        public String restorePlaces(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences sp = Scheduler.p(MainActivity.this);
                SharedPreferences.Editor ed = sp.edit();
                int n = 0;
                JSONObject of = o.optJSONObject("office");
                if (of != null && sp.getString("oLat", "").isEmpty()) { ed.putString("oLat", String.valueOf(of.getDouble("lat"))).putString("oLng", String.valueOf(of.getDouble("lng"))); n++; }
                JSONObject hm = o.optJSONObject("home");
                if (hm != null && sp.getString("hLat", "").isEmpty()) { ed.putString("hLat", String.valueOf(hm.getDouble("lat"))).putString("hLng", String.valueOf(hm.getDouble("lng"))); n++; }
                org.json.JSONArray rs = o.optJSONArray("routes");
                if (rs != null && rs.length() > 0 && new org.json.JSONArray(sp.getString("routes", "[]")).length() == 0) { ed.putString("routes", rs.toString()); n++; }
                ed.apply();
                if (n > 0) Scheduler.schedule(MainActivity.this);
                return String.valueOf(n);
            } catch (Exception e) { return "0"; }
        }

        @JavascriptInterface
        public void deleteRoute(String route) {
            try {
                SharedPreferences sp = Scheduler.p(MainActivity.this);
                org.json.JSONArray rs = new org.json.JSONArray(sp.getString("routes", "[]"));
                org.json.JSONArray out = new org.json.JSONArray();
                for (int i = 0; i < rs.length(); i++) if (!route.equals(rs.getJSONObject(i).optString("name"))) out.put(rs.getJSONObject(i));
                sp.edit().putString("routes", out.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setRain(boolean enabled) {
            Scheduler.p(MainActivity.this).edit().putBoolean("rEnabled", enabled).putLong("lastRSlot", 0).apply();
            Scheduler.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void testMaps() {
            Scheduler.ring(MainActivity.this, "maps", new JSONArray(), new JSONArray());
        }

        @JavascriptInterface
        public void testRain() {
            Scheduler.ring(MainActivity.this, "rain", new JSONArray(), new JSONArray());
        }

        @JavascriptInterface
        public void setCommute(boolean enabled, int startMin, int endMin) {
            if (endMin < startMin) endMin = startMin;
            Scheduler.p(MainActivity.this).edit().putBoolean("cEnabled", enabled).putInt("cStart", startMin).putInt("cEnd", endMin)
                    .putString("cDone", "").putLong("lastCSlot", 0).apply();
            Scheduler.schedule(MainActivity.this);
        }

        /** Rings the evening "leave the office?" call right now (ignores location). */
        @JavascriptInterface
        public void testCommute() {
            Scheduler.ring(MainActivity.this, "commute", new JSONArray(), new JSONArray());
        }

        @JavascriptInterface
        public void callStarted() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(false);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setSpeaker(boolean on) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setSpeakerphoneOn(on);
            } catch (Exception ignored) {}
        }

        /** unresolved=true when the call was cut early (treated like a decline: ring again). */
        @JavascriptInterface
        public void callEnded(boolean unresolved) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception ignored) {}
            if (unresolved) {
                SharedPreferencesHolder.retryLast(MainActivity.this);
            }
        }
    }

    /** Re-ring for the most recent reminder if the call was cut right away. */
    static class SharedPreferencesHolder {
        static void retryLast(Context c) {
            String mode = Scheduler.p(c).getString("lastMode", "task");
            String ids = Scheduler.p(c).getString("lastIds", "[]");
            Scheduler.scheduleRetry(c, mode, ids);
        }
    }
}
