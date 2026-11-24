package com.santhosh.reminder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Current phone location (no Google Play services needed). Blocking: call from a background thread. */
public class Loc {
    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean background(Context c) {
        return Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static Location current(Context c, long timeoutMs) {
        if (!granted(c)) return null;
        final LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        final AtomicReference<Location> got = new AtomicReference<Location>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        String[] providers = new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER};
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (final String prov : providers) {
            try {
                if (!lm.isProviderEnabled(prov)) continue;
                long left = deadline - System.currentTimeMillis();
                if (left < 1000) break;
                if (Build.VERSION.SDK_INT >= 30) {
                    final CountDownLatch l = new CountDownLatch(1);
                    lm.getCurrentLocation(prov, null, c.getMainExecutor(), new java.util.function.Consumer<Location>() {
                        @Override public void accept(Location loc) { if (loc != null) got.set(loc); l.countDown(); }
                    });
                    l.await(Math.min(left, prov.equals(LocationManager.NETWORK_PROVIDER) ? 3000 : left), TimeUnit.MILLISECONDS);
                } else {
                    final CountDownLatch l = new CountDownLatch(1);
                    lm.requestSingleUpdate(prov, new LocationListener() {
                        @Override public void onLocationChanged(Location loc) { got.set(loc); l.countDown(); }
                        @Override public void onStatusChanged(String p, int s, Bundle e) {}
                        @Override public void onProviderEnabled(String p) {}
                        @Override public void onProviderDisabled(String p) {}
                    }, Looper.getMainLooper());
                    l.await(Math.min(left, prov.equals(LocationManager.NETWORK_PROVIDER) ? 3000 : left), TimeUnit.MILLISECONDS);
                }
                if (got.get() != null) return got.get();
            } catch (Exception ignored) {}
        }
        // fall back to a recent last-known fix (up to 15 minutes old)
        Location best = null;
        try {
            for (String prov : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && System.currentTimeMillis() - l.getTime() < 15 * 60000L && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
        } catch (Exception ignored) {}
        return best;
    }

    static float meters(double lat1, double lng1, double lat2, double lng2) {
        float[] r = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, r);
        return r[0];
    }
}
