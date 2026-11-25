package local.dailywallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;

/**
 * One check: ask Bing for today's picture and download it when it is new. On a failure the picture already here
 * stays, and the next try waits 5, 15, then 60 minutes.
 */
final class Fetcher {
    private static final Object LOCK = new Object();
    /** Bing's 1920x1080 pictures are 200-600 KB. */
    static final int MAX_PICTURE = 10 * 1024 * 1024;
    private static final int MIN_WIDTH = 640;
    private static final int MIN_HEIGHT = 360;

    private Fetcher() {
    }

    /** Runs on the calling thread, which must not be the UI thread. */
    static void run(Context c) {
        synchronized (LOCK) {
            Store store = Store.get(c);
            store.startAttempt(System.currentTimeMillis());
            PictureProvider.notifyChanged(c);
            try {
                Bing.Picture p = Bing.parse(Http.text(Bing.FEED));
                if (p.id.equals(store.id()) && store.current() != null) {
                    long now = System.currentTimeMillis();
                    store.unchanged(now, Schedule.nextAfterSuccess(p.start, now));
                } else {
                    File tmp = store.tempFile();
                    Http.download(p.url, tmp, MAX_PICTURE);
                    int dim;
                    try {
                        dim = measure(tmp);
                    } catch (IOException e) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        throw e;
                    }
                    File target = store.file(p.id);
                    if (target == null || !tmp.renameTo(target)) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        throw new IOException("could not save the picture");
                    }
                    long now = System.currentTimeMillis();
                    store.saved(p, dim, now, Schedule.nextAfterSuccess(p.start, now));
                }
            } catch (IOException | RuntimeException e) {
                int failures = store.failures() + 1;
                store.failed(message(e), System.currentTimeMillis() + Schedule.retryDelay(failures));
            } finally {
                store.stopRunning();
                PictureProvider.notifyChanged(c);
            }
        }
    }

    /** Checks the download is a picture of a usable size and picks its dim from a 1/16 sample of it. */
    private static int measure(File f) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth < MIN_WIDTH || o.outHeight < MIN_HEIGHT) throw new IOException("not a picture");
        o.inJustDecodeBounds = false;
        o.inSampleSize = 16;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("not a picture");
        try {
            int w = b.getWidth(), h = b.getHeight();
            int[] pixels = new int[w * h];
            b.getPixels(pixels, 0, w, 0, 0, w, h);
            return Brightness.dimFor(Brightness.mean(pixels));
        } finally {
            b.recycle();
        }
    }

    private static String message(Exception e) {
        if (e instanceof UnknownHostException) return "no connection";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
