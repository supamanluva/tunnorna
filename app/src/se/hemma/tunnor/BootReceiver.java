package se.hemma.tunnor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Larm försvinner vid omstart, så de läggs in igen här. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        Scheduler.scheduleDailyRefresh(app);
        Scheduler.scheduleNext(app);
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
