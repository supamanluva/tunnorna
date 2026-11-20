package se.hemma.tunnor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        if (Scheduler.ACTION_REMIND.equals(intent.getAction())) {
            String d = intent.getStringExtra(Scheduler.EXTRA_DATE);
            if (d != null) {
                LocalDate date = LocalDate.parse(d);
                Store s = new Store(app);
                Store.Pickup p = Scheduler.find(s.pickups(), date);
                if (p == null) p = new Store.Pickup(date, "Sophämtning");
                boolean snooze = intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false);
                if (snooze || !date.toString().equals(s.lastNotified())) Scheduler.ring(app, p);
            }
        }
        // Hämta färskt schema och planera nästa påminnelse.
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Scheduler.refresh(app);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
