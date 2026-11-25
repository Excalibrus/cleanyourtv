package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class EpisodeRowsTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = Instant.parse("2026-11-23T18:00:00Z").toEpochMilli();
    private static final long TODAY_5AM = Instant.parse("2026-11-23T05:00:00Z").toEpochMilli();

    private static EpisodeRows.Episode ep(int n, int daysAgo, int more) {
        String id = "tt" + (100 + n);
        return new EpisodeRows.Episode(id, "Series " + n, id + ":2:" + n, 2, n, "Title " + n,
                TODAY_5AM - daysAgo * EpisodeRows.DAY_MS, more);
    }

    private static EpisodeRows.Snapshot ok(EpisodeRows.Episode... rows) {
        return new EpisodeRows.Snapshot("ok", NOW - 60_000, false, 0, false, new ArrayList<>(Arrays.asList(rows)));
    }

    private static List<EpisodeRows.Kind> kinds(List<EpisodeRows.Line> lines) {
        List<EpisodeRows.Kind> out = new ArrayList<>();
        for (EpisodeRows.Line l : lines) out.add(l.kind);
        return out;
    }

    @Test
    public void missingAppOrNothingPickedShowsTheSetupRow() {
        List<EpisodeRows.Line> lines = EpisodeRows.lines(null, 5, NOW, UTC);
        assertEquals(List.of(EpisodeRows.Kind.SETUP), kinds(lines));
        assertEquals("Set up new episodes", lines.get(0).text);
        EpisodeRows.Snapshot setup = new EpisodeRows.Snapshot("setup", 0, false, 0, false, null);
        assertEquals(List.of(EpisodeRows.Kind.SETUP), kinds(EpisodeRows.lines(setup, 5, NOW, UTC)));
    }

    @Test
    public void nothingNewHidesThePanel() {
        assertTrue(EpisodeRows.lines(ok(), 5, NOW, UTC).isEmpty());
    }

    @Test
    public void anEpisodeRow() {
        EpisodeRows.Line l = EpisodeRows.lines(ok(ep(3, 0, 2)), 5, NOW, UTC).get(0);
        assertEquals(EpisodeRows.Kind.EPISODE, l.kind);
        assertEquals("Series 3", l.text);
        assertEquals("S02E03 +2", l.code);
        assertEquals("today", l.day);
        assertFalse(l.soon);
        assertTrue(EpisodeRows.lines(ok(ep(4, -1, 0)), 5, NOW, UTC).get(0).soon);
    }

    @Test
    public void sixSeriesInFiveRowsEndInMore() {
        List<EpisodeRows.Line> lines = EpisodeRows.lines(
                ok(ep(1, 0, 0), ep(2, 1, 0), ep(3, 2, 0), ep(4, 3, 0), ep(5, 4, 0), ep(6, 5, 0)), 5, NOW, UTC);
        assertEquals(5, lines.size());
        assertEquals(EpisodeRows.Kind.MORE, lines.get(4).kind);
        assertEquals("+2 more", lines.get(4).text);
        List<EpisodeRows.Line> exact = EpisodeRows.lines(
                ok(ep(1, 0, 0), ep(2, 1, 0), ep(3, 2, 0), ep(4, 3, 0), ep(5, 4, 0)), 5, NOW, UTC);
        assertEquals(5, exact.size());
        assertEquals(EpisodeRows.Kind.EPISODE, exact.get(4).kind);
    }

    @Test
    public void lessRoomAboveTheDockMeansFewerRows() {
        List<EpisodeRows.Line> lines = EpisodeRows.lines(ok(ep(1, 0, 0), ep(2, 1, 0), ep(3, 2, 0)), 2, NOW, UTC);
        assertEquals(List.of(EpisodeRows.Kind.EPISODE, EpisodeRows.Kind.MORE), kinds(lines));
        assertEquals("+2 more", lines.get(1).text);
        assertEquals(5, EpisodeRows.lines(ok(ep(1, 0, 0), ep(2, 1, 0), ep(3, 2, 0), ep(4, 3, 0), ep(5, 4, 0)),
                9, NOW, UTC).size());
    }

    @Test
    public void aDayOldListSaysSo() {
        EpisodeRows.Snapshot old = new EpisodeRows.Snapshot("ok", NOW - 2 * EpisodeRows.DAY_MS - 5000, false, 0, true,
                List.of(ep(1, 0, 0), ep(2, 1, 0)));
        List<EpisodeRows.Line> lines = EpisodeRows.lines(old, 5, NOW, UTC);
        assertEquals(List.of(EpisodeRows.Kind.EPISODE, EpisodeRows.Kind.EPISODE, EpisodeRows.Kind.STALE), kinds(lines));
        assertEquals("updated 2 days ago", lines.get(2).text);
        assertFalse(lines.get(2).focusable());
        assertNull(EpisodeRows.staleText(NOW - EpisodeRows.DAY_MS + 1, NOW));
        assertEquals("updated 1 day ago", EpisodeRows.staleText(NOW - EpisodeRows.DAY_MS, NOW));
    }

    @Test
    public void anExpiredSignInKeepsTheListAndAsksToSignIn() {
        EpisodeRows.Snapshot expired = new EpisodeRows.Snapshot("expired", NOW - 60_000, false, 0, false,
                List.of(ep(1, 0, 0), ep(2, 1, 0), ep(3, 2, 0), ep(4, 3, 0), ep(5, 4, 0)));
        List<EpisodeRows.Line> lines = EpisodeRows.lines(expired, 5, NOW, UTC);
        assertEquals(5, lines.size());
        assertEquals(EpisodeRows.Kind.MORE, lines.get(3).kind);
        assertEquals("+2 more", lines.get(3).text);
        assertEquals(EpisodeRows.Kind.SIGN_IN, lines.get(4).kind);
        EpisodeRows.Snapshot empty = new EpisodeRows.Snapshot("expired", 0, false, 0, false, null);
        assertEquals(List.of(EpisodeRows.Kind.SIGN_IN), kinds(EpisodeRows.lines(empty, 5, NOW, UTC)));
    }

    @Test
    public void anOldSnapshotDropsEpisodesPastTheWindowAndRelabels() {
        List<EpisodeRows.Line> lines = EpisodeRows.lines(ok(ep(1, 31, 0), ep(2, 30, 0), ep(3, -2, 0)), 5, NOW, UTC);
        assertEquals(1, lines.size());
        assertEquals("Series 2", lines.get(0).text);
        assertEquals("Oct 24", lines.get(0).day);
    }

    @Test
    public void onlyImdbIdsReachTheLink() {
        assertEquals("stremio:///detail/series/tt101/tt101:2:1", EpisodeRows.deepLink(ep(1, 0, 0)));
        assertNull(EpisodeRows.deepLink(new EpisodeRows.Episode("tt1", "x", "tt2:1:1", 1, 1, "", 1, 0)));
        assertNull(EpisodeRows.deepLink(new EpisodeRows.Episode("tt1/../x", "x", "tt1:1:1", 1, 1, "", 1, 0)));
        assertNull(EpisodeRows.deepLink(new EpisodeRows.Episode("tt1", "x", "tt1:1:1?a=b", 1, 1, "", 1, 0)));
        assertNull(EpisodeRows.deepLink(new EpisodeRows.Episode("kitsu:1", "x", "kitsu:1:1", 1, 1, "", 1, 0)));
        // A row with a bad link is not shown at all.
        assertTrue(EpisodeRows.lines(ok(new EpisodeRows.Episode("tt1", "x", "bad", 1, 1, "", TODAY_5AM, 0)), 5, NOW,
                UTC).isEmpty());
    }

    @Test
    public void theLoaderWaitsASecondAndShowsDuringRetries() {
        EpisodeRows.Snapshot justStarted = new EpisodeRows.Snapshot("ok", 0, true, NOW - 400, false, null);
        assertFalse(EpisodeRows.spinner(justStarted, NOW));
        EpisodeRows.Snapshot slow = new EpisodeRows.Snapshot("ok", 0, true, NOW - 1500, false, null);
        assertTrue(EpisodeRows.spinner(slow, NOW));
        EpisodeRows.Snapshot dead = new EpisodeRows.Snapshot("ok", 0, true, NOW - 4 * 60_000, false, null);
        assertFalse(EpisodeRows.spinner(dead, NOW));
        EpisodeRows.Snapshot retrying = new EpisodeRows.Snapshot("ok", 0, false, 0, true, null);
        assertTrue(EpisodeRows.spinner(retrying, NOW));
        assertFalse(EpisodeRows.spinner(null, NOW));
    }

    @Test
    public void dayLabelsAndTitleLine() {
        assertEquals("yesterday", EpisodeRows.dayLabel(TODAY_5AM - EpisodeRows.DAY_MS, NOW, UTC));
        assertEquals("tomorrow", EpisodeRows.dayLabel(TODAY_5AM + EpisodeRows.DAY_MS, NOW, UTC));
        assertEquals("Fri", EpisodeRows.dayLabel(TODAY_5AM - 3 * EpisodeRows.DAY_MS, NOW, UTC));
        assertEquals("Nov 16", EpisodeRows.dayLabel(TODAY_5AM - 7 * EpisodeRows.DAY_MS, NOW, UTC));
        assertEquals("S02E03 · Title 3", EpisodeRows.subtitle(ep(3, 0, 0)));
        assertEquals("S01E01", EpisodeRows.subtitle(new EpisodeRows.Episode("tt1", "x", "tt1:1:1", 1, 1, "", 1, 0)));
    }
}
