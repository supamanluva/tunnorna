package se.hemma.tunnor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

/** Helskärmsvy som visas över låsskärmen när alarmet ringer. */
public class AlarmActivity extends Activity {

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        IntentFilter f = new IntentFilter(AlarmService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(done, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(done, f);

        render(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        render(intent);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void render(Intent in) {
        String d = in.getStringExtra(Scheduler.EXTRA_DATE);
        String what = in.getStringExtra(AlarmService.EXTRA_WHAT);
        LocalDate date = d != null ? LocalDate.parse(d) : LocalDate.now().plusDays(1);
        if (what == null) what = "Sophämtning";

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(Color.parseColor("#1B5E20"));
        int pad = dp(24);
        col.setPadding(pad, dp(64), pad, dp(40));
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, dp(64) + insets.getSystemWindowInsetTop(), pad,
                    dp(40) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Ställ ut tunnorna!");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView when = new TextView(this);
        String day = Scheduler.pretty(date).toLowerCase(Scheduler.SV);
        when.setText((date.equals(LocalDate.now().plusDays(1)) ? "Imorgon, " : "") + day);
        when.setTextColor(Color.parseColor("#C8E6C9"));
        when.setTextSize(20);
        when.setGravity(Gravity.CENTER);
        when.setPadding(0, dp(8), 0, dp(32));
        col.addView(when);

        // En stor rad per tunna, matavfall sist eftersom den alltid ska ut.
        String[] bins = what.split("\\s*,\\s*");
        java.util.Arrays.sort(bins, (a, x) -> Boolean.compare(
                a.equalsIgnoreCase("Matavfall"), x.equalsIgnoreCase("Matavfall")));
        for (String bin : bins) {
            TextView t = new TextView(this);
            t.setText(bin);
            t.setTextSize(28);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Color.parseColor("#1B5E20"));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(18), dp(16), dp(18));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(16));
            t.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(14);
            col.addView(t, lp);
        }

        TextView note = new TextView(this);
        note.setText("Ska stå ute senast kl. 06.00");
        note.setTextColor(Color.parseColor("#C8E6C9"));
        note.setTextSize(16);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(8), 0, 0);
        col.addView(note);

        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(-1, 0, 1f);
        col.addView(new TextView(this), spacer);

        Button stop = new Button(this);
        stop.setText("Stäng av");
        stop.setTextSize(22);
        stop.setOnClickListener(v -> {
            startService(new Intent(this, AlarmService.class).setAction(AlarmService.ACTION_STOP));
            finish();
        });
        col.addView(stop, new LinearLayout.LayoutParams(-1, dp(72)));

        Button snooze = new Button(this);
        snooze.setText("Snooza " + AlarmService.SNOOZE_MIN + " min");
        snooze.setTextSize(18);
        snooze.setOnClickListener(v -> {
            startService(new Intent(this, AlarmService.class).setAction(AlarmService.ACTION_SNOOZE));
            finish();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(60));
        slp.topMargin = dp(12);
        col.addView(snooze, slp);

        setContentView(col);
    }

    @Override
    public void onBackPressed() {
        // Tillbaka-knappen ska inte råka tysta alarmet utan att man valt.
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(done); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
