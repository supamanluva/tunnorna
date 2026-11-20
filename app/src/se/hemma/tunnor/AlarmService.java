package se.hemma.tunnor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.time.LocalDate;

/** Ringer som ett väckarklockalarm tills man stänger av eller snoozar. */
public class AlarmService extends Service {

    static final String ACTION_START = "se.hemma.tunnor.ALARM_START";
    static final String ACTION_STOP = "se.hemma.tunnor.ALARM_STOP";
    static final String ACTION_SNOOZE = "se.hemma.tunnor.ALARM_SNOOZE";
    static final String ACTION_DONE = "se.hemma.tunnor.ALARM_DONE"; // till AlarmActivity
    static final String EXTRA_WHAT = "what";
    static final int SNOOZE_MIN = 10;
    private static final long RING_MAX_MS = 5 * 60 * 1000;
    private static final String CHANNEL = "tunnor_alarm";
    private static final int NOTIF_ID = 200;

    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store.Pickup current;

    static void start(Context ctx, Store.Pickup p) {
        Intent i = new Intent(ctx, AlarmService.class).setAction(ACTION_START)
                .putExtra(Scheduler.EXTRA_DATE, p.date.toString())
                .putExtra(EXTRA_WHAT, p.what);
        ctx.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            LocalDate date = LocalDate.parse(intent.getStringExtra(Scheduler.EXTRA_DATE));
            String what = intent.getStringExtra(EXTRA_WHAT);
            current = new Store.Pickup(date, what == null ? "Sophämtning" : what);
            Notification n = buildNotification(current);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(NOTIF_ID, n);
            }
            startRinging();
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::timeout, RING_MAX_MS);
        } else if (ACTION_SNOOZE.equals(action)) {
            if (current != null) Scheduler.scheduleSnooze(this, current.date, SNOOZE_MIN);
            finishAlarm();
        } else {
            finishAlarm();
        }
        return START_NOT_STICKY;
    }

    /** Ingen reagerade: tyst, men lämna en vanlig notis kvar. */
    private void timeout() {
        Store.Pickup p = current;
        finishAlarm();
        if (p != null) Scheduler.notify(this, p);
    }

    private void finishAlarm() {
        handler.removeCallbacksAndMessages(null);
        stopRinging();
        sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startRinging() {
        stopRinging();
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            player = null;
        }
        vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 800, 600};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
    }

    private void stopRinging() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
    }

    private Notification buildNotification(Store.Pickup p) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarm: ställ ut tunnorna",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null); // ljudet spelas av tjänsten
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);

        Intent full = new Intent(this, AlarmActivity.class)
                .putExtra(Scheduler.EXTRA_DATE, p.date.toString())
                .putExtra(EXTRA_WHAT, p.what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(this, 10, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 11,
                new Intent(this, AlarmService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snoozePi = PendingIntent.getService(this, 12,
                new Intent(this, AlarmService.class).setAction(ACTION_SNOOZE), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Ställ ut tunnorna!")
                .setContentText(p.what)
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Imorgon (" + Scheduler.pretty(p.date).toLowerCase(Scheduler.SV) + ") hämtas:\n" + p.what))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .addAction(new Notification.Action.Builder(null, "Stäng av", stopPi).build())
                .addAction(new Notification.Action.Builder(null, "Snooza " + SNOOZE_MIN + " min", snoozePi).build())
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRinging();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
