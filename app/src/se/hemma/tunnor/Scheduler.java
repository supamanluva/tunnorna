package se.hemma.tunnor;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Planerar påminnelser och visar notiser. */
final class Scheduler {

    static final String ACTION_REMIND = "se.hemma.tunnor.REMIND";
    static final String ACTION_REFRESH = "se.hemma.tunnor.REFRESH";
    static final String EXTRA_DATE = "date";
    static final String EXTRA_SNOOZE = "snooze";
    private static final String CHANNEL = "tunnor";
    static final Locale SV = new Locale("sv", "SE");

    private Scheduler() {}

    static LocalDateTime reminderTime(Store s, LocalDate pickup) {
        return LocalDateTime.of(pickup.minusDays(1), LocalTime.of(s.hour(), s.minute()));
    }

    /** Första hämtningen vars påminnelse ligger i framtiden, eller null. */
    static Store.Pickup nextReminder(Store s) {
        LocalDateTime now = LocalDateTime.now();
        for (Store.Pickup p : s.pickups()) {
            if (reminderTime(s, p.date).isAfter(now)) return p;
        }
        return null;
    }

    /**
     * Ställer in larmet för nästa påminnelse. Om telefonen var avstängd vid påminnelsetiden
     * men det fortfarande är kvällen innan, visas notisen direkt.
     */
    static void scheduleNext(Context ctx) {
        Store s = new Store(ctx);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = remindIntent(ctx, null);
        am.cancel(pi);

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        for (Store.Pickup p : s.pickups()) {
            if (p.date.equals(tomorrow)
                    && !reminderTime(s, p.date).isAfter(LocalDateTime.now())
                    && !p.date.toString().equals(s.lastNotified())) {
                notify(ctx, p);
            }
        }

        Store.Pickup next = nextReminder(s);
        if (next == null) return;
        long at = reminderTime(s, next.date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        setAlarm(ctx, at, remindIntent(ctx, next.date));
    }

    /** Väckarklocklarm: mest pålitligt, går igenom strömsparläge och visar klockikon. */
    private static void setAlarm(Context ctx, long at, PendingIntent pi) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) {
            PendingIntent show = PendingIntent.getActivity(ctx, 5,
                    new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void scheduleSnooze(Context ctx, LocalDate date, int minutes) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_REMIND)
                .putExtra(EXTRA_DATE, date.toString()).putExtra(EXTRA_SNOOZE, true);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setAlarm(ctx, System.currentTimeMillis() + minutes * 60_000L, pi);
    }

    /** Startar det ringande alarmet; faller tillbaka på vanlig notis om det inte går. */
    static void ring(Context ctx, Store.Pickup p) {
        new Store(ctx).setLastNotified(p.date);
        try {
            AlarmService.start(ctx, p);
        } catch (Exception e) {
            notify(ctx, p);
        }
    }

    /** Uppdaterar schemat från kommunen en gång per dag (vid ca 12:00). */
    static void scheduleDailyRefresh(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime first = LocalDate.now().atTime(12, 0);
        if (!first.isAfter(LocalDateTime.now())) first = first.plusDays(1);
        long at = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, pi);
    }

    private static PendingIntent remindIntent(Context ctx, LocalDate date) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_REMIND);
        if (date != null) i.putExtra(EXTRA_DATE, date.toString());
        return PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Hämtar nytt schema och planerar om. Körs på bakgrundstråd. */
    static boolean refresh(Context ctx) {
        Store s = new Store(ctx);
        if (s.address().isEmpty()) return false;
        try {
            s.mergePickups(Api.fetchSchedule(s.address()));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            scheduleNext(ctx);
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Ställ ut tunnorna",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Påminnelse kvällen före sophämtning");
        nm.createNotificationChannel(ch);
    }

    static void notify(Context ctx, Store.Pickup p) {
        ensureChannel(ctx);
        String day = p.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", SV));
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE);
        String body = "Imorgon (" + day + ") hämtas: " + p.what
                + ".\nTunnorna ska stå ute senast kl. 06.00.";
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Ställ ut tunnorna!")
                .setContentText("Imorgon hämtas: " + p.what)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        try {
            ctx.getSystemService(NotificationManager.class).notify(100, n);
        } catch (SecurityException ignored) {
            // Notisbehörighet saknas.
        }
        new Store(ctx).setLastNotified(p.date);
    }

    static String pretty(LocalDate d) {
        String s = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", SV));
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static Store.Pickup find(List<Store.Pickup> list, LocalDate date) {
        for (Store.Pickup p : list) if (p.date.equals(date)) return p;
        return null;
    }
}
