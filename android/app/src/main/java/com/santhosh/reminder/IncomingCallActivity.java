package com.santhosh.reminder;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen "incoming call" screen shown over the lock screen. */
public class IncomingCallActivity extends Activity {
    static IncomingCallActivity current;
    String mode = "task", ids = "[]";

    static void closeIfOpen() {
        if (current != null) { try { current.finish(); } catch (Exception ignored) {} current = null; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && km != null) km.requestDismissKeyguard(this, null);
        } catch (Exception ignored) {}

        Intent in = getIntent();
        mode = in.getStringExtra("mode") == null ? "task" : in.getStringExtra("mode");
        ids = in.getStringExtra("ids") == null ? "[]" : in.getStringExtra("ids");
        String label = in.getStringExtra("label") == null ? "Reminder" : in.getStringExtra("label");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0f172a"));
        int pad = dp(28);
        root.setPadding(pad, dp(72), pad, dp(56));

        TextView avatar = new TextView(this);
        avatar.setText("🤖");
        avatar.setTextSize(54);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable();
        ag.setShape(GradientDrawable.OVAL);
        ag.setColor(Color.parseColor("#16a34a"));
        avatar.setBackground(ag);
        root.addView(avatar, new LinearLayout.LayoutParams(dp(130), dp(130)));

        TextView who = text("Reminder AI Call", 28, Color.WHITE, true);
        who.setPadding(0, dp(24), 0, dp(6));
        root.addView(who);
        root.addView(text("Incoming reminder call…", 16, Color.parseColor("#cbd5e1"), false));
        TextView lbl = text(label, 22, Color.WHITE, true);
        lbl.setPadding(0, dp(28), 0, 0);
        root.addView(lbl);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(button("✕", "Decline", "#dc2626", new View.OnClickListener() {
            @Override public void onClick(View v) { RingService.decline(IncomingCallActivity.this); finish(); }
        }));
        row.addView(button("📞", "Answer", "#16a34a", new View.OnClickListener() {
            @Override public void onClick(View v) { answer(); }
        }));
        root.addView(row);
        setContentView(root);
    }

    void answer() {
        Intent a = new Intent(this, MainActivity.class);
        a.putExtra("answer", true).putExtra("mode", mode).putExtra("ids", ids);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(a);
        finish();
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout button(String icon, String label, String color, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(32), 0, dp(32), 0);
        TextView c = new TextView(this);
        c.setText(icon);
        c.setTextSize(32);
        c.setTextColor(Color.WHITE);
        c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(color));
        c.setBackground(g);
        c.setOnClickListener(l);
        col.addView(c, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView t = text(label, 14, Color.parseColor("#cbd5e1"), false);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        return col;
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* ignore: use the buttons */ }
}
