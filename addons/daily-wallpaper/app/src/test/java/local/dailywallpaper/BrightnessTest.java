package local.dailywallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class BrightnessTest {
    private static int[] fill(int argb) {
        int[] px = new int[64];
        Arrays.fill(px, argb);
        return px;
    }

    @Test
    public void meanBrightness() {
        assertEquals(0.0, Brightness.mean(fill(0xFF000000)), 1e-9);
        assertEquals(1.0, Brightness.mean(fill(0xFFFFFFFF)), 1e-9);
        // Green counts most, blue least, as the eye sees them.
        assertEquals(0.7152, Brightness.mean(fill(0xFF00FF00)), 1e-4);
        assertEquals(0.0722, Brightness.mean(fill(0xFF0000FF)), 1e-4);
        assertEquals(0.0, Brightness.mean(new int[0]), 1e-9);
        assertEquals(0.0, Brightness.mean(null), 1e-9);
    }

    @Test
    public void darkPicturesGetLittleDimBrightOnesMore() {
        assertEquals(20, Brightness.dimFor(0.0));
        assertEquals(20, Brightness.dimFor(0.25));
        assertEquals(38, Brightness.dimFor(0.45));
        assertEquals(55, Brightness.dimFor(0.65));
        assertEquals(55, Brightness.dimFor(1.0));
        assertEquals(20, Brightness.dimFor(Double.NaN));
    }
}
