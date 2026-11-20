package se.hemma.tunnor;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Sparade inställningar och senast hämtade schema. */
final class Store {

    static final class Pickup implements Comparable<Pickup> {
        final LocalDate date;
        final String what; // t.ex. "Matavfall, Pappersförpackningar"

        Pickup(LocalDate date, String what) {
            this.date = date;
            this.what = what;
        }

        @Override
        public int compareTo(Pickup o) {
            return date.compareTo(o.date);
        }
    }

    private final SharedPreferences p;

    Store(Context ctx) {
        p = ctx.getSharedPreferences("tunnor", Context.MODE_PRIVATE);
    }

    String address() { return p.getString("address", ""); }
    int hour() { return p.getInt("hour", 20); }
    int minute() { return p.getInt("minute", 0); }
    String lastNotified() { return p.getString("lastNotified", ""); }
    long lastRefresh() { return p.getLong("lastRefresh", 0); }

    void setAddress(String a) { p.edit().putString("address", a).apply(); }
    void setTime(int h, int m) { p.edit().putInt("hour", h).putInt("minute", m).apply(); }
    void setLastNotified(LocalDate d) { p.edit().putString("lastNotified", d.toString()).apply(); }

    List<Pickup> pickups() {
        List<Pickup> out = new ArrayList<>();
        String raw = p.getString("pickups", "");
        for (String line : raw.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            try {
                out.add(new Pickup(LocalDate.parse(line.substring(0, bar)), line.substring(bar + 1)));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Sparar nya hämtningar. Kommunens svar ersätter det gamla schemat (så att ändrade
     * datum slår igenom); ett tomt svar ignoreras så att det sparade schemat finns kvar.
     */
    void mergePickups(List<Pickup> fresh) {
        if (fresh.isEmpty()) return;
        List<Pickup> sorted = new ArrayList<>(fresh);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (Pickup x : sorted) sb.append(x.date).append('|').append(x.what).append('\n');
        p.edit().putString("pickups", sb.toString())
                .putLong("lastRefresh", System.currentTimeMillis()).apply();
    }

    void clearPickups() {
        p.edit().remove("pickups").remove("lastNotified").apply();
    }

    List<String> addresses() {
        String raw = p.getString("addresses", "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    void setAddresses(List<String> list) {
        p.edit().putString("addresses", String.join("\n", list)).apply();
    }
}
