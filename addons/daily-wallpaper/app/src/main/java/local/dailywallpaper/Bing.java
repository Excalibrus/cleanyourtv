package local.dailywallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Bing's picture of the day, UK edition: where to ask, and how its answer becomes a {@link Picture}. */
final class Bing {
    static final String HOST = "https://www.bing.com";
    /** The UK edition: English captions, and a new picture at UK midnight (01:00 in central Europe). */
    static final String MARKET = "en-GB";
    static final String FEED = HOST + "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=" + MARKET;
    /** The picture at TV resolution; Bing also has UHD, which a 1 GB TV would only scale down again. */
    static final String SIZE = "_1920x1080.jpg";
    static final int MAX_CAPTION = 200;
    private static final String PREFIX = "/th?id=";
    /** The id doubles as a file name: letters, digits, dot, dash, underscore; not starting with a dot. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}");
    private static final DateTimeFormatter START = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** One day's picture: its id (also its file name), where to download it, its caption and when it started. */
    static final class Picture {
        final String id;
        final String url;
        final String caption;
        /** When Bing put it up, UTC milliseconds; the next one follows a day later. */
        final long start;

        Picture(String id, String url, String caption, long start) {
            this.id = id;
            this.url = url;
            this.caption = caption;
            this.start = start;
        }
    }

    private Bing() {
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The feed's first picture. Anything but a plain picture on www.bing.com is refused. */
    static Picture parse(String json) throws IOException {
        try {
            JSONArray images = new JSONObject(json).getJSONArray("images");
            JSONObject image = images.getJSONObject(0);
            String base = image.getString("urlbase");
            if (!base.startsWith(PREFIX)) throw new IOException("unexpected picture address");
            String id = base.substring(PREFIX.length());
            if (!validId(id)) throw new IOException("unexpected picture address");
            long start = startTime(image.optString("fullstartdate", ""));
            if (start <= 0) throw new IOException("no start date");
            return new Picture(id, HOST + base + SIZE, caption(image.optString("copyright", "")), start);
        } catch (JSONException e) {
            throw new IOException("unreadable answer", e);
        }
    }

    /** {@code fullstartdate}, {@code 202611230000}, is UTC. 0 when it is not that shape. */
    static long startTime(String value) {
        if (value == null || value.length() != 12) return 0;
        try {
            return LocalDateTime.parse(value, START).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /**
     * Bing's {@code copyright}, "Fiddlehead fern (© Reza Saputra/Getty Images)", as the line on Home:
     * "Fiddlehead fern · © Reza Saputra/Getty Images". Control characters go, and a long one ends in "…".
     */
    static String caption(String copyright) {
        String s = clean(copyright);
        int open = s.lastIndexOf("(©");
        if (open >= 0 && s.endsWith(")")) {
            String what = s.substring(0, open).trim();
            String who = s.substring(open + 1, s.length() - 1).trim();
            s = what.isEmpty() ? who : who.isEmpty() ? what : what + " · " + who;
        }
        if (s.length() > MAX_CAPTION) s = s.substring(0, MAX_CAPTION - 1).trim() + "…";
        return s;
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT) {
                space = out.length() > 0;
                continue;
            }
            if (space) out.append(' ');
            space = false;
            out.append(ch);
        }
        return out.toString();
    }
}
