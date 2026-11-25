package local.dailywallpaper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain GETs to www.bing.com and nowhere else. */
final class Http {
    /** The feed is about 1 KB; anything this large is not it. */
    private static final int MAX_TEXT = 256 * 1024;

    private Http() {
    }

    static String text(String url) throws IOException {
        HttpURLConnection c = open(url, "application/json");
        try {
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, MAX_TEXT);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Saves the answer to {@code to}; over {@code max} bytes, or not an image, it fails and leaves no file. */
    static void download(String url, File to, int max) throws IOException {
        HttpURLConnection c = open(url, "image/*");
        try {
            String type = c.getContentType();
            if (type != null && !type.startsWith("image/")) throw new IOException("not a picture");
            if (c.getContentLengthLong() > max) throw new IOException("picture too large");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                copy(in, out, max);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                to.delete();
                throw e;
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        if (!url.startsWith(Bing.HOST + "/")) throw new IOException("not a Bing address");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        // A redirect could lead anywhere; Bing answers both addresses directly.
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "DailyWallpaper/0.1 (Android TV)");
        c.setRequestProperty("Accept", accept);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static void copy(InputStream in, OutputStream out, int max) throws IOException {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("answer too large");
            out.write(buf, 0, n);
        }
    }
}
