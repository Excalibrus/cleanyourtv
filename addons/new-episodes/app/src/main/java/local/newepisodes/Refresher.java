package local.newepisodes;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * One refresh: read the library (watched state) every time, Cinemeta's episode lists when they are over six
 * hours old, and turn them into rows. On a failure the last good rows stay and a retry is scheduled; when Stremio
 * refuses the sign-in, retries stop until the person signs in again.
 */
final class Refresher {
    /** {@link #run}: nothing ran, leave the retry job as it is. */
    static final long KEEP_RETRY = -1;
    /** {@link #run}: no retry is needed, cancel a pending one. */
    static final long NO_RETRY = 0;
    private static final Object LOCK = new Object();

    private Refresher() {
    }

    /**
     * Runs on the calling thread, which must not be the UI thread. Returns the wait before the next try after a
     * failure, {@link #NO_RETRY} or {@link #KEEP_RETRY}. The caller schedules it once its own job has finished:
     * the retry job may be the one running, and replacing or cancelling a running job stops it.
     */
    static long run(Context c) {
        synchronized (LOCK) {
            Store store = Store.get(c);
            String key = store.authKey();
            if (key == null) return KEEP_RETRY;
            long now = System.currentTimeMillis();
            store.startAttempt(now);
            PanelProvider.notifyChanged(c);
            try {
                List<Api.LibraryEntry> library = Api.library(key);
                store.saveLibrary(library);
                List<Episodes.Series> series = new ArrayList<>();
                Set<String> keep = new HashSet<>();
                boolean missing = false;
                for (Api.LibraryEntry e : store.followedInLibrary()) {
                    keep.add(e.id);
                    List<Video> videos = store.videos(e.id);
                    if (videos == null || RefreshPolicy.metaDue(store.videosFetched(e.id), now)) {
                        try {
                            videos = Api.videos(e.id);
                            store.saveVideos(e.id, videos, now);
                        } catch (IOException ex) {
                            // An older list still serves; with none, this refresh failed.
                            if (videos == null) {
                                missing = true;
                                continue;
                            }
                        }
                    }
                    series.add(new Episodes.Series(e.id, e.name, e.watched, videos));
                }
                if (missing) throw new IOException("Cinemeta did not answer");
                store.deleteMeta(keep);
                long done = System.currentTimeMillis();
                List<Episodes.Row> rows = Episodes.rows(series, done, TimeZone.getDefault());
                store.succeeded(Store.rowsJson(rows), done);
                return NO_RETRY;
            } catch (Api.AuthException e) {
                store.markExpired();
                return NO_RETRY;
            } catch (IOException | RuntimeException e) {
                int failures = store.state().failures + 1;
                long delay = RefreshPolicy.retryDelay(failures);
                store.failed(String.valueOf(e.getMessage()), System.currentTimeMillis() + delay);
                return delay;
            } finally {
                store.stopRunning();
                PanelProvider.notifyChanged(c);
            }
        }
    }
}
