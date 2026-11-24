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
                return o.toString();
            } catch (Exception e) { return "{}"; }
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
