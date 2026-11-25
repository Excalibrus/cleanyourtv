package local.newepisodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/** Turns the followed series into the panel's rows: one per series with new, unwatched episodes. */
final class Episodes {
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** An episode drops off this many days after its release day, watched or not. */
    static final int WINDOW_DAYS = 30;
    /** An episode shows from this many days before its release day ("tomorrow"). */
    static final int AHEAD_DAYS = 1;

    private Episodes() {
    }

    /** A followed series: its library entry and Cinemeta's videos. */
    static final class Series {
        final String id;
        final String name;
        /** The library item's {@code state.watched}, or null. */
        final String watched;
        final List<Video> videos;

        Series(String id, String name, String watched, List<Video> videos) {
            this.id = id;
            this.name = name;
            this.watched = watched;
            this.videos = videos;
        }
    }

    /** One panel row: a series' first new unwatched episode, and how many more it has. */
    static final class Row {
        final String series;
        final String name;
        final Video video;
        final int more;
        /** False while the series' only new episodes are still to come (tomorrow); those rows go last. */
        final boolean out;
        /** Newest release among the episodes already out, or the soonest one for a row that is not out. */
        final long sortKey;

        Row(String series, String name, Video video, int more, boolean out, long sortKey) {
            this.series = series;
            this.name = name;
            this.video = video;
            this.more = more;
            this.out = out;
            this.sortKey = sortKey;
        }
    }

    /** The local calendar day of {@code ms}, as a day count since the epoch. */
    static long day(long ms, TimeZone zone) {
        return Math.floorDiv(ms + zone.getOffset(ms), DAY_MS);
    }

    /**
     * Rows for {@code followed} at {@code now}: per series, the episodes (specials left out) released from
     * {@link #WINDOW_DAYS} days ago to {@link #AHEAD_DAYS} day ahead, by the TV's calendar, that the watched
     * field does not mark. Newest first; a series whose new episodes are all still to come goes last, soonest
     * first.
     */
    static List<Row> rows(List<Series> followed, long now, TimeZone zone) {
        long today = day(now, zone);
        List<Row> rows = new ArrayList<>();
        for (Series s : followed) {
            List<Video> sorted = new ArrayList<>(s.videos);
            Collections.sort(sorted, Video.STREMIO_ORDER);
            List<String> ids = new ArrayList<>(sorted.size());
            for (Video v : sorted) ids.add(v.id);
            Watched w = Watched.parse(s.watched);
            boolean[] watched = w == null ? new boolean[sorted.size()] : w.map(ids);

            Video first = null;
            int count = 0;
            long newestOut = Long.MIN_VALUE;
            long soonest = Long.MAX_VALUE;
            for (int i = 0; i < sorted.size(); i++) {
                Video v = sorted.get(i);
                if (watched[i] || v.season <= 0 || v.released <= 0) continue;
                long diff = day(v.released, zone) - today;
                if (diff > AHEAD_DAYS || diff < -WINDOW_DAYS) continue;
                if (first == null) first = v;
                count++;
                if (diff <= 0) newestOut = Math.max(newestOut, v.released);
                else soonest = Math.min(soonest, v.released);
            }
            if (first == null) continue;
            boolean out = newestOut != Long.MIN_VALUE;
            rows.add(new Row(s.id, s.name, first, count - 1, out, out ? newestOut : soonest));
        }
        Collections.sort(rows, ORDER);
        return rows;
    }

    private static final Comparator<Row> ORDER = (a, b) -> {
        if (a.out != b.out) return a.out ? -1 : 1;
        int c = a.out ? Long.compare(b.sortKey, a.sortKey) : Long.compare(a.sortKey, b.sortKey);
        return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
    };
}
