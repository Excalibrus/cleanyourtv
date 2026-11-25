package local.dailywallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * What the launcher reads. {@code call("state")} answers today's picture as "id", "caption" and "dim" (none of them
 * before the first picture); {@code call("refresh", "home" | "boot" | "manual")} also asks for a check, which runs
 * only when {@link Schedule} allows. {@code content://local.dailywallpaper.picture/picture/<id>} opens that picture
 * for reading. A change notifies {@link #STATE}.
 *
 * <p>Only apps signed with this app's key may call it (the launcher built on the same PC), and the ADB shell for
 * testing.
 */
public class PictureProvider extends ContentProvider {
    static final String AUTHORITY = "local.dailywallpaper.picture";
    static final Uri STATE = Uri.parse("content://" + AUTHORITY + "/state");
    private static final int SHELL_UID = 2000;

    static void notifyChanged(Context c) {
        c.getContentResolver().notifyChange(STATE, null, false);
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
                Jobs.request(c, Schedule.Reason.from(arg));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else if (!"state".equals(method)) {
            return null;
        }
        Store store = Store.get(c);
        Bundle out = new Bundle();
        if (store.current() != null) {
            out.putString("id", store.id());
            out.putString("caption", store.caption());
            out.putInt("dim", store.dim());
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) throw new FileNotFoundException();
        checkCaller(c);
        if (!"r".equals(mode)) throw new SecurityException("read only");
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !"picture".equals(path.get(0))) throw new FileNotFoundException();
        String id = path.get(1);
        Store store = Store.get(c);
        // Only today's picture and the one before it, by id: never a path.
        if (!id.equals(store.id()) && !id.equals(store.previousId())) throw new FileNotFoundException();
        File file = store.file(id);
        if (file == null || !file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void checkCaller(Context c) {
        int uid = Binder.getCallingUid();
        int me = Process.myUid();
        if (uid == me || uid == SHELL_UID) return;
        if (c.getPackageManager().checkSignatures(uid, me) == PackageManager.SIGNATURE_MATCH) return;
        throw new SecurityException("Daily Wallpaper answers only apps signed with its own key");
    }

    @Override
    public String getType(Uri uri) {
        List<String> path = uri.getPathSegments();
        return path.size() == 2 && "picture".equals(path.get(0)) ? "image/jpeg" : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
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
