package com.santhosh.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;

/** Foreground service that rings like an incoming call and shows the full-screen call UI. */
public class RingService extends Service {
    static final String CH = "call";
    static final int NID = 42;
    static final long RING_MS = 45000;

    MediaPlayer mp;
    Vibrator vib;
    PowerManager.WakeLock wl;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean ringing = false;
    String mode = "task", idsJson = "[]";

    final Runnable timeout = new Runnable() {
        @Override public void run() { endRing(true); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        String act = in == null ? null : in.getAction();
        if ("DECLINE".equals(act)) { endRing(true); return START_NOT_STICKY; }
        if ("STOP".equals(act)) { endRing(false); return START_NOT_STICKY; }
        if (in == null) { stopSelf(); return START_NOT_STICKY; }
        if (ringing) return START_NOT_STICKY;

        mode = in.getStringExtra("mode") == null ? "task" : in.getStringExtra("mode");
        idsJson = in.getStringExtra("ids") == null ? "[]" : in.getStringExtra("ids");
        String titlesJson = in.getStringExtra("titles") == null ? "[]" : in.getStringExtra("titles");
        String label = buildLabel(mode, titlesJson);
        Scheduler.p(this).edit().putString("lastMode", mode).putString("lastIds", idsJson).apply();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Reminder call", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }

        Intent full = new Intent(this, IncomingCallActivity.class);
        full.putExtra("mode", mode).putExtra("ids", idsJson).putExtra("label", label);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent ans = new Intent(this, MainActivity.class);
        ans.putExtra("answer", true).putExtra("mode", mode).putExtra("ids", idsJson);
        ans.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent ansPi = PendingIntent.getActivity(this, 2, ans, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dec = new Intent(this, RingService.class).setAction("DECLINE");
        PendingIntent decPi = PendingIntent.getService(this, 3, dec, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Reminder AI Call")
                .setContentText(label)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decPi)
                .addAction(android.R.drawable.sym_action_call, "Answer", ansPi);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification n = b.build();

        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, n);
        } catch (Exception e) {
            Scheduler.p(this).edit().putString("lastError", String.valueOf(e)).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        ringing = true;

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "rem:ring");
            wl.acquire(RING_MS + 10000);
        } catch (Exception ignored) {}

        startSound();
        try { startActivity(full); } catch (Exception ignored) {} // works when "display over other apps" is allowed
        h.postDelayed(timeout, RING_MS);
        return START_NOT_STICKY;
    }

    String buildLabel(String mode, String titlesJson) {
        try {
            JSONArray t = new JSONArray(titlesJson);
            if (mode.equals("hourly")) return t.length() + " tasks pending";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length() && i < 3; i++) { if (i > 0) sb.append(" • "); sb.append(t.getString(i)); }
            if (t.length() > 3) sb.append(" …");
            return sb.length() == 0 ? "Reminder" : sb.toString();
        } catch (Exception e) { return "Reminder"; }
    }

    void startSound() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max * 0.8) am.setStreamVolume(AudioManager.STREAM_ALARM, (int) (max * 0.85), 0);
        } catch (Exception ignored) {}
        Uri[] tries = new Uri[]{
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)};
        for (Uri u : tries) {
            if (u == null) continue;
            try {
                MediaPlayer m = new MediaPlayer();
                m.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                m.setDataSource(this, u);
                m.setLooping(true);
                m.prepare();
                m.start();
                mp = m;
                break;
            } catch (Exception e) {
                try { mp = null; } catch (Exception ignored) {}
            }
        }
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pat = new long[]{0, 800, 600};
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pat, 0));
                else vib.vibrate(pat, 0);
            }
        } catch (Exception ignored) {}
    }

    void stopSound() {
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) {}
        mp = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) {}
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
    }

    /** missed=true: declined or nobody answered, so ring again later. */
    void endRing(boolean missed) {
        h.removeCallbacks(timeout);
        boolean was = ringing;
        ringing = false;
        stopSound();
        IncomingCallActivity.closeIfOpen();
        if (was && missed) Scheduler.scheduleRetry(this, mode, idsJson);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(timeout);
        stopSound();
        super.onDestroy();
    }

    static void stop(Context c) {
        try { c.startService(new Intent(c, RingService.class).setAction("STOP")); } catch (Exception ignored) {}
    }
    static void decline(Context c) {
        try { c.startService(new Intent(c, RingService.class).setAction("DECLINE")); } catch (Exception ignored) {}
    }
}
