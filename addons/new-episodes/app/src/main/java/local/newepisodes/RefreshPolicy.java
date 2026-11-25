package local.newepisodes;

/** When a refresh runs, and when the next try after a failure is. No Android types, so it is unit-tested. */
final class RefreshPolicy {
    static final long MINUTE_MS = 60_000L;
    /** A return to Home refreshes at most this often. */
    static final long HOME_THROTTLE_MS = 15 * MINUTE_MS;
    /** After a failure, a return to Home tries again once the last try is this old. */
    static final long HOME_RETRY_MS = MINUTE_MS;
    /** Cinemeta's episode lists are fetched again once they are this old; the library is read every time. */
    static final long META_MAX_AGE_MS = 6 * 60 * MINUTE_MS;
    /** A refresh still marked running after this long died with its process. */
    static final long STALE_RUN_MS = 3 * MINUTE_MS;
    /** Minutes to the next try after the 1st, 2nd, 3rd failure; every later one waits the last value. */
    private static final long[] BACKOFF_MIN = {1, 5, 15, 30};

    enum Reason {
        BOOT, HOME, MANUAL, RETRY;

        static Reason from(String name) {
            if (name != null) {
                for (Reason r : values()) if (r.name().equalsIgnoreCase(name)) return r;
            }
            return HOME;
        }
    }

    /** What the decision reads from the store. */
    static final class State {
        boolean signedIn;
        boolean running;
        long runningSince;
        long lastAttempt;
        int failures;
        /** The last try was made since the TV last started. */
        boolean attemptedThisBoot;
    }

    private RefreshPolicy() {
    }

    static boolean isRunning(State s, long now) {
        return s.running && now >= s.runningSince && now - s.runningSince < STALE_RUN_MS;
    }

    /**
     * Whether a refresh asked for {@code reason} runs now. Nothing runs while signed out, which also covers an
     * expired sign-in: retries stop until the person signs in again. {@code interactive} is the screen's state:
     * timed retries wait while the TV is off, and the next return to Home catches up.
     */
    static boolean shouldRun(Reason reason, State s, long now, boolean interactive) {
        if (!s.signedIn || isRunning(s, now)) return false;
        long since = now - s.lastAttempt;
        // A clock set back (the TV's time corrected after boot) leaves a last try in the future: let it run.
        boolean clockBack = since < 0;
        switch (reason) {
            case BOOT:
                // BOOT_COMPLETED can come a minute or two after Home has already refreshed: once per start is enough.
                return !s.attemptedThisBoot;
            case MANUAL:
                return true;
            case RETRY:
                return s.failures > 0 && interactive;
            case HOME:
            default:
                if (clockBack) return true;
                return since >= (s.failures > 0 ? HOME_RETRY_MS : HOME_THROTTLE_MS);
        }
    }

    /** The wait before the next try, after {@code failures} failures in a row (1 or more). */
    static long retryDelay(int failures) {
        int i = Math.max(0, Math.min(failures - 1, BACKOFF_MIN.length - 1));
        return BACKOFF_MIN[i] * MINUTE_MS;
    }

    /** Whether a cached episode list fetched at {@code fetched} is due for a new fetch. */
    static boolean metaDue(long fetched, long now) {
        return fetched <= 0 || now < fetched || now - fetched >= META_MAX_AGE_MS;
    }
}
