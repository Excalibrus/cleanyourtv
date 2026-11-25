package local.dailywallpaper;

/** When the picture is checked, and when after a failure. No Android types, so it is unit-tested. */
final class Schedule {
    static final long MINUTE_MS = 60_000L;
    static final long HOUR_MS = 60 * MINUTE_MS;
    static final long DAY_MS = 24 * HOUR_MS;
    /** Bing's new picture is looked for this long after it should be up. */
    static final long AFTER_CHANGE_MS = 5 * MINUTE_MS;
    /** Bing still shows the old picture after its change time: look again this much later. */
    static final long NOT_YET_MS = 30 * MINUTE_MS;
    /** A check still marked running after this long died with its process. */
    static final long STALE_RUN_MS = 3 * MINUTE_MS;
    /** Minutes to the next try after the 1st, 2nd, 3rd failure; every later one waits the last value. */
    private static final long[] BACKOFF_MIN = {5, 15, 60};

    enum Reason {
        /** The TV started. */
        BOOT,
        /** Home came back (the launcher asks). */
        HOME,
        /** The timed check: after the daily change, or a retry. Waits while the TV is off. */
        TIMED,
        /** The Refresh button. */
        MANUAL;

        static Reason from(String name) {
            if (name != null) {
                for (Reason r : values()) if (r.name().equalsIgnoreCase(name)) return r;
            }
            return HOME;
        }
    }

    /** What the decision reads from the store. */
    static final class State {
        boolean hasPicture;
        long nextCheck;
        boolean running;
        long runningSince;
    }

    private Schedule() {
    }

    static boolean isRunning(State s, long now) {
        return s.running && now >= s.runningSince && now - s.runningSince < STALE_RUN_MS;
    }

    /**
     * Whether a check is due: no picture yet, or its time has come. A next check over a day away means the clock
     * was set back (the TV's time corrected after boot): check then too, rather than wait for a time that never comes.
     */
    static boolean due(State s, long now) {
        return !s.hasPicture || now >= s.nextCheck || s.nextCheck - now > DAY_MS + AFTER_CHANGE_MS;
    }

    /** Whether a check asked for {@code reason} runs now. {@code interactive} is the screen's state. */
    static boolean shouldRun(Reason reason, State s, long now, boolean interactive) {
        if (isRunning(s, now)) return false;
        switch (reason) {
            case MANUAL:
                return true;
            case TIMED:
                // The TV is off: the next return to Home catches up.
                return interactive && due(s, now);
            case BOOT:
            case HOME:
            default:
                return due(s, now);
        }
    }

    /** After a good check whose picture started at {@code start}: 5 minutes after the next change, or soon. */
    static long nextAfterSuccess(long start, long now) {
        long next = start + DAY_MS + AFTER_CHANGE_MS;
        return next > now ? next : now + NOT_YET_MS;
    }

    /** The wait before the next try, after {@code failures} failures in a row (1 or more). */
    static long retryDelay(int failures) {
        int i = Math.max(0, Math.min(failures - 1, BACKOFF_MIN.length - 1));
        return BACKOFF_MIN[i] * MINUTE_MS;
    }
}
