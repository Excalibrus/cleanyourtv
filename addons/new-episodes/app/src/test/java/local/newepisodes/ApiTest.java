package local.newepisodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/** The parsers against answers shaped like the recorded ones. */
public class ApiTest {
    @Test
    public void linkCode() throws IOException {
        Api.LinkCode c = Api.parseCreate("{\"result\":{\"success\":true,\"code\":\"46P9\","
                + "\"link\":\"https://link.stremio.com/46P9\",\"qrcode\":\"https://link.stremio.com/qr?data=x\"}}");
        assertEquals("46P9", c.code);
        assertEquals("https://link.stremio.com/46P9", c.link);
        assertEquals("https://link.stremio.com/qr?data=x", c.qr);
    }

    @Test
    public void aQrFromAnotherHostIsNotFetched() throws IOException {
        Api.LinkCode c = Api.parseCreate("{\"result\":{\"code\":\"A1\",\"qrcode\":\"http://example.com/qr\"}}");
        assertEquals("", c.qr);
    }

    @Test
    public void linkRead() throws IOException {
        assertNull(Api.parseRead("{\"error\":{\"code\":101,\"message\":\"Invalid or expired token\"}}"));
        assertEquals("abc", Api.parseRead("{\"result\":{\"success\":true,\"authKey\":\"abc\"}}"));
        assertThrows(IOException.class, () -> Api.parseRead("{\"error\":{\"code\":7,\"message\":\"boom\"}}"));
    }

    @Test
    public void libraryKeepsSeriesInTheLibraryOnly() throws IOException {
        List<Api.LibraryEntry> lib = Api.parseLibrary("{\"result\":["
                + "{\"_id\":\"tt1\",\"name\":\"Kept\",\"type\":\"series\",\"removed\":false,\"temp\":false,"
                + "\"state\":{\"watched\":\"tt1:1:1:1:eJwDAAAAAAE=\",\"video_id\":\"tt1:1:1\"}},"
                + "{\"_id\":\"tt2\",\"name\":\"Movie\",\"type\":\"movie\",\"removed\":false,\"temp\":false},"
                + "{\"_id\":\"tt3\",\"name\":\"Removed\",\"type\":\"series\",\"removed\":true,\"temp\":false},"
                + "{\"_id\":\"tt4\",\"name\":\"Temp\",\"type\":\"series\",\"removed\":false,\"temp\":true},"
                + "{\"_id\":\"kitsu:5\",\"name\":\"Anime\",\"type\":\"series\",\"removed\":false,\"temp\":false},"
                + "{\"_id\":\"tt6\",\"name\":\"Fresh\",\"type\":\"series\",\"state\":{\"watched\":null}}]}");
        assertEquals(2, lib.size());
        assertEquals("tt1", lib.get(0).id);
        assertEquals("Kept", lib.get(0).name);
        assertEquals("tt1:1:1:1:eJwDAAAAAAE=", lib.get(0).watched);
        assertEquals("tt6", lib.get(1).id);
        assertNull(lib.get(1).watched);
    }

    @Test
    public void anEndedSessionIsAnAuthError() {
        assertThrows(Api.AuthException.class,
                () -> Api.parseLibrary("{\"error\":{\"code\":1,\"message\":\"Session does not exist\"}}"));
        assertThrows(IOException.class, () -> Api.parseLibrary("{\"error\":{\"code\":12,\"message\":\"busy\"}}"));
        assertThrows(IOException.class, () -> Api.parseLibrary("<html>"));
    }

    @Test
    public void cinemetaVideos() throws IOException {
        List<Video> v = Api.parseVideos("{\"meta\":{\"id\":\"tt0903747\",\"videos\":["
                + "{\"id\":\"tt0903747:1:1\",\"season\":1,\"episode\":1,\"name\":\"Pilot\","
                + "\"released\":\"2008-01-21T05:00:00.000Z\"},"
                + "{\"id\":\"tt0903747:0:1\",\"season\":0,\"number\":1,\"title\":\"Extra\",\"released\":null},"
                + "{\"id\":\"tt0903747:2:1\",\"season\":2,\"episode\":1,\"firstAired\":\"2009-03-08T05:00:00.000Z\"}]}}");
        assertEquals(3, v.size());
        assertEquals("tt0903747:1:1", v.get(0).id);
        assertEquals(1, v.get(0).season);
        assertEquals("Pilot", v.get(0).title);
        assertEquals(1200891600000L, v.get(0).released);
        assertEquals(0, v.get(1).season);
        assertEquals(1, v.get(1).episode);
        assertEquals("Extra", v.get(1).title);
        assertEquals(0, v.get(1).released);
        assertEquals(1236488400000L, v.get(2).released);
    }

    @Test
    public void unknownSeriesHasNoVideos() throws IOException {
        assertEquals(0, Api.parseVideos("{\"meta\":null}").size());
        assertEquals(0, Api.parseVideos("{}").size());
    }
}
