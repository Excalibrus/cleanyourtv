package local.newepisodes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * What the launcher's panel reads. {@code call("snapshot")} answers the list as JSON in the "json" extra;
 * {@code call("refresh", "home" | "boot" | "manual")} also asks for a refresh, which runs only when
 * {@link RefreshPolicy} allows. A change notifies {@link #SNAPSHOT}.
 *
 * <p>Only apps signed with this app's key may call it (the launcher built on the same PC), and the ADB shell for
 * testing. The answer holds series and episode names, never the Stremio sign-in.
 */
public class PanelProvider extends ContentProvider {
    static final String AUTHORITY = "local.newepisodes.panel";
    static final Uri SNAPSHOT = Uri.parse("content://" + AUTHORITY + "/snapshot");
    private static final int SHELL_UID = 2000;

    static void notifyChanged(Context c) {
        c.getContentResolver().notifyChange(SNAPSHOT, null, false);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        if (c == null) return null;
        checkCaller(c);
        if ("refresh".equals(method)) {
            long token = Binder.clearCallingIdentity();
            try {
                Jobs.request(c, RefreshPolicy.Reason.from(arg));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else if (!"snapshot".equals(method)) {
            return null;
        }
        Bundle out = new Bundle();
        out.putString("json", Store.get(c).snapshot(System.currentTimeMillis()));
        return out;
    }

    private static void checkCaller(Context c) {
        int uid = Binder.getCallingUid();
        int me = Process.myUid();
        if (uid == me || uid == SHELL_UID) return;
        if (c.getPackageManager().checkSignatures(uid, me) == PackageManager.SIGNATURE_MATCH) return;
        throw new SecurityException("New Episodes answers only apps signed with its own key");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
