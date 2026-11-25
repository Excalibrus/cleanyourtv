package local.dailywallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * The picture on disk and what is known about it, in this app's private storage. Today's picture and the one
 * before it are kept (the launcher may still be copying the old one); every other file goes.
 */
final class Store {
    private static final String ID = "id";
    private static final String PREVIOUS = "previous";
    private static final String CAPTION = "caption";
    private static final String DIM = "dim";
    private static final String START = "start";
    private static final String CHECKED = "checked";
    private static final String NEXT_CHECK = "nextCheck";
    private static final String FAILURES = "failures";
    private static final String LAST_ERROR = "lastError";
    private static final String RUNNING = "running";
    private static final String RUNNING_SINCE = "runningSince";

    private static Store instance;
    private final SharedPreferences prefs;
    private final File dir;

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private Store(Context c) {
        prefs = c.getSharedPreferences("store", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "pictures");
    }

    SharedPreferences prefs() {
        return prefs;
    }

    String id() {
        return prefs.getString(ID, null);
    }

    String previousId() {
        return prefs.getString(PREVIOUS, null);
    }

    String caption() {
        return prefs.getString(CAPTION, "");
    }

    int dim() {
        return prefs.getInt(DIM, Brightness.MIN_DIM);
    }

    long checked() {
        return prefs.getLong(CHECKED, 0);
    }

    long nextCheck() {
        return prefs.getLong(NEXT_CHECK, 0);
    }

    int failures() {
        return prefs.getInt(FAILURES, 0);
    }

    String lastError() {
        return prefs.getString(LAST_ERROR, null);
    }

    /** Where the picture with this id is kept, or null for an id that is not a plain file name. */
    File file(String id) {
        return Bing.validId(id) ? new File(dir, id + ".jpg") : null;
    }

    /** Today's picture, or null before the first one. */
    File current() {
        File f = file(id());
        return f != null && f.isFile() && f.length() > 0 ? f : null;
    }

    File tempFile() {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "download.tmp");
    }

    Schedule.State state() {
        Schedule.State s = new Schedule.State();
        s.hasPicture = current() != null;
        s.nextCheck = nextCheck();
        s.running = prefs.getBoolean(RUNNING, false);
        s.runningSince = prefs.getLong(RUNNING_SINCE, 0);
        return s;
    }

    void startAttempt(long now) {
        prefs.edit().putBoolean(RUNNING, true).putLong(RUNNING_SINCE, now).commit();
    }

    void stopRunning() {
        prefs.edit().putBoolean(RUNNING, false).commit();
    }

    /** A new picture is in place under its id's file name. */
    void saved(Bing.Picture p, int dim, long now, long nextCheck) {
        String old = id();
        String previous = old != null && !old.equals(p.id) ? old : previousId();
        prefs.edit().putString(ID, p.id).putString(PREVIOUS, previous).putString(CAPTION, p.caption).putInt(DIM, dim)
                .putLong(START, p.start).putLong(CHECKED, now).putLong(NEXT_CHECK, nextCheck).putInt(FAILURES, 0)
                .remove(LAST_ERROR).commit();
        cleanUp();
    }

    /** Bing still has the picture already here. */
    void unchanged(long now, long nextCheck) {
        prefs.edit().putLong(CHECKED, now).putLong(NEXT_CHECK, nextCheck).putInt(FAILURES, 0).remove(LAST_ERROR)
                .commit();
    }

    void failed(String error, long nextCheck) {
        prefs.edit().putInt(FAILURES, failures() + 1).putLong(NEXT_CHECK, nextCheck).putString(LAST_ERROR, error)
                .commit();
    }

    /** Deletes every file but today's and the previous picture. */
    private void cleanUp() {
        File[] files = dir.listFiles();
        if (files == null) return;
        File keep = file(id());
        File keepToo = file(previousId());
        for (File f : files) {
            if (!f.equals(keep) && !f.equals(keepToo)) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }
}
