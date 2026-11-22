package com.santhosh.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { Scheduler.onFire(app); } catch (Throwable t) { try { Scheduler.schedule(app); } catch (Throwable ignored) {} }
                pr.finish();
            }
        }).start();
    }
}
