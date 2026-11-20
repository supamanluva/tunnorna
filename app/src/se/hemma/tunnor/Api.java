package se.hemma.tunnor;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Hämtar schema från Vännäs kommuns webbplats. */
final class Api {

    static final class AddressNotFound extends Exception {}

    private static final String CALENDAR =
            "https://vannas.se/rest-api/garbage-collector-calendar/subscription/";
    private static final String PAGE =
            "https://www.vannas.se/bygga-miljo-och-infrastruktur/avfall-och-atervinning/hemsortering/en--och-tvafamiljshus";

    private Api() {}

    /** Kommande hämtningar för adressen, via kommunens kalenderprenumeration (iCalendar). */
    static List<Store.Pickup> fetchSchedule(String address) throws IOException, AddressNotFound {
        String enc = URLEncoder.encode(address.trim(), "UTF-8").replace("+", "%20");
        HttpURLConnection c = open(CALENDAR + enc);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                String err = read(c.getErrorStream());
                if (err.contains("ADDRESS_NOT_FOUND")) throw new AddressNotFound();
                throw new IOException("HTTP " + code);
            }
            return parseIcs(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    static List<Store.Pickup> parseIcs(String ics) {
        // "Unfold": rader som börjar med blanksteg hör till föregående rad.
        String text = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
        List<Store.Pickup> out = new ArrayList<>();
        DateTimeFormatter f = DateTimeFormatter.BASIC_ISO_DATE;
        LocalDate date = null;
        String what = "";
        boolean inEvent = false, inAlarm = false;
        for (String line : text.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) { inEvent = true; date = null; what = ""; }
            else if (line.equals("BEGIN:VALARM")) inAlarm = true;
            else if (line.equals("END:VALARM")) inAlarm = false;
            else if (line.equals("END:VEVENT")) {
                if (date != null) out.add(new Store.Pickup(date, what.isEmpty() ? "Sophämtning" : what));
                inEvent = false;
            } else if (inEvent && !inAlarm) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon), val = line.substring(colon + 1);
                if (key.startsWith("DTSTART")) {
                    try { date = LocalDate.parse(val.substring(0, 8), f); } catch (Exception ignored) {}
                } else if (key.startsWith("DESCRIPTION")) {
                    what = val.replace("\\,", ",").replace("\\n", " ").replace("\\;", ";")
                            .replaceFirst("^Kommunen hämtar\\s*", "").trim();
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Alla sökbara adresser (för autokomplettering), utlästa ur kommunens sida. */
    static List<String> fetchAddresses() throws Exception {
        HttpURLConnection c = open(PAGE);
        try {
            String html = read(c.getInputStream());
            int i = html.indexOf("\"addressList\"");
            if (i < 0) return new ArrayList<>();
            int start = html.indexOf("\"body\":[", i);
            int end = html.indexOf(']', start);
            JSONArray arr = new JSONArray(html.substring(start + 7, end + 1));
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (int k = 0; k < arr.length(); k++) set.add(arr.getString(k));
            List<String> list = new ArrayList<>(set);
            Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
            return list;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Tunnorna-Android/1.0");
        return c;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
