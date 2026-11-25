package local.dailywallpaper;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Today's picture full screen, its caption, when it was checked, and Refresh. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Store store;
    private ImageView picture;
    private TextView caption;
    private TextView status;
    private Bitmap shown;
    private String shownId;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        picture = findViewById(R.id.picture);
        caption = findViewById(R.id.caption);
        status = findViewById(R.id.status);
        View refresh = findViewById(R.id.refresh);
        refresh.setOnClickListener(v -> {
            Jobs.request(this, Schedule.Reason.MANUAL);
            render();
        });
        refresh.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        store.prefs().registerOnSharedPreferenceChangeListener(this);
        // Opening the app checks too when a check is due, as a return to Home does.
        Jobs.request(this, Schedule.Reason.HOME);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        store.prefs().unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        picture.setImageDrawable(null);
        if (shown != null) shown.recycle();
        shown = null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        handler.post(this::render);
    }

    private void render() {
        if (!started) return;
        File file = store.current();
        String id = store.id();
        String text = store.caption();
        caption.setText(file == null ? getString(R.string.no_picture) : text);
        status.setText(statusText(file != null));
        if (file != null && !id.equals(shownId)) load(file, id);
    }

    private String statusText(boolean hasPicture) {
        Schedule.State s = store.state();
        long now = System.currentTimeMillis();
        if (Schedule.isRunning(s, now)) return getString(R.string.status_checking);
        String error = store.lastError();
        if (store.failures() > 0) {
            return getString(hasPicture ? R.string.status_failed_kept : R.string.status_failed_none,
                    error == null ? "" : error, when(store.nextCheck(), now));
        }
        if (!hasPicture) return getString(R.string.status_first);
        return getString(R.string.status_ok, time(store.checked()), when(store.nextCheck(), now));
    }

    private String time(long at) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
    }

    /** "at 21:40", "tomorrow at 01:05", or a date further away. */
    private String when(long at, long now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(at);
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(now);
        if (a.get(Calendar.YEAR) == n.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)) {
            return getString(R.string.today_at, time(at));
        }
        n.add(Calendar.DAY_OF_YEAR, 1);
        if (a.get(Calendar.YEAR) == n.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)) {
            return getString(R.string.tomorrow_at, time(at));
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(at));
    }

    /** Decodes the picture off the UI thread, at screen size in RGB_565 (4 MB at 1080p). */
    private void load(File file, String id) {
        shownId = id;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int screenW = m.widthPixels, screenH = m.heightPixels;
        io.execute(() -> {
            Bitmap b = decode(file, screenW, screenH);
            handler.post(() -> {
                if (isDestroyed() || !id.equals(shownId)) {
                    if (b != null) b.recycle();
                    return;
                }
                Bitmap old = shown;
                shown = b;
                picture.setImageBitmap(b);
                if (old != null) handler.postDelayed(old::recycle, 500);
            });
        });
    }

    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
