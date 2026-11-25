package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DailyWallpaperTest {
    @Test
    public void idsAreSafeFileNames() {
        assertTrue(DailyWallpaper.validId("OHR.FiddleheadFern_EN-GB1234567890"));
        assertTrue(DailyWallpaper.validId("a"));
        assertFalse(DailyWallpaper.validId(null));
        assertFalse(DailyWallpaper.validId(""));
        assertFalse(DailyWallpaper.validId(".hidden"));
        assertFalse(DailyWallpaper.validId("../prefs"));
        assertFalse(DailyWallpaper.validId("a/b"));
        assertFalse(DailyWallpaper.validId("a b"));
        StringBuilder long120 = new StringBuilder();
        for (int i = 0; i < 120; i++) long120.append('x');
        assertTrue(DailyWallpaper.validId(long120.toString()));
        assertFalse(DailyWallpaper.validId(long120 + "x"));
    }

    @Test
    public void dimStaysInWallpaperDimRange() {
        assertEquals(0, DailyWallpaper.clampDim(-5));
        assertEquals(35, DailyWallpaper.clampDim(35));
        assertEquals(90, DailyWallpaper.clampDim(400));
    }

    @Test
    public void captionIsOneShortLine() {
        assertEquals("", DailyWallpaper.cleanCaption(null));
        assertEquals("Fern · © Someone", DailyWallpaper.cleanCaption("  Fern · © Someone\n"));
        assertEquals("a b", DailyWallpaper.cleanCaption("a\nb"));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 300; i++) s.append('w');
        String line = DailyWallpaper.cleanCaption(s.toString());
        assertEquals(DailyWallpaper.MAX_CAPTION, line.length());
        assertTrue(line.endsWith("…"));
        s.setLength(DailyWallpaper.MAX_CAPTION);
        assertEquals(s.toString(), DailyWallpaper.cleanCaption(s.toString()));
    }
}
