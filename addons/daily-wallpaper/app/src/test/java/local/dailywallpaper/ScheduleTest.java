package local.dailywallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class ScheduleTest {
    private static final long MIN = Schedule.MINUTE_MS;
    /** 2026-11-23 00:00 UTC: the UK picture's start, 01:00 in Ljubljana. */
    private static final long START = Instant.parse("2026-11-23T00:00:00Z").toEpochMilli();

    private static Schedule.State state(boolean picture, long nextCheck) {
        Schedule.State s = new Schedule.State();
        s.hasPicture = picture;
        s.nextCheck = nextCheck;
        return s;
    }

    @Test
    public void theNextCheckIsFiveMinutesAfterTomorrowsChange() {
        long now = START + 9 * 60 * MIN;
        assertEquals(START + Schedule.DAY_MS + 5 * MIN, Schedule.nextAfterSuccess(START, now));
        // Bing is late and still shows yesterday's picture: look again in half an hour.
        long late = START + Schedule.DAY_MS + 6 * MIN;
        assertEquals(late + 30 * MIN, Schedule.nextAfterSuccess(START, late));
    }

    @Test
    public void homeAndBootRunOnlyWhenDue() {
        long next = START + Schedule.DAY_MS + 5 * MIN;
        Schedule.State s = state(true, next);
        assertFalse(Schedule.shouldRun(Schedule.Reason.HOME, s, next - 1, true));
        assertFalse(Schedule.shouldRun(Schedule.Reason.BOOT, s, next - 1, true));
        assertTrue(Schedule.shouldRun(Schedule.Reason.HOME, s, next, true));
        assertTrue(Schedule.shouldRun(Schedule.Reason.BOOT, s, next, false));
        // No picture yet: always due.
        assertTrue(Schedule.shouldRun(Schedule.Reason.HOME, state(false, next), next - 1, true));
    }

    @Test
    public void theTimedCheckWaitsWhileTheTvIsOff() {
        Schedule.State s = state(true, START);
        assertFalse(Schedule.shouldRun(Schedule.Reason.TIMED, s, START, false));
        assertTrue(Schedule.shouldRun(Schedule.Reason.TIMED, s, START, true));
        assertFalse(Schedule.shouldRun(Schedule.Reason.TIMED, s, START - 1, true));
    }

    @Test
    public void refreshAlwaysRunsUnlessACheckIsRunning() {
        Schedule.State s = state(true, START + Schedule.DAY_MS);
        assertTrue(Schedule.shouldRun(Schedule.Reason.MANUAL, s, START, false));
        s.running = true;
        s.runningSince = START - MIN;
        assertFalse(Schedule.shouldRun(Schedule.Reason.MANUAL, s, START, true));
        // A run marked going for over three minutes died with its process.
        assertTrue(Schedule.shouldRun(Schedule.Reason.MANUAL, s, START + 3 * MIN, true));
    }

    @Test
    public void aClockSetBackDoesNotStopTheChecks() {
        // The TV's clock jumped back two days after the last check: the next check is too far away to trust.
        Schedule.State s = state(true, START + Schedule.DAY_MS + 5 * MIN);
        assertTrue(Schedule.due(s, START - Schedule.DAY_MS));
        assertFalse(Schedule.due(s, START + 1));
    }

    @Test
    public void retriesWait5Then15ThenEveryHour() {
        assertEquals(5 * MIN, Schedule.retryDelay(1));
        assertEquals(15 * MIN, Schedule.retryDelay(2));
        assertEquals(60 * MIN, Schedule.retryDelay(3));
        assertEquals(60 * MIN, Schedule.retryDelay(9));
        assertEquals(5 * MIN, Schedule.retryDelay(0));
    }

    @Test
    public void reasons() {
        assertEquals(Schedule.Reason.BOOT, Schedule.Reason.from("boot"));
        assertEquals(Schedule.Reason.MANUAL, Schedule.Reason.from("MANUAL"));
        assertEquals(Schedule.Reason.HOME, Schedule.Reason.from("anything"));
        assertEquals(Schedule.Reason.HOME, Schedule.Reason.from(null));
    }
}
