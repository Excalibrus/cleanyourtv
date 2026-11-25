package local.newepisodes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the add-on keeps, in its private storage: the Stremio auth key, the picked series, the library and
 * Cinemeta caches, the last good rows and the refresh state. Nothing here is backed up (allowBackup is off).
 */
final class Store {
    private static final String AUTH = "authKey";
    private static final String EXPIRED = "expired";
    private static final String FOLLOWED = "followed";
    private static final String LIBRARY = "library";
    private static final String ROWS = "rows";
    private static final String LAST_SUCCESS = "lastSuccess";
    private static final String LAST_ATTEMPT = "lastAttempt";
    private static final String FAILURES = "failures";
    private static final String NEXT_RETRY = "nextRetry";
    private static final String RUNNING = "running";
    private static final String RUNNING_SINCE = "runningSince";
    private static final String LAST_ERROR = "lastError";
    /** Android's boot count at the last try, and the uptime then for a TV without a boot count. */
    private static final String ATTEMPT_BOOT = "attemptBoot";
    private static final String ATTEMPT_UPTIME = "attemptUptime";

    private static Store instance;

    private final SharedPreferences prefs;
    private final File metaDir;
    private final ContentResolver resolver;

    private Store(Context c) {
        prefs = c.getSharedPreferences("store", Context.MODE_PRIVATE);
        metaDir = new File(c.getFilesDir(), "meta");
        resolver = c.getContentResolver();
    }

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    SharedPreferences prefs() {
        return prefs;
    }

    // ---- Sign-in ----

    String authKey() {
        return prefs.getString(AUTH, null);
    }

    boolean expired() {
        return prefs.getBoolean(EXPIRED, false);
    }

    void signIn(String key) {
        prefs.edit().putString(AUTH, key).putBoolean(EXPIRED, false).putInt(FAILURES, 0).putLong(LAST_ATTEMPT, 0)
                .putLong(NEXT_RETRY, 0).remove(LAST_ERROR).commit();
    }

    /** Signed out on purpose: the list goes too, the picks stay for the next sign-in. */
    void signOut() {
        prefs.edit().remove(AUTH).putBoolean(EXPIRED, false).remove(LIBRARY).remove(ROWS).putLong(LAST_SUCCESS, 0)
                .putInt(FAILURES, 0).putLong(NEXT_RETRY, 0).putBoolean(RUNNING, false).remove(LAST_ERROR).commit();
        deleteMeta(Collections.emptySet());
    }

    /** Stremio refused the key: drop it, keep the last list, and say so until the person signs in again. */
    void markExpired() {
        prefs.edit().remove(AUTH).putBoolean(EXPIRED, true).putInt(FAILURES, 0).putLong(NEXT_RETRY, 0)
                .putBoolean(RUNNING, false).commit();
    }

    // ---- Picks and library ----

    Set<String> followed() {
        return new HashSet<>(prefs.getStringSet(FOLLOWED, Collections.emptySet()));
    }

    void setFollowed(Set<String> ids) {
        prefs.edit().putStringSet(FOLLOWED, new HashSet<>(ids)).commit();
    }

    /** The library's series as last read, or null before the first read. */
    List<Api.LibraryEntry> library() {
        String raw = prefs.getString(LIBRARY, null);
        if (raw == null) return null;
        List<Api.LibraryEntry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Api.LibraryEntry(o.getString("id"), o.getString("name"),
                        o.isNull("watched") ? null : o.optString("watched", null)));
            }
        } catch (JSONException e) {
            return null;
        }
        return out;
    }

    void saveLibrary(List<Api.LibraryEntry> entries) {
        JSONArray a = new JSONArray();
        try {
            for (Api.LibraryEntry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("name", e.name);
                o.put("watched", e.watched == null ? JSONObject.NULL : e.watched);
                a.put(o);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(LIBRARY, a.toString()).commit();
    }

    /** Picked series that are still in the library, in library order. */
    List<Api.LibraryEntry> followedInLibrary() {
        List<Api.LibraryEntry> lib = library();
        List<Api.LibraryEntry> out = new ArrayList<>();
        if (lib == null) return out;
        Set<String> picked = followed();
        for (Api.LibraryEntry e : lib) if (picked.contains(e.id)) out.add(e);
        return out;
    }

    // ---- Cinemeta cache: one small file per followed series ----

    private File metaFile(String id) {
        return new File(metaDir, id + ".json");
    }

    /** The cached episode list, or null. */
    List<Video> videos(String id) {
        JSONObject o = readMeta(id);
        if (o == null) return null;
        List<Video> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("v");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONArray v = a.optJSONArray(i);
            if (v == null) continue;
            out.add(new Video(v.optString(0), v.optInt(1, -1), v.optInt(2), v.optString(3), v.optLong(4)));
        }
        return out;
    }

    long videosFetched(String id) {
        JSONObject o = readMeta(id);
        return o == null ? 0 : o.optLong("t");
    }

    void saveVideos(String id, List<Video> videos, long now) {
        try {
            JSONArray a = new JSONArray();
            for (Video v : videos) {
                JSONArray row = new JSONArray();
                row.put(v.id).put(v.season).put(v.episode).put(v.title).put(v.released);
                a.put(row);
            }
            JSONObject o = new JSONObject();
            o.put("t", now);
            o.put("v", a);
            if (!metaDir.isDirectory() && !metaDir.mkdirs()) return;
            File tmp = new File(metaDir, id + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(metaFile(id))) tmp.delete();
        } catch (JSONException | IOException ignored) {
            // The next refresh fetches it again.
        }
    }

    /** Drops the cached lists of series no longer picked. */
    void deleteMeta(Set<String> keep) {
        File[] files = metaDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : "";
            if (!keep.contains(id)) f.delete();
        }
    }

    private JSONObject readMeta(String id) {
        File f = metaFile(id);
        if (!f.isFile() || f.length() > 4 * 1024 * 1024) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---- Refresh state ----

    RefreshPolicy.State state() {
        RefreshPolicy.State s = new RefreshPolicy.State();
        s.signedIn = authKey() != null;
        s.attemptedThisBoot = attemptedThisBoot();
        // A run marked in progress before the TV restarted is over.
        s.running = prefs.getBoolean(RUNNING, false) && s.attemptedThisBoot;
        s.runningSince = prefs.getLong(RUNNING_SINCE, 0);
        s.lastAttempt = prefs.getLong(LAST_ATTEMPT, 0);
        s.failures = prefs.getInt(FAILURES, 0);
        return s;
    }

    long lastSuccess() {
        return prefs.getLong(LAST_SUCCESS, 0);
    }

    long nextRetry() {
        return prefs.getLong(NEXT_RETRY, 0);
    }

    String lastError() {
        return prefs.getString(LAST_ERROR, null);
    }

    void startAttempt(long now) {
        prefs.edit().putBoolean(RUNNING, true).putLong(RUNNING_SINCE, now).putLong(LAST_ATTEMPT, now)
                .putInt(ATTEMPT_BOOT, bootCount()).putLong(ATTEMPT_UPTIME, SystemClock.elapsedRealtime()).commit();
    }

    /**
     * Whether the last try was made since the TV last started. By Android's boot count, not the clock, which a TV
     * often corrects a minute after it starts.
     */
    private boolean attemptedThisBoot() {
        int boot = bootCount();
        int then = prefs.getInt(ATTEMPT_BOOT, -1);
        if (boot >= 0 && then >= 0) return boot == then;
        // No boot count: a try made since the start has an uptime no larger than now.
        long uptime = prefs.getLong(ATTEMPT_UPTIME, -1);
        return uptime >= 0 && uptime <= SystemClock.elapsedRealtime();
    }

    private int bootCount() {
        return Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
    }

    void succeeded(String rowsJson, long now) {
        prefs.edit().putString(ROWS, rowsJson).putLong(LAST_SUCCESS, now).putInt(FAILURES, 0).putLong(NEXT_RETRY, 0)
                .putBoolean(RUNNING, false).remove(LAST_ERROR).commit();
    }

    /** Records a failure and returns how many there have been in a row. */
    int failed(String error, long nextRetryAt) {
        int failures = prefs.getInt(FAILURES, 0) + 1;
        prefs.edit().putInt(FAILURES, failures).putLong(NEXT_RETRY, nextRetryAt).putBoolean(RUNNING, false)
                .putString(LAST_ERROR, error).commit();
        return failures;
    }

    void stopRunning() {
        prefs.edit().putBoolean(RUNNING, false).commit();
    }

    // ---- What the launcher reads ----

    /** The last good rows, without series that were unpicked since. */
    JSONArray rows() {
        JSONArray out = new JSONArray();
        String raw = prefs.getString(ROWS, null);
        if (raw == null) return out;
        Set<String> picked = followed();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject row = a.getJSONObject(i);
                if (picked.contains(row.optString("series"))) out.put(row);
            }
        } catch (JSONException ignored) {
            // An unreadable list shows as none.
        }
        return out;
    }

    /**
     * The panel's snapshot. {@code state} is "setup" (signed out, or nothing picked yet), "expired" (Stremio
     * refused the sign-in: the last list stays, with a prompt to sign in again) or "ok".
     */
    String snapshot(long now) {
        RefreshPolicy.State s = state();
        String st;
        if (expired()) st = "expired";
        else if (!s.signedIn || followedInLibrary().isEmpty()) st = "setup";
        else st = "ok";
        boolean running = RefreshPolicy.isRunning(s, now);
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("state", st);
            o.put("lastSuccess", lastSuccess());
            o.put("refreshing", running);
            o.put("refreshStarted", running ? s.runningSince : 0);
            o.put("retrying", s.signedIn && s.failures > 0);
            o.put("rows", "setup".equals(st) ? new JSONArray() : rows());
        } catch (JSONException ignored) {
            // Built from plain values: cannot fail.
        }
        return o.toString();
    }

    static String rowsJson(List<Episodes.Row> rows) {
        JSONArray a = new JSONArray();
        try {
            for (Episodes.Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("series", r.series);
                o.put("name", r.name);
                o.put("video", r.video.id);
                o.put("season", r.video.season);
                o.put("episode", r.video.episode);
                o.put("title", r.video.title);
                o.put("released", r.video.released);
                o.put("more", r.more);
                a.put(o);
            }
        } catch (JSONException ignored) {
            // Built from plain values: cannot fail.
        }
        return a.toString();
    }
}
