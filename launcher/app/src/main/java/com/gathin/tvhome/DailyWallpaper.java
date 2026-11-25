package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * daily_wallpaper: the Daily Wallpaper app's picture of the day, copied into this launcher's own storage, so Home
 * decodes it like a theme picture at start (and the clock screensaver can show it) without asking the app first.
 * The app downloads it; this launcher has no internet. The copy is refreshed from the app's provider on a worker
 * thread whenever Home comes back or the app says it has a new one.
 */
final class DailyWallpaper {
    static final String AUTHORITY = "local.dailywallpaper.picture";
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_CAPTION = 200;
    private static final String PREFS = "daily_wallpaper";
    private static final String DIR = "daily_wallpaper";
    /** The app's ids double as file names: letters, digits, dot, dash, underscore; not starting with a dot. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}");

    /** A picture on this TV: its file, how dark Home draws it (percent) and its caption. */
    static final class Picture {
        final String id;
        final File file;
        final int dim;
        final String caption;

        Picture(String id, File file, int dim, String caption) {
            this.id = id;
            this.file = file;
            this.dim = dim;
            this.caption = caption;
        }
    }

    private DailyWallpaper() {
    }

    /** The app's state; it notifies this uri when its picture changes. A method, so unit tests can load the class. */
    static Uri state() {
        return Uri.parse("content://" + AUTHORITY + "/state");
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** wallpaper_dim's range. */
    static int clampDim(int dim) {
        return Math.max(0, Math.min(90, dim));
    }

    /** One line of plain text: control characters become spaces, and a long one ends in "…". */
    static String cleanCaption(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_CAPTION + 1));
        for (int i = 0; i < s.length() && out.length() <= MAX_CAPTION; i++) {
            char ch = s.charAt(i);
            out.append(Character.isISOControl(ch) ? ' ' : ch);
        }
        String line = out.toString().trim();
        return line.length() > MAX_CAPTION ? line.substring(0, MAX_CAPTION - 1).trim() + "…" : line;
    }

    /** The copy on this TV, or null before the first one. */
    static Picture local(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (!validId(id)) return null;
        File f = new File(new File(c.getFilesDir(), DIR), id + ".jpg");
        if (!f.isFile() || f.length() == 0) return null;
        return new Picture(id, f, clampDim(p.getInt("dim", 0)), cleanCaption(p.getString("caption", "")));
    }

    /**
     * Reads the app's picture and copies it in when it is new. With a {@code reason} ("home") the app also checks Bing
     * if a check is due. Worker thread only. Returns the picture when it or its caption or dim changed, else null;
     * also null when the app is missing or not signed with this launcher's key. commit(), not apply(): this is the
     * worker thread, and Home reads the prefs at its next start.
     */
    @SuppressLint("ApplySharedPref")
    static Picture sync(Context c, String reason) {
        Bundle state;
        try {
            state = c.getContentResolver().call(state(),reason == null ? "state" : "refresh", reason, null);
        } catch (RuntimeException e) {
            return null;   // not installed (unknown authority), or another key (SecurityException)
        }
        if (state == null) return null;
        String id = state.getString("id");
        if (!validId(id)) return null;   // no picture yet
        int dim = clampDim(state.getInt("dim", 0));
        String caption = cleanCaption(state.getString("caption", ""));
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Picture here = local(c);
        if (here != null && here.id.equals(id)) {
            if (here.dim == dim && here.caption.equals(caption)) return null;
            prefs.edit().putInt("dim", dim).putString("caption", caption).commit();
            return new Picture(id, here.file, dim, caption);
        }
        File dir = new File(c.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, "copy.tmp");
        File target = new File(dir, id + ".jpg");
        try (InputStream in = c.getContentResolver().openInputStream(
                Uri.parse("content://" + AUTHORITY + "/picture/" + id));
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("no picture");
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("picture too large");
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        prefs.edit().putString("id", id).putInt("dim", dim).putString("caption", caption).commit();
        // The picture before stays until the next change: Home may still show it while the new one fades in.
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.equals(target) && (here == null || !f.equals(here.file))) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        return new Picture(id, target, dim, caption);
    }
}
