package se.hemma.tunnor;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class MainActivity extends Activity {

    private Store store;
    private AutoCompleteTextView addressField;
    private TextView status, list, warnings;
    private Button timeButton, saveButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        Scheduler.ensureChannel(this);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        addressField.setText(store.address());
        loadAddressSuggestions();
        Scheduler.scheduleDailyRefresh(this);
        Scheduler.scheduleNext(this);
        render();
        if (!store.address().isEmpty()) refreshInBackground(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ---------- UI ----------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#212121"));
        return t;
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        TextView h = text(14, false);
        h.setText("Sök din adress i Vännäs kommun (t.ex. \"Vännäsvägen 1\") och spara.");
        col.addView(h);

        addressField = new AutoCompleteTextView(this);
        addressField.setHint("Gatuadress och nummer");
        addressField.setSingleLine(true);
        addressField.setThreshold(2);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        addressField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        addressField.setOnEditorActionListener((v, id, e) -> { saveAddress(); return true; });
        addressField.setOnItemClickListener((parent, view, pos, id) -> saveAddress());
        col.addView(addressField, new LinearLayout.LayoutParams(-1, -2));

        saveButton = new Button(this);
        saveButton.setText("Spara adress");
        saveButton.setOnClickListener(v -> saveAddress());
        col.addView(saveButton);

        status = text(18, true);
        status.setPadding(0, dp(20), 0, dp(4));
        col.addView(status);

        list = text(15, false);
        list.setLineSpacing(dp(4), 1f);
        col.addView(list);

        timeButton = new Button(this);
        timeButton.setOnClickListener(v -> pickTime());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        col.addView(timeButton, lp);

        Button test = new Button(this);
        test.setText("Testa alarmet");
        test.setOnClickListener(v -> {
            List<Store.Pickup> ps = store.pickups();
            Store.Pickup p = ps.isEmpty()
                    ? new Store.Pickup(LocalDate.now().plusDays(1), "Restavfall, Matavfall (exempel)")
                    : ps.get(0);
            try {
                AlarmService.start(this, p); // testet påverkar inte det riktiga alarmet
                // Visa helskärmen direkt (annars visas bara en notis när telefonen är upplåst).
                startActivity(new Intent(this, AlarmActivity.class)
                        .putExtra(Scheduler.EXTRA_DATE, p.date.toString())
                        .putExtra(AlarmService.EXTRA_WHAT, p.what));
            } catch (Exception e) {
                Toast.makeText(this, "Kunde inte starta alarmet: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        col.addView(test);

        warnings = text(14, false);
        warnings.setTextColor(Color.parseColor("#B71C1C"));
        warnings.setPadding(0, dp(16), 0, 0);
        col.addView(warnings);

        Button fullscreen = new Button(this);
        fullscreen.setText("Tillåt helskärmsalarm");
        fullscreen.setTag("fullscreen");
        fullscreen.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        col.addView(fullscreen);

        Button battery = new Button(this);
        battery.setText("Stäng av batterioptimering för appen");
        battery.setOnClickListener(v -> openBatterySettings());
        col.addView(battery);
        battery.setTag("battery");

        TextView foot = text(12, false);
        foot.setTextColor(Color.GRAY);
        foot.setPadding(0, dp(16), 0, 0);
        foot.setText("Alarmet ringer med telefonens alarmljud och alarmvolym. Data från vannas.se, schemat uppdateras automatiskt varje dag. "
                + "Tunnorna ska stå ute från kl. 06.00 på tömningsdagen.");
        col.addView(foot);

        // Stänger bara fönstret; schemalagda alarm ligger kvar i AlarmManager och ringer ändå.
        Button close = new Button(this);
        close.setText("Stäng appen");
        close.setOnClickListener(v -> finishAndRemoveTask());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(20);
        col.addView(close, clp);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(col);
        // Android 15+ ritar under statusfältet; lägg till marginal för systemfälten.
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top;
                bottom = i.bottom;
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        return sv;
    }

    private void render() {
        if (status == null) return;
        timeButton.setText(String.format(Scheduler.SV, "Påminnelsetid kvällen innan: %02d:%02d",
                store.hour(), store.minute()));

        List<Store.Pickup> ps = store.pickups();
        LocalDate today = LocalDate.now();
        if (store.address().isEmpty()) {
            status.setText("Ingen adress sparad ännu.");
            list.setText("");
        } else if (ps.isEmpty()) {
            status.setText(store.address());
            list.setText("Inga hämtningar hittade ännu.");
        } else {
            Store.Pickup first = null;
            for (Store.Pickup p : ps) if (!p.date.isBefore(today)) { first = p; break; }
            status.setText(first == null ? store.address()
                    : store.address() + "\n\nNästa: " + Scheduler.pretty(first.date)
                    + (first.date.equals(today) ? " (idag)" : first.date.equals(today.plusDays(1)) ? " (imorgon)" : "")
                    + "\nTa ut: " + first.what.replace(", ", " + "));
            StringBuilder sb = new StringBuilder("Kommande hämtningar:\n");
            for (Store.Pickup p : ps) {
                if (p.date.isBefore(today)) continue;
                sb.append("• ").append(Scheduler.pretty(p.date))
                        .append(p.date.equals(today) ? " (idag)" : p.date.equals(today.plusDays(1)) ? " (imorgon)" : "")
                        .append("\n    ").append(p.what).append('\n');
            }
            Store.Pickup next = Scheduler.nextReminder(store);
            if (next != null) {
                LocalDateTime r = Scheduler.reminderTime(store, next.date);
                sb.append("\n⏰ Nästa påminnelse: ")
                        .append(r.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'kl.' HH:mm", Scheduler.SV)));
            }
            list.setText(sb.toString().trim());
        }

        StringBuilder w = new StringBuilder();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) w.append("Notiser är avstängda för appen – slå på dem i inställningarna.\n");
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager.class).canScheduleExactAlarms())
            w.append("Exakta alarm är inte tillåtna – påminnelsen kan komma några minuter sent.\n");
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            w.append("Helskärmsalarm är inte tillåtet – tryck på knappen nedan och slå på det.\n");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean optimized = !pm.isIgnoringBatteryOptimizations(getPackageName());
        if (optimized) w.append("Tips: stäng av batterioptimering så att telefonen inte stoppar påminnelsen.");
        warnings.setText(w.toString().trim());
        View fsi = ((LinearLayout) warnings.getParent()).findViewWithTag("fullscreen");
        if (fsi != null) fsi.setVisibility(Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
                ? View.VISIBLE : View.GONE);
        View battery = ((LinearLayout) warnings.getParent()).findViewWithTag("battery");
        if (battery != null) battery.setVisibility(optimized ? View.VISIBLE : View.GONE);
    }

    // ---------- Actions ----------

    private void saveAddress() {
        String a = addressField.getText().toString().trim().replaceAll("\\s+", " ");
        if (a.isEmpty()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(addressField.getWindowToken(), 0);
        addressField.dismissDropDown();
        saveButton.setEnabled(false);
        status.setText("Söker…");
        list.setText("");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                List<Store.Pickup> ps = Api.fetchSchedule(a);
                if (!a.equals(store.address())) {
                    store.clearPickups();
                    store.setAddress(a);
                }
                store.mergePickups(ps);
                Scheduler.scheduleNext(this);
                ok = true;
                msg = ps.isEmpty() ? "Adressen sparad, men inga kommande hämtningar hittades." : "Sparat! Du får en påminnelse kvällen innan.";
            } catch (Api.AddressNotFound e) {
                msg = "Hittade inte \"" + a + "\". Kontrollera stavningen (välj gärna ur listan).";
            } catch (Exception e) {
                msg = "Kunde inte nå kommunen: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                saveButton.setEnabled(true);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                render();
            });
        }).start();
    }

    private void refreshInBackground(boolean toast) {
        new Thread(() -> {
            boolean ok = Scheduler.refresh(getApplicationContext());
            runOnUiThread(() -> {
                if (toast) Toast.makeText(this, ok ? "Uppdaterat" : "Kunde inte uppdatera", Toast.LENGTH_SHORT).show();
                render();
            });
        }).start();
    }

    private void pickTime() {
        new TimePickerDialog(this, (v, h, m) -> {
            store.setTime(h, m);
            Scheduler.scheduleNext(this);
            render();
        }, store.hour(), store.minute(), true).show();
    }

    private void loadAddressSuggestions() {
        List<String> cached = store.addresses();
        if (!cached.isEmpty()) setSuggestions(cached);
        new Thread(() -> {
            try {
                List<String> fresh = Api.fetchAddresses();
                if (fresh.isEmpty()) return;
                store.setAddresses(fresh);
                runOnUiThread(() -> setSuggestions(fresh));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void setSuggestions(List<String> a) {
        addressField.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, a));
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
