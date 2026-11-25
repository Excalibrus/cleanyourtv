package local.dailywallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class BingTest {
    /** The shape of the UK feed on 2026-11-23, cut to the fields that are read. */
    private static final String FEED = "{\"images\":[{\"startdate\":\"20261123\",\"fullstartdate\":\"202611230000\","
            + "\"enddate\":\"20261124\",\"url\":\"/th?id=OHR.FibonacciFern_EN-GB6785963710_1920x1080.jpg&rf=x.jpg\","
            + "\"urlbase\":\"/th?id=OHR.FibonacciFern_EN-GB6785963710\","
            + "\"copyright\":\"Fiddlehead fern (© Reza Saputra/Getty Images)\",\"title\":\"Geometry in nature\"}],"
            + "\"tooltips\":{}}";

    @Test
    public void theUkFeed() throws IOException {
        Bing.Picture p = Bing.parse(FEED);
        assertEquals("OHR.FibonacciFern_EN-GB6785963710", p.id);
        assertEquals("https://www.bing.com/th?id=OHR.FibonacciFern_EN-GB6785963710_1920x1080.jpg", p.url);
        assertEquals("Fiddlehead fern · © Reza Saputra/Getty Images", p.caption);
        assertEquals(Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(), p.start);
    }

    @Test
    public void onlyPlainPicturesOnBing() {
        for (String base : new String[]{"//evil.example/x", "/th?id=../../x", "/th?id=.hidden", "/th?id=a/b",
                "/th?id=a b", "/th?id=", "/az/hprichbg/rb/x.jpg", "/th?id=x&rf=evil"}) {
            String json = FEED.replace("/th?id=OHR.FibonacciFern_EN-GB6785963710\"", base + "\"");
            try {
                Bing.parse(json);
                fail("accepted " + base);
            } catch (IOException expected) {
                // refused
            }
        }
    }

    @Test
    public void brokenAnswersAreFailures() {
        for (String json : new String[]{"", "<html>", "{}", "{\"images\":[]}",
                FEED.replace("202611230000", "2026-11-23")}) {
            try {
                Bing.parse(json);
                fail("accepted " + json);
            } catch (IOException expected) {
                // refused
            }
        }
    }

    @Test
    public void ids() {
        assertTrue(Bing.validId("OHR.FibonacciFern_EN-GB6785963710"));
        assertFalse(Bing.validId(null));
        assertFalse(Bing.validId(".."));
        assertFalse(Bing.validId("a/b"));
        assertFalse(Bing.validId("x".repeat(121)));
    }

    @Test
    public void captions() {
        assertEquals("Lake Bled · © Someone/Getty Images", Bing.caption("Lake Bled  (© Someone/Getty Images)"));
        assertEquals("Just a title", Bing.caption("Just a title"));
        assertEquals("© Only credit", Bing.caption("(© Only credit)"));
        assertEquals("A (small) bridge · © X", Bing.caption("A (small) bridge (© X)"));
        assertEquals("No control chars", Bing.caption("No\ncontrol\u0007 chars‎"));
        assertEquals("", Bing.caption(null));
        String longOne = Bing.caption("y".repeat(500));
        assertEquals(Bing.MAX_CAPTION, longOne.length());
        assertTrue(longOne.endsWith("…"));
    }

    @Test
    public void startTimes() {
        assertEquals(Instant.parse("2026-07-14T23:00:00Z").toEpochMilli(), Bing.startTime("202607142300"));
        assertEquals(0, Bing.startTime(""));
        assertEquals(0, Bing.startTime("20261123"));
        assertEquals(0, Bing.startTime("2026112300ab"));
        assertEquals(0, Bing.startTime(null));
    }
}
