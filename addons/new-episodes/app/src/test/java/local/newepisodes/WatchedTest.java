package local.newepisodes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;

public class WatchedTest {
    private static List<String> ids(String prefix, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add(prefix + i);
        return out;
    }

    /** Builds a field the way stremio-core writes it, for the cases its own tests do not cover. */
    static String field(String anchor, boolean... watched) {
        byte[] bits = new byte[(watched.length + 7) / 8];
        for (int i = 0; i < watched.length; i++) if (watched[i]) bits[i / 8] |= (byte) (1 << (i % 8));
        Deflater d = new Deflater();
        d.setInput(bits);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return anchor + ":" + watched.length + ":" + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    public void stremioCoreVector() {
        // stremio-watched-bitfield's own test: episodes 1 to 5 of season 1 watched, 6 to 9 not.
        Watched w = Watched.parse("tt2934286:1:5:5:eJyTZwAAAEAAIA==");
        assertNotNull(w);
        boolean[] got = w.map(ids("tt2934286:1:", 1, 9));
        assertArrayEquals(new boolean[]{true, true, true, true, true, false, false, false, false}, got);
    }

    @Test
    public void newEpisodesAtTheEndAreUnwatched() {
        Watched w = Watched.parse("tt2934286:1:5:5:eJyTZwAAAEAAIA==");
        boolean[] got = w.map(ids("tt2934286:1:", 1, 12));
        assertArrayEquals(new boolean[]{true, true, true, true, true, false, false, false, false, false, false, false},
                got);
    }

    @Test
    public void anEpisodeInsertedBeforeTheAnchorShiftsTheBits() {
        List<String> list = new ArrayList<>();
        list.add("tt2934286:1:0");
        list.addAll(ids("tt2934286:1:", 1, 9));
        boolean[] got = Watched.parse("tt2934286:1:5:5:eJyTZwAAAEAAIA==").map(list);
        assertArrayEquals(new boolean[]{false, true, true, true, true, true, false, false, false, false}, got);
    }

    @Test
    public void anchorGoneMeansNothingWatched() {
        boolean[] got = Watched.parse("tt2934286:1:5:5:eJyTZwAAAEAAIA==").map(ids("tt2934286:2:", 1, 3));
        assertArrayEquals(new boolean[3], got);
    }

    @Test
    public void emptyFieldFromStremio() {
        boolean[] got = Watched.parse("undefined:1:eJwDAAAAAAE=").map(ids("tt1:1:", 1, 4));
        assertArrayEquals(new boolean[4], got);
    }

    @Test
    public void roundTripPastOneByte() {
        boolean[] watched = new boolean[13];
        watched[0] = watched[7] = watched[8] = watched[12] = true;
        String f = field("tt9:2:13", watched);
        assertArrayEquals(watched, Watched.parse(f).map(ids("tt9:2:", 1, 13)));
    }

    @Test
    public void garbageIsNull() {
        assertNull(Watched.parse(null));
        assertNull(Watched.parse(""));
        assertNull(Watched.parse("abc"));
        assertNull(Watched.parse("tt1:1:1:x:eJwDAAAAAAE="));
        assertNull(Watched.parse("tt1:1:1:5:not base64!"));
        assertNull(Watched.parse("tt1:1:1:5:AAAA"));
    }
}
