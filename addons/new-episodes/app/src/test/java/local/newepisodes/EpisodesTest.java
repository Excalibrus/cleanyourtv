package local.newepisodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class EpisodesTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone LJUBLJANA = TimeZone.getTimeZone("Europe/Ljubljana");
    private static final long NOW = t("2026-11-23T18:00:00Z");

    private static long t(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static long daysAgo(int days) {
        return t("2026-11-23T05:00:00Z") - days * Episodes.DAY_MS;
    }

    private static Video ep(String series, int season, int episode, long released) {
        return new Video(series + ":" + season + ":" + episode, season, episode, "Ep " + episode, released);
    }

    private static Episodes.Series series(String id, String name, String watched, Video... videos) {
        return new Episodes.Series(id, name, watched, Arrays.asList(videos));
    }

    @Test
    public void firstUnwatchedEpisodeWithTheRestCounted() {
        String watched = WatchedTest.field("tt1:1:1", true);
        List<Episodes.Row> rows = Episodes.rows(List.of(series("tt1", "Alpha", watched,
                ep("tt1", 1, 1, daysAgo(10)), ep("tt1", 1, 2, daysAgo(3)), ep("tt1", 1, 3, daysAgo(0)))), NOW, UTC);
        assertEquals(1, rows.size());
        Episodes.Row r = rows.get(0);
        assertEquals("tt1:1:2", r.video.id);
        assertEquals(1, r.more);
        assertTrue(r.out);
        assertEquals(daysAgo(0), r.sortKey);
    }

    @Test
    public void watchedSpecialsOldAndFarFutureEpisodesGiveNoRow() {
        String allWatched = WatchedTest.field("tt3:1:5", true);
        List<Episodes.Row> rows = Episodes.rows(List.of(
                series("tt3", "Watched", allWatched, ep("tt3", 1, 5, daysAgo(1))),
                series("tt4", "Special", null, ep("tt4", 0, 1, daysAgo(0))),
                series("tt5", "Old", null, ep("tt5", 2, 1, daysAgo(31))),
                series("tt6", "Later", null, ep("tt6", 2, 1, daysAgo(-2))),
                series("tt7", "Undated", null, ep("tt7", 1, 1, 0))), NOW, UTC);
        assertTrue(rows.isEmpty());
    }

    @Test
    public void thirtyDaysIsTheLastDay() {
        List<Episodes.Row> rows = Episodes.rows(List.of(series("tt5", "Edge", null, ep("tt5", 1, 1, daysAgo(30)))),
                NOW, UTC);
        assertEquals(1, rows.size());
    }

    @Test
    public void newestFirstTomorrowLast() {
        List<Episodes.Row> rows = Episodes.rows(List.of(
                series("tt10", "Soon", null, ep("tt10", 3, 4, daysAgo(-1))),
                series("tt11", "Yesterday", null, ep("tt11", 3, 1, daysAgo(1))),
                series("tt12", "Today", null, ep("tt12", 5, 3, daysAgo(0))),
                series("tt13", "Friday", null, ep("tt13", 4, 2, daysAgo(3)))), NOW, UTC);
        List<String> names = new ArrayList<>();
        for (Episodes.Row r : rows) names.add(r.name);
        assertEquals(List.of("Today", "Yesterday", "Friday", "Soon"), names);
        assertFalse(rows.get(3).out);
    }

    @Test
    public void aSeriesWithTodayAndTomorrowSortsByToday() {
        List<Episodes.Row> rows = Episodes.rows(List.of(
                series("tt20", "Daily", null, ep("tt20", 1, 6, daysAgo(-1)), ep("tt20", 1, 5, daysAgo(0))),
                series("tt21", "Weekly", null, ep("tt21", 1, 1, daysAgo(2)))), NOW, UTC);
        assertEquals("Daily", rows.get(0).name);
        assertEquals("tt20:1:5", rows.get(0).video.id);
        assertEquals(1, rows.get(0).more);
    }

    @Test
    public void tomorrowFollowsTheTvsCalendar() {
        // 23:30 UTC on the 24th is still tomorrow in UTC, but 00:30 on the 25th in Ljubljana.
        long late = t("2026-11-24T23:30:00Z");
        List<Episodes.Series> s = List.of(series("tt30", "Late", null, ep("tt30", 1, 1, late)));
        assertEquals(1, Episodes.rows(s, NOW, UTC).size());
        assertEquals(0, Episodes.rows(s, NOW, LJUBLJANA).size());
    }

    @Test
    public void dayLabels() {
        assertEquals("today", DayLabel.of(daysAgo(0), NOW, UTC));
        assertEquals("yesterday", DayLabel.of(daysAgo(1), NOW, UTC));
        assertEquals("tomorrow", DayLabel.of(daysAgo(-1), NOW, UTC));
        // 2026-11-20 is a Friday.
        assertEquals("Fri", DayLabel.of(daysAgo(3), NOW, UTC));
        assertEquals("Tue", DayLabel.of(daysAgo(6), NOW, UTC));
        assertEquals("Nov 16", DayLabel.of(daysAgo(7), NOW, UTC));
        assertEquals("Nov 26", DayLabel.of(daysAgo(-3), NOW, UTC));
        assertEquals("S05E03", DayLabel.code(5, 3));
        assertEquals("S12E103", DayLabel.code(12, 103));
    }
}
