package local.newepisodes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stremio's account API, its link login, and Cinemeta. The calls block: run them off the UI thread. The parsers
 * are static and take the response body, so the JVM tests feed them recorded answers.
 */
final class Api {
    static final String ACCOUNT = "https://api.strem.io/api/";
    static final String LINK = "https://link.stremio.com/api/v2/";
    static final String CINEMETA = "https://v3-cinemeta.strem.io/meta/series/";
    /** The only ids Cinemeta answers for; a series from another catalogue (kitsu:, tmdb:) is left out. */
    static final Pattern IMDB_ID = Pattern.compile("^tt\\d{1,12}$");
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private Api() {
    }

    /** The account no longer accepts the sign-in: it was signed out elsewhere, or the session expired. */
    static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    /** A link-login code: shown on the TV, approved on the person's phone. */
    static final class LinkCode {
        final String code;
        final String link;
        final String qr;

        LinkCode(String code, String link, String qr) {
            this.code = code;
            this.link = link;
            this.qr = qr;
        }
    }

    /** A series in the person's Stremio library. */
    static final class LibraryEntry {
        final String id;
        final String name;
        /** {@code state.watched}, or null. */
        final String watched;

        LibraryEntry(String id, String name, String watched) {
            this.id = id;
            this.name = name;
            this.watched = watched;
        }
    }

    // ---- Calls ----

    static LinkCode createLink() throws IOException {
        return parseCreate(get(LINK + "create?type=Create"));
    }

    /** The auth key once the code is approved, null while it is still waiting. */
    static String readLink(String code) throws IOException {
        return parseRead(get(LINK + "read?type=Read&code=" + URLEncoder.encode(code, "UTF-8")));
    }

    static List<LibraryEntry> library(String authKey) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("authKey", authKey);
            body.put("collection", "libraryItem");
            body.put("ids", new JSONArray());
            body.put("all", true);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return parseLibrary(post(ACCOUNT + "datastoreGet", body.toString()));
    }

    /** Ends the session on Stremio's side. Best effort: a failure changes nothing on the TV. */
    static void logout(String authKey) {
        try {
            JSONObject body = new JSONObject();
            body.put("authKey", authKey);
            post(ACCOUNT + "logout", body.toString());
        } catch (IOException | JSONException ignored) {
            // Signed out on the TV either way.
        }
    }

    /** The series' episodes; empty when Cinemeta does not know it. */
    static List<Video> videos(String imdbId) throws IOException {
        if (!IMDB_ID.matcher(imdbId).matches()) return new ArrayList<>();
        Response r = request("GET", CINEMETA + imdbId + ".json", null);
        if (r.code == 404) return new ArrayList<>();
        if (r.code != 200) throw new IOException("Cinemeta HTTP " + r.code);
        return parseVideos(r.body);
    }

    /** A small image, such as the login QR code. */
    static byte[] bytes(String url) throws IOException {
        Response r = request("GET", url, null);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return r.raw;
    }

    // ---- Parsers ----

    static LinkCode parseCreate(String body) throws IOException {
        JSONObject result = result(body);
        String code = result.optString("code", "");
        if (code.isEmpty()) throw new IOException("no code in the answer");
        String link = result.optString("link", "https://link.stremio.com/" + code);
        String qr = result.optString("qrcode", "");
        // Only Stremio's own QR image is fetched.
        if (!qr.startsWith("https://link.stremio.com/")) qr = "";
        return new LinkCode(code, link, qr);
    }

    static String parseRead(String body) throws IOException {
        JSONObject json = object(body);
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            // 101: no one has approved the code yet (StreamVault, rivulet).
            if (error.optInt("code") == 101) return null;
            throw new IOException(error.optString("message", "link error"));
        }
        JSONObject result = json.optJSONObject("result");
        if (result == null) return null;
        String key = result.optString("authKey", "");
        return key.isEmpty() ? null : key;
    }

    static List<LibraryEntry> parseLibrary(String body) throws IOException {
        JSONObject json = object(body);
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "");
            if (error.optInt("code") == 1 || message.toLowerCase().contains("session")) throw new AuthException(message);
            throw new IOException("Stremio: " + message);
        }
        JSONArray items = json.optJSONArray("result");
        if (items == null) throw new IOException("no library in the answer");
        List<LibraryEntry> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"series".equals(item.optString("type"))) continue;
            if (item.optBoolean("removed") || item.optBoolean("temp")) continue;
            String id = item.optString("_id", "");
            if (!IMDB_ID.matcher(id).matches()) continue;
            String name = item.optString("name", "").trim();
            if (name.isEmpty()) name = id;
            JSONObject state = item.optJSONObject("state");
            String watched = state == null || state.isNull("watched") ? null : state.optString("watched", null);
            out.add(new LibraryEntry(id, name, watched));
        }
        return out;
    }

    static List<Video> parseVideos(String body) throws IOException {
        JSONObject meta = object(body).optJSONObject("meta");
        List<Video> out = new ArrayList<>();
        JSONArray videos = meta == null ? null : meta.optJSONArray("videos");
        if (videos == null) return out;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if (v == null) continue;
            String id = v.optString("id", "");
            if (id.isEmpty()) continue;
            int season = v.has("season") && !v.isNull("season") ? v.optInt("season", -1) : -1;
            int episode = v.optInt("episode", v.optInt("number", 0));
            String title = v.optString("name", "");
            if (title.isEmpty() || "null".equals(title)) title = v.optString("title", "");
            if ("null".equals(title)) title = "";
            long released = time(v.optString("released", ""));
            if (released == 0) released = time(v.optString("firstAired", ""));
            out.add(new Video(id, season, episode, title, released));
        }
        return out;
    }

    /** ISO-8601 UTC as Cinemeta writes it ("2008-01-21T05:00:00.000Z"), 0 when absent or unreadable. */
    static long time(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static JSONObject object(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("not JSON", e);
        }
    }

    private static JSONObject result(String body) throws IOException {
        JSONObject json = object(body);
        JSONObject error = json.optJSONObject("error");
        if (error != null) throw new IOException(error.optString("message", "error"));
        JSONObject result = json.optJSONObject("result");
        if (result == null) throw new IOException("no result in the answer");
        return result;
    }

    // ---- HTTP ----

    private static final class Response {
        final int code;
        final byte[] raw;
        final String body;

        Response(int code, byte[] raw) {
            this.code = code;
            this.raw = raw;
            this.body = new String(raw, StandardCharsets.UTF_8);
        }
    }

    private static String get(String url) throws IOException {
        Response r = request("GET", url, null);
        check(r);
        return r.body;
    }

    private static String post(String url, String json) throws IOException {
        Response r = request("POST", url, json);
        check(r);
        return r.body;
    }

    private static void check(Response r) throws IOException {
        if (r.code == 401 || r.code == 403) throw new AuthException("HTTP " + r.code);
        // Stremio answers its own errors as JSON, sometimes with a 4xx/5xx status: let the parser read them.
        if (r.code != 200 && !r.body.trim().startsWith("{")) throw new IOException("HTTP " + r.code);
    }

    private static Response request(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "NewEpisodes/0.1 (Android TV)");
            c.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? new byte[0] : readAll(in));
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("answer too large");
            }
            return out.toByteArray();
        }
    }
}
