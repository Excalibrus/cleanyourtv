package local.newepisodes;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Signed out: Stremio's link login, a code the person approves on their phone. Signed in: the library's series
 * with ticks (a new series stays off), and the list the Home panel shows, with Refresh and Sign out.
 */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long POLL_MS = 5_000;
    /** A code no one approved is swapped for a new one after this long. */
    private static final long CODE_LIFETIME_MS = 10 * 60_000L;
    private static final long STATUS_TICK_MS = 30_000;
    private static final long SIGN_OUT_CONFIRM_MS = 3_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Store store;

    private View signin;
    private View home;
    private TextView signinIntro;
    private TextView link;
    private TextView code;
    private TextView signinStatus;
    private ImageView qr;
    private Button newCode;
    private ListView series;
    private TextView libraryEmpty;
    private TextView status;
    private LinearLayout rows;
    private Button signOut;

    private boolean started;
    /** Bumped for every new code, so an answer for an older code is dropped. */
    private int codeGeneration;
    private Api.LinkCode linkCode;
    private long codeCreated;
    private boolean polling;
    private boolean signOutArmed;
    private boolean picksChanged;
    private List<Api.LibraryEntry> listed = new ArrayList<>();

    private final Runnable poll = this::pollCode;
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (store.authKey() != null) renderStatus();
            handler.postDelayed(this, STATUS_TICK_MS);
        }
    };
    private final Runnable disarmSignOut = () -> {
        signOutArmed = false;
        signOut.setText(R.string.sign_out);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        signin = findViewById(R.id.signin);
        home = findViewById(R.id.home);
        signinIntro = findViewById(R.id.signinIntro);
        link = findViewById(R.id.link);
        code = findViewById(R.id.code);
        signinStatus = findViewById(R.id.signinStatus);
        qr = findViewById(R.id.qr);
        newCode = findViewById(R.id.newCode);
        series = findViewById(R.id.series);
        libraryEmpty = findViewById(R.id.libraryEmpty);
        status = findViewById(R.id.status);
        rows = findViewById(R.id.rows);
        signOut = findViewById(R.id.signOut);

        newCode.setOnClickListener(v -> newCode());
        findViewById(R.id.refresh).setOnClickListener(v -> {
            Jobs.request(this, RefreshPolicy.Reason.MANUAL);
            renderStatus();
        });
        signOut.setOnClickListener(v -> signOut());
        series.setOnItemClickListener((parent, view, position, id) -> savePicks());
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        store.prefs().registerOnSharedPreferenceChangeListener(this);
        render();
        handler.postDelayed(statusTick, STATUS_TICK_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        store.prefs().unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacksAndMessages(null);
        polling = false;
        signOutArmed = false;
        signOut.setText(R.string.sign_out);
        // The panel follows new picks at once, not after the 15-minute pause between Home refreshes.
        if (picksChanged) {
            picksChanged = false;
            Jobs.request(this, RefreshPolicy.Reason.MANUAL);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        handler.post(() -> {
            if (started) render();
        });
    }

    private void render() {
        if (store.authKey() == null) showSignin();
        else showHome();
    }

    // ---- Signed out ----

    private void showSignin() {
        boolean wasHidden = signin.getVisibility() != View.VISIBLE;
        home.setVisibility(View.GONE);
        signin.setVisibility(View.VISIBLE);
        signinIntro.setText(store.expired() ? R.string.signin_expired : R.string.signin_intro);
        if (linkCode == null && !polling) newCode();
        else if (!polling) startPolling();
        if (wasHidden) newCode.requestFocus();
    }

    private void newCode() {
        int generation = ++codeGeneration;
        linkCode = null;
        polling = false;
        handler.removeCallbacks(poll);
        code.setText("····");
        link.setText("link.stremio.com");
        qr.setImageDrawable(null);
        signinStatus.setText(R.string.signin_getting);
        io.execute(() -> {
            try {
                Api.LinkCode lc = Api.createLink();
                Bitmap image = null;
                if (!lc.qr.isEmpty()) {
                    try {
                        byte[] png = Api.bytes(lc.qr);
                        image = BitmapFactory.decodeByteArray(png, 0, png.length);
                    } catch (IOException ignored) {
                        // The link and code are enough without the picture.
                    }
                }
                Bitmap picture = image;
                handler.post(() -> {
                    if (generation != codeGeneration || !started) return;
                    linkCode = lc;
                    codeCreated = System.currentTimeMillis();
                    code.setText(lc.code);
                    link.setText(lc.link.replaceFirst("^https?://", ""));
                    if (picture != null) {
                        BitmapDrawable d = new BitmapDrawable(getResources(), picture);
                        d.setFilterBitmap(false);
                        qr.setImageDrawable(d);
                    }
                    signinStatus.setText(R.string.signin_waiting);
                    startPolling();
                });
            } catch (IOException e) {
                handler.post(() -> {
                    if (generation == codeGeneration && started) signinStatus.setText(R.string.signin_offline);
                });
            }
        });
    }

    private void startPolling() {
        if (linkCode == null || !started) return;
        polling = true;
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
    }

    private void pollCode() {
        if (!polling || linkCode == null || !started) return;
        if (System.currentTimeMillis() - codeCreated > CODE_LIFETIME_MS) {
            newCode();
            return;
        }
        int generation = codeGeneration;
        String c = linkCode.code;
        io.execute(() -> {
            String key = null;
            String error = null;
            try {
                key = Api.readLink(c);
            } catch (IOException e) {
                error = e.getMessage();
            }
            String authKey = key;
            String problem = error;
            handler.post(() -> {
                if (generation != codeGeneration || !started) return;
                if (authKey != null) {
                    polling = false;
                    linkCode = null;
                    store.signIn(authKey);
                    Jobs.request(this, RefreshPolicy.Reason.MANUAL);
                    render();
                    return;
                }
                signinStatus.setText(problem == null ? getString(R.string.signin_waiting)
                        : "Couldn't check the code (" + problem + "). Trying again…");
                handler.postDelayed(poll, POLL_MS);
            });
        });
    }

    // ---- Signed in ----

    private void showHome() {
        boolean wasHidden = home.getVisibility() != View.VISIBLE;
        polling = false;
        handler.removeCallbacks(poll);
        signin.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        renderLibrary();
        renderStatus();
        renderRows();
        if (wasHidden) {
            if (series.getCount() > 0) series.requestFocus();
            else findViewById(R.id.refresh).requestFocus();
        }
    }

    private void renderLibrary() {
        List<Api.LibraryEntry> lib = store.library();
        List<Api.LibraryEntry> sorted = lib == null ? new ArrayList<>() : new ArrayList<>(lib);
        Collections.sort(sorted, (a, b) -> a.name.compareToIgnoreCase(b.name));
        if (!sameIds(sorted, listed)) {
            listed = sorted;
            List<String> names = new ArrayList<>();
            for (Api.LibraryEntry e : sorted) names.add(e.name);
            boolean hadFocus = series.hasFocus();
            series.setAdapter(new ArrayAdapter<>(this, R.layout.series_item, names));
            if (hadFocus && !names.isEmpty()) series.requestFocus();
        }
        Set<String> picked = store.followed();
        for (int i = 0; i < listed.size(); i++) series.setItemChecked(i, picked.contains(listed.get(i).id));
        if (lib == null) {
            libraryEmpty.setText(store.state().failures > 0 ? R.string.library_failed : R.string.library_loading);
            libraryEmpty.setVisibility(View.VISIBLE);
        } else if (lib.isEmpty()) {
            libraryEmpty.setText(R.string.library_empty);
            libraryEmpty.setVisibility(View.VISIBLE);
        } else {
            libraryEmpty.setVisibility(View.GONE);
        }
    }

    private static boolean sameIds(List<Api.LibraryEntry> a, List<Api.LibraryEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id.equals(b.get(i).id) || !a.get(i).name.equals(b.get(i).name)) return false;
        }
        return true;
    }

    private void savePicks() {
        Set<String> picked = store.followed();
        // Ids not in today's library stay as they are; they come back ticked if the series is added again.
        for (Api.LibraryEntry e : listed) picked.remove(e.id);
        SparseBooleanArray checked = series.getCheckedItemPositions();
        for (int i = 0; i < listed.size(); i++) {
            if (checked.get(i)) picked.add(listed.get(i).id);
        }
        store.setFollowed(picked);
        picksChanged = true;
    }

    private void renderStatus() {
        RefreshPolicy.State s = store.state();
        long now = System.currentTimeMillis();
        String text;
        if (RefreshPolicy.isRunning(s, now)) {
            text = "Updating…";
        } else if (s.failures > 0) {
            long in = Math.max(0, store.nextRetry() - now);
            long minutes = Math.max(1, (in + 59_999) / 60_000);
            String error = store.lastError();
            text = "Couldn't update" + (error == null ? "" : " (" + error + ")") + ". Trying again in "
                    + minutes + " min.";
        } else if (store.lastSuccess() == 0) {
            text = "Not updated yet.";
        } else {
            text = "Updated " + ago(now - store.lastSuccess()) + ".";
        }
        status.setText(text);
    }

    static String ago(long ms) {
        long minutes = Math.max(0, ms) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    private void renderRows() {
        rows.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        JSONArray list = store.rows();
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            View row = inflater.inflate(R.layout.episode_row, rows, false);
            ((TextView) row.findViewById(R.id.name)).setText(r.optString("name"));
            int more = r.optInt("more");
            ((TextView) row.findViewById(R.id.code)).setText(DayLabel.code(r.optInt("season"), r.optInt("episode"))
                    + (more > 0 ? String.format(Locale.US, " +%d", more) : ""));
            String day = DayLabel.of(r.optLong("released"), now, zone);
            TextView dayView = row.findViewById(R.id.day);
            dayView.setText(day);
            if ("tomorrow".equals(day)) dayView.setTextColor(0x8CFFFFFF);
            rows.addView(row);
        }
        if (list.length() == 0) {
            TextView none = new TextView(this);
            none.setTextColor(0x8CFFFFFF);
            none.setTextSize(15);
            none.setText(store.followedInLibrary().isEmpty() ? R.string.rows_none_picked : R.string.rows_none);
            rows.addView(none);
        }
    }

    private void signOut() {
        if (!signOutArmed) {
            signOutArmed = true;
            signOut.setText(R.string.sign_out_confirm);
            handler.postDelayed(disarmSignOut, SIGN_OUT_CONFIRM_MS);
            return;
        }
        handler.removeCallbacks(disarmSignOut);
        signOutArmed = false;
        signOut.setText(R.string.sign_out);
        String key = store.authKey();
        if (key != null) io.execute(() -> Api.logout(key));
        Jobs.cancelAll(this);
        store.signOut();
        listed = new ArrayList<>();
        series.setAdapter(null);
        PanelProvider.notifyChanged(this);
        render();
    }
}
