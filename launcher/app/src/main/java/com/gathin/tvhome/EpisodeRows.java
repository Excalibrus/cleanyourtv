package com.gathin.tvhome;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * episodes_panel: what the NEW EPISODES panel shows for a snapshot from the New Episodes app, without Android types
 * so it is unit-tested. The app sends every row it has; the panel decides how many fit, the day labels (from its own
 * clock, so an old snapshot still says "yesterday" correctly) and the extra lines.
 */
final class EpisodeRows {
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** The panel's most rows, whatever room there is. */
    static final int MAX_ROWS = 5;
    /** A refresh shows the loader only once it has run this long. */
    static final long SPINNER_DELAY_MS = 1000;
    /** A refresh still marked running after this long died with the app's process. */
    static final long STALE_RUN_MS = 3 * 60_000L;
    /** "updated N days ago" once the last good refresh is this old. */
    static final long STALE_AFTER_MS = DAY_MS;
    /** The app drops an episode 30 days after release; an old snapshot is held to the same. */
    static final int WINDOW_DAYS = 30;

    static final String SETUP = "Set up new episodes";
    static final String SIGN_IN = "Sign in to Stremio again";

    private static final Pattern SERIES_ID = Pattern.compile("^tt\\d{1,12}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^tt\\d{1,12}:\\d{1,4}:\\d{1,5}$");

    private EpisodeRows() {
    }

    /** One row of the app's snapshot. */
    static final class Episode {
        final String series;
        final String name;
        final String video;
        final int season;
        final int episode;
        final String title;
        final long released;
        final int more;

        Episode(String series, String name, String video, int season, int episode, String title, long released, int more) {
            this.series = series;
            this.name = name == null ? "" : name;
            this.video = video;
            this.season = season;
            this.episode = episode;
            this.title = title == null ? "" : title;
            this.released = released;
            this.more = more;
        }
    }

    /** The app's snapshot (format v1). */
    static final class Snapshot {
        /** "ok", "setup" (signed out or nothing picked) or "expired" (sign in again; the last list stays). */
        final String state;
        final long lastSuccess;
        final boolean refreshing;
        final long refreshStarted;
        final boolean retrying;
        final List<Episode> rows;

        Snapshot(String state, long lastSuccess, boolean refreshing, long refreshStarted, boolean retrying,
                 List<Episode> rows) {
            this.state = state == null ? "setup" : state;
            this.lastSuccess = lastSuccess;
            this.refreshing = refreshing;
            this.refreshStarted = refreshStarted;
            this.retrying = retrying;
            this.rows = rows == null ? new ArrayList<>() : rows;
        }
    }

    enum Kind {
        /** An episode: OK opens it in Stremio. */
        EPISODE,
        /** "+N more": OK opens the app, which lists them all. */
        MORE,
        /** "Set up new episodes": the app is missing, signed out or has nothing picked. OK opens it. */
        SETUP,
        /** "Sign in to Stremio again": OK opens the app. */
        SIGN_IN,
        /** "updated N days ago", grey, not focusable. */
        STALE
    }

    /** One line of the panel. */
    static final class Line {
        final Kind kind;
        final Episode episode;
        final String text;
        final String code;
        final String day;
        /** The day is "tomorrow": grey instead of the accent. */
        final boolean soon;

        Line(Kind kind, Episode episode, String text, String code, String day, boolean soon) {
            this.kind = kind;
            this.episode = episode;
            this.text = text;
            this.code = code;
            this.day = day;
            this.soon = soon;
        }

        boolean focusable() {
            return kind != Kind.STALE;
        }
    }

    /**
     * The panel's lines for {@code s} (null: the app is missing or does not answer this launcher), at most
     * {@code capacity} of them. Empty when there is nothing to show, which hides the panel.
     */
    static List<Line> lines(Snapshot s, int capacity, long now, TimeZone zone) {
        List<Line> out = new ArrayList<>();
        int room = Math.max(1, Math.min(MAX_ROWS, capacity));
        if (s == null || "setup".equals(s.state) || !("ok".equals(s.state) || "expired".equals(s.state))) {
            out.add(new Line(Kind.SETUP, null, SETUP, "", "", false));
            return out;
        }
        boolean expired = "expired".equals(s.state);
        List<Episode> episodes = new ArrayList<>();
        long today = day(now, zone);
        for (Episode e : s.rows) {
            if (deepLink(e) == null || e.released <= 0) continue;
            long diff = day(e.released, zone) - today;
            if (diff > 1 || diff < -WINDOW_DAYS) continue;
            episodes.add(e);
        }
        if (episodes.isEmpty() && !expired) return out;

        int left = room - (expired ? 1 : 0);
        String stale = staleText(s.lastSuccess, now);
        boolean showStale = stale != null && !episodes.isEmpty() && left >= 2;
        if (showStale) left--;
        if (left > 0 && !episodes.isEmpty()) {
            boolean overflow = episodes.size() > left;
            int shown = overflow ? left - 1 : episodes.size();
            for (int i = 0; i < shown; i++) out.add(episodeLine(episodes.get(i), now, zone));
            if (overflow) {
                int more = episodes.size() - shown;
                out.add(new Line(Kind.MORE, null, "+" + more + " more", "", "", false));
            }
        }
        if (showStale) out.add(new Line(Kind.STALE, null, stale, "", "", false));
        if (expired) out.add(new Line(Kind.SIGN_IN, null, SIGN_IN, "", "", false));
        return out;
    }

    private static Line episodeLine(Episode e, long now, TimeZone zone) {
        String code = code(e.season, e.episode) + (e.more > 0 ? " +" + e.more : "");
        String day = dayLabel(e.released, now, zone);
        return new Line(Kind.EPISODE, e, e.name, code, day, "tomorrow".equals(day));
    }

    /** Whether the loader shows: during a retry, or once a refresh has run a second. */
    static boolean spinner(Snapshot s, long now) {
        if (s == null || "setup".equals(s.state)) return false;
        if (s.retrying) return true;
        if (!s.refreshing) return false;
        long ran = now - s.refreshStarted;
        return ran >= SPINNER_DELAY_MS && ran < STALE_RUN_MS;
    }

    /** "updated 2 days ago" once the last good refresh is a day old, else null. */
    static String staleText(long lastSuccess, long now) {
        if (lastSuccess <= 0 || now - lastSuccess < STALE_AFTER_MS) return null;
        long days = (now - lastSuccess) / DAY_MS;
        return "updated " + days + (days == 1 ? " day ago" : " days ago");
    }

    static long day(long ms, TimeZone zone) {
        return Math.floorDiv(ms + zone.getOffset(ms), DAY_MS);
    }

    /** "today", "yesterday", "tomorrow", "Fri" for the past week, "Nov 12" before that, in the TV's time zone. */
    static String dayLabel(long released, long now, TimeZone zone) {
        long diff = day(released, zone) - day(now, zone);
        if (diff == 0) return "today";
        if (diff == -1) return "yesterday";
        if (diff == 1) return "tomorrow";
        SimpleDateFormat f = new SimpleDateFormat(diff < 0 && diff >= -6 ? "EEE" : "MMM d", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(released));
    }

    /** "S05E03". */
    static String code(int season, int episode) {
        return String.format(Locale.US, "S%02dE%02d", season, episode);
    }

    /** The title's second line while the row is focused: "S05E03 · Episode name". */
    static String subtitle(Episode e) {
        String code = code(e.season, e.episode);
        return e.title.isEmpty() ? code : code + " · " + e.title;
    }

    /**
     * Stremio's link to the episode, {@code stremio:///detail/series/<series>/<video>}; null when the ids are not the
     * IMDb ids Cinemeta uses, so nothing else is ever put into the link.
     */
    static String deepLink(Episode e) {
        if (e == null || e.series == null || e.video == null) return null;
        if (!SERIES_ID.matcher(e.series).matches() || !VIDEO_ID.matcher(e.video).matches()) return null;
        if (!e.video.startsWith(e.series + ":")) return null;
        return "stremio:///detail/series/" + e.series + "/" + e.video;
    }
}
