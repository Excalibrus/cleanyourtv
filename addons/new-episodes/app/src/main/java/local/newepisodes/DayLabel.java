package local.newepisodes;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** "today", "yesterday", "tomorrow", "Fri" for the past week, "Nov 12" before that, in the TV's time zone. */
final class DayLabel {
    private DayLabel() {
    }

    static String of(long released, long now, TimeZone zone) {
        long diff = Episodes.day(released, zone) - Episodes.day(now, zone);
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
}
