package local.newepisodes;

import static local.newepisodes.RefreshPolicy.MINUTE_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import local.newepisodes.RefreshPolicy.Reason;

import org.junit.Test;

public class RefreshPolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    private static RefreshPolicy.State state(long lastAttemptAgo, int failures) {
        RefreshPolicy.State s = new RefreshPolicy.State();
        s.signedIn = true;
        s.lastAttempt = NOW - lastAttemptAgo;
        s.failures = failures;
        return s;
    }

    @Test
    public void signedOutNeverRuns() {
        RefreshPolicy.State s = state(60 * MINUTE_MS, 2);
        s.signedIn = false;
        for (Reason r : Reason.values()) assertFalse(r.name(), RefreshPolicy.shouldRun(r, s, NOW, true));
    }

    @Test
    public void homeAtMostEveryFifteenMinutes() {
        assertFalse(RefreshPolicy.shouldRun(Reason.HOME, state(14 * MINUTE_MS, 0), NOW, true));
        assertTrue(RefreshPolicy.shouldRun(Reason.HOME, state(15 * MINUTE_MS, 0), NOW, true));
        assertTrue(RefreshPolicy.shouldRun(Reason.HOME, state(NOW, 0), NOW, true));
    }

    @Test
    public void homeRetriesAfterAMinuteWhenTheLastTryFailed() {
        assertFalse(RefreshPolicy.shouldRun(Reason.HOME, state(59_000, 1), NOW, true));
        assertTrue(RefreshPolicy.shouldRun(Reason.HOME, state(61_000, 1), NOW, true));
    }

    @Test
    public void timedRetriesOnlyAfterAFailureAndWhileTheTvIsOn() {
        assertTrue(RefreshPolicy.shouldRun(Reason.RETRY, state(MINUTE_MS, 1), NOW, true));
        assertFalse(RefreshPolicy.shouldRun(Reason.RETRY, state(MINUTE_MS, 1), NOW, false));
        assertFalse(RefreshPolicy.shouldRun(Reason.RETRY, state(MINUTE_MS, 0), NOW, true));
    }

    @Test
    public void bootRunsUnlessHomeAlreadyTriedSinceTheStart() {
        RefreshPolicy.State s = state(0, 0);
        assertTrue(RefreshPolicy.shouldRun(Reason.BOOT, s, NOW, true));
        s.attemptedThisBoot = true;
        assertFalse(RefreshPolicy.shouldRun(Reason.BOOT, s, NOW, true));
        // Whatever the clock says: a TV corrects it after it starts.
        s.lastAttempt = NOW + 3 * 60 * MINUTE_MS;
        assertFalse(RefreshPolicy.shouldRun(Reason.BOOT, s, NOW, true));
    }

    @Test
    public void manualAlwaysRuns() {
        RefreshPolicy.State s = state(0, 0);
        s.attemptedThisBoot = true;
        assertTrue(RefreshPolicy.shouldRun(Reason.MANUAL, s, NOW, true));
    }

    @Test
    public void oneRunAtATimeUnlessItDied() {
        RefreshPolicy.State s = state(20 * MINUTE_MS, 0);
        s.running = true;
        s.runningSince = NOW - 30_000;
        assertFalse(RefreshPolicy.shouldRun(Reason.MANUAL, s, NOW, true));
        s.runningSince = NOW - 4 * MINUTE_MS;
        assertTrue(RefreshPolicy.shouldRun(Reason.MANUAL, s, NOW, true));
    }

    @Test
    public void aClockSetBackDoesNotBlockHome() {
        assertTrue(RefreshPolicy.shouldRun(Reason.HOME, state(-3 * 60 * MINUTE_MS, 0), NOW, true));
    }

    @Test
    public void backoffIsOneFiveFifteenThenThirty() {
        assertEquals(MINUTE_MS, RefreshPolicy.retryDelay(1));
        assertEquals(5 * MINUTE_MS, RefreshPolicy.retryDelay(2));
        assertEquals(15 * MINUTE_MS, RefreshPolicy.retryDelay(3));
        assertEquals(30 * MINUTE_MS, RefreshPolicy.retryDelay(4));
        assertEquals(30 * MINUTE_MS, RefreshPolicy.retryDelay(40));
    }

    @Test
    public void episodeListsAreFetchedAgainAfterSixHours() {
        assertTrue(RefreshPolicy.metaDue(0, NOW));
        assertFalse(RefreshPolicy.metaDue(NOW - 5 * 60 * MINUTE_MS, NOW));
        assertTrue(RefreshPolicy.metaDue(NOW - 6 * 60 * MINUTE_MS, NOW));
        assertTrue(RefreshPolicy.metaDue(NOW + MINUTE_MS, NOW));
    }

    @Test
    public void reasonsFromTheLauncher() {
        assertEquals(Reason.HOME, Reason.from("home"));
        assertEquals(Reason.BOOT, Reason.from("boot"));
        assertEquals(Reason.MANUAL, Reason.from("manual"));
        assertEquals(Reason.HOME, Reason.from(null));
        assertEquals(Reason.HOME, Reason.from("nonsense"));
    }
}
