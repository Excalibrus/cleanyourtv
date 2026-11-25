package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * episodes_panel: the NEW EPISODES list at the top right, above the dock. The New Episodes app (local.newepisodes)
 * does the network work; this reads its snapshot over a content provider call (which only answers apps signed with
 * the same key), on a background thread, when Home comes back and when the app says the list changed. Up from the
 * dock enters the rows, OK on an episode opens it in Stremio.
 */
final class EpisodesPanel {
    /** What the panel needs from Home: the title above the dock, and the way back to the dock. */
    interface Host {
        /** A row took focus: show {@code title}, and {@code subtitle} (null for none) under it. */
        void onRowFocused(String title, String subtitle);

        /** Focus left the rows for something else (a tile, a pill). */
        void onRowsLeft();

        /** Down from the bottom row: back to the dock tile Up came from. */
        void focusDockTile(int position);

        /** The panel was shown or hidden; {@code textLeft} is where its text starts, in root coordinates. */
        void onPanelShown(boolean shown, int textLeft);
    }

    static final String APP = "local.newepisodes";
    static final String STREMIO = "com.stremio.one";
    private static final Uri PROVIDER = Uri.parse("content://local.newepisodes.panel");
    private static final Uri SNAPSHOT = Uri.parse("content://local.newepisodes.panel/snapshot");

    private final Activity activity;
    private final Host host;
    private final FrameLayout root;
    private final View panel;
    private final LinearLayout rows;
    private final Spinner spinner;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<EpisodeRows.Line> shown = new ArrayList<>();

    private EpisodeRows.Snapshot snapshot;
    private boolean loaded;
    private boolean resumed;
    private boolean observing;
    private int dockPosition;
    /** Where the dock band starts, in root coordinates; the rows stop above it. -1 until the dock is laid out. */
    private int dockTop = -1;
    private int rowsTop = -1;

    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            if (resumed) load(false);
        }
    };
    private final Runnable recheck = this::render;

    /**
     * @param backing a dark backing behind the panel (daily_wallpaper on). It reaches 6dp past the text on every
     *                side, so the text stays where it is without one.
     */
    EpisodesPanel(Activity activity, FrameLayout root, View before, Host host, boolean stillLoader, boolean backing) {
        this.activity = activity;
        this.host = host;
        this.root = root;
        density = activity.getResources().getDisplayMetrics().density;
        panel = LayoutInflater.from(activity).inflate(R.layout.episodes_panel, root, false);
        if (backing) {
            int pad = (int) (6 * density);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            lp.width += 2 * pad;
            lp.topMargin -= pad;
            lp.rightMargin -= pad;
            panel.setPadding(pad, pad, pad, pad);
            panel.setBackgroundResource(R.drawable.episodes_backing);
        }
        root.addView(panel, root.indexOfChild(before));
        rows = panel.findViewById(R.id.episodesRows);
        spinner = new Spinner(activity, stillLoader);
        spinner.setVisibility(View.INVISIBLE);
        ((FrameLayout) panel.findViewById(R.id.episodesSpinner)).addView(spinner,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- lifecycle ----

    void onResume() {
        resumed = true;
        if (!observing) {
            try {
                activity.getContentResolver().registerContentObserver(SNAPSHOT, false, observer);
                observing = true;
            } catch (SecurityException e) {
                // The app is missing or refuses this launcher: the setup row shows, and the next resume tries again.
            }
        }
        // "home": the app refreshes when its own rules allow (at most every 15 minutes, sooner after a failure).
        load(true);
    }

    void onPause() {
        resumed = false;
        main.removeCallbacks(recheck);
        spinner.stop();
        if (observing) {
            activity.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
    }

    /** Once a minute while Home is resumed: the day labels and the stale line follow the clock. */
    void onMinute() {
        if (resumed) render();
    }

    void onDestroy() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ---- data ----

    private void load(boolean refresh) {
        ContentResolver resolver = activity.getContentResolver();
        try {
            io.execute(() -> {
                EpisodeRows.Snapshot s = null;
                try {
                    Bundle out = resolver.call(PROVIDER, refresh ? "refresh" : "snapshot", refresh ? "home" : null, null);
                    s = parse(out == null ? null : out.getString("json"));
                } catch (RuntimeException e) {
                    // Not installed (unknown authority), or signed with another key (SecurityException).
                }
                EpisodeRows.Snapshot result = s;
                main.post(() -> {
                    snapshot = result;
                    loaded = true;
                    render();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Destroyed.
        }
    }

    static EpisodeRows.Snapshot parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("v") != 1) return null;
            List<EpisodeRows.Episode> list = new ArrayList<>();
            JSONArray a = o.optJSONArray("rows");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;
                list.add(new EpisodeRows.Episode(r.optString("series"), r.optString("name"), r.optString("video"),
                        r.optInt("season"), r.optInt("episode"), r.optString("title"), r.optLong("released"),
                        r.optInt("more")));
            }
            return new EpisodeRows.Snapshot(o.optString("state", "setup"), o.optLong("lastSuccess"),
                    o.optBoolean("refreshing"), o.optLong("refreshStarted"), o.optBoolean("retrying"), list);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---- layout ----

    /** The dock tiles' top in root coordinates, from Home's grid layout. */
    void setDockCardTop(int cardTop) {
        // Not laid out (the dock scrolled away): keep the last value.
        if (cardTop <= 0) return;
        // The dock band reaches 14dp above its tiles (DockDecoration); keep 4dp clear of it.
        int top = cardTop - (int) (18 * density);
        if (top == dockTop) return;
        dockTop = top;
        if (loaded) render();
    }

    private int capacity() {
        if (dockTop < 0) return EpisodeRows.MAX_ROWS;
        if (rowsTop < 0) {
            View header = panel.findViewById(R.id.episodesHeader);
            header.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            // Header, its 1dp rule, and the rows' 4dp margin.
            rowsTop = lp.topMargin + panel.getPaddingTop() + header.getMeasuredHeight() + (int) (5 * density);
        }
        int rowH = (int) (19 * density);
        return Math.max(1, Math.min(EpisodeRows.MAX_ROWS, (dockTop - rowsTop - panel.getPaddingBottom()) / rowH));
    }

    private void render() {
        main.removeCallbacks(recheck);
        if (!loaded) return;
        long now = System.currentTimeMillis();
        List<EpisodeRows.Line> lines = EpisodeRows.lines(snapshot, capacity(), now, TimeZone.getDefault());
        int focused = focusedRow();
        if (lines.isEmpty()) {
            if (focused >= 0) host.focusDockTile(dockPosition);
            shown.clear();
            rows.removeAllViews();
            spinner.stop();
            if (panel.getVisibility() != View.GONE) {
                panel.setVisibility(View.GONE);
                host.onPanelShown(false, 0);
            }
            return;
        }
        bind(lines, focused);
        if (panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            if (root.getWidth() > 0) host.onPanelShown(true, textLeft());
            else root.post(() -> host.onPanelShown(panel.getVisibility() == View.VISIBLE, textLeft()));
        }
        boolean spin = EpisodeRows.spinner(snapshot, now);
        if (spin && resumed) spinner.start();
        else spinner.stop();
        // A refresh that has just started: look again when it has run a second.
        if (!spin && snapshot != null && snapshot.refreshing && resumed) {
            long ran = Math.max(0, now - snapshot.refreshStarted);
            if (ran < EpisodeRows.SPINNER_DELAY_MS) main.postDelayed(recheck, EpisodeRows.SPINNER_DELAY_MS - ran + 50);
        }
    }

    /** Fills the rows in place, so a focused row keeps focus when the list is refreshed under it. */
    private void bind(List<EpisodeRows.Line> lines, int focused) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        while (rows.getChildCount() < lines.size()) {
            View row = inflater.inflate(R.layout.episodes_row, rows, false);
            int index = rows.getChildCount();
            row.setOnClickListener(v -> open(index));
            row.setOnKeyListener((v, keyCode, event) -> onRowKey(index, keyCode, event));
            row.setOnFocusChangeListener((v, has) -> {
                if (has) onRowFocused(index);
            });
            rows.addView(row);
        }
        // Fewer rows than before: move focus off a row about to go first, so it lands on a row, not anywhere.
        if (focused >= lines.size()) {
            int last = lastFocusable(lines);
            if (last >= 0) rows.getChildAt(last).requestFocus();
            else host.focusDockTile(dockPosition);
        }
        while (rows.getChildCount() > lines.size()) rows.removeViewAt(rows.getChildCount() - 1);
        shown.clear();
        shown.addAll(lines);
        for (int i = 0; i < lines.size(); i++) {
            EpisodeRows.Line line = lines.get(i);
            View row = rows.getChildAt(i);
            TextView name = row.findViewById(R.id.epName);
            TextView code = row.findViewById(R.id.epCode);
            TextView day = row.findViewById(R.id.epDay);
            name.setText(line.text);
            name.setTextColor(activity.getColorStateList(line.kind == EpisodeRows.Kind.EPISODE
                    || line.kind == EpisodeRows.Kind.SETUP ? R.color.episodes_name : R.color.episodes_dim));
            code.setText(line.code);
            code.setVisibility(line.code.isEmpty() ? View.GONE : View.VISIBLE);
            day.setText(line.day);
            day.setVisibility(line.day.isEmpty() ? View.GONE : View.VISIBLE);
            day.setTextColor(activity.getColorStateList(line.soon ? R.color.episodes_dim : R.color.episodes_day));
            boolean focusable = line.focusable();
            if (!focusable && row.hasFocus()) {
                int last = lastFocusable(lines);
                if (last >= 0) rows.getChildAt(last).requestFocus();
            }
            row.setFocusable(focusable);
        }
        if (focused >= 0 && focused < lines.size() && rows.getChildAt(focused).isFocused()) onRowFocused(focused);
    }

    /** Where the rows' text starts, from the layout's numbers: right after it turns visible it has no position yet. */
    private int textLeft() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        return root.getWidth() - lp.rightMargin - lp.width + panel.getPaddingLeft() + (int) (8 * density);
    }

    // ---- focus and keys ----

    boolean isShown() {
        return panel.getVisibility() == View.VISIBLE && !shown.isEmpty();
    }

    boolean isRow(View v) {
        return v != null && v.getParent() == rows;
    }

    /** Up from the dock tile at {@code position}: focus the bottom row. False when the panel has none. */
    boolean focusFromDock(int position) {
        if (!isShown()) return false;
        int last = lastFocusable(shown);
        if (last < 0) return false;
        dockPosition = position;
        rows.getChildAt(last).requestFocus();
        return true;
    }

    /**
     * Down from the top bar: focus the top row. Down from the bottom row then goes to the dock tile at
     * {@code position}. False when the panel has none.
     */
    boolean focusFromTopBar(int position) {
        if (!isShown()) return false;
        int first = nextFocusable(-1, 1);
        if (first < 0) return false;
        dockPosition = position;
        rows.getChildAt(first).requestFocus();
        return true;
    }

    /** From Home's global focus listener. */
    void onFocusChanged(View oldFocus, View newFocus) {
        if (isRow(oldFocus) && !isRow(newFocus)) host.onRowsLeft();
    }

    private int focusedRow() {
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).isFocused()) return i;
        return -1;
    }

    private static int lastFocusable(List<EpisodeRows.Line> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).focusable()) return i;
        return -1;
    }

    private int nextFocusable(int from, int step) {
        for (int i = from + step; i >= 0 && i < shown.size(); i += step) if (shown.get(i).focusable()) return i;
        return -1;
    }

    private boolean onRowKey(int index, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                int to = nextFocusable(index, -1);
                // The top row: let the focus search go up to the top bar's pills.
                if (to < 0) return false;
                rows.getChildAt(to).requestFocus();
                rows.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int to = nextFocusable(index, 1);
                if (to >= 0) rows.getChildAt(to).requestFocus();
                else host.focusDockTile(dockPosition);
                rows.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Nothing beside the rows; the focus search would jump to a tile or a pill.
                return true;
            default:
                return false;
        }
    }

    private void onRowFocused(int index) {
        if (index >= shown.size()) return;
        EpisodeRows.Line line = shown.get(index);
        if (line.kind == EpisodeRows.Kind.EPISODE) host.onRowFocused(line.episode.name, EpisodeRows.subtitle(line.episode));
        else host.onRowFocused("New Episodes", line.kind == EpisodeRows.Kind.MORE ? "Every new episode, in the New Episodes app"
                : "Opens the New Episodes app");
    }

    // ---- opening ----

    private void open(int index) {
        if (index >= shown.size()) return;
        EpisodeRows.Line line = shown.get(index);
        if (line.kind == EpisodeRows.Kind.EPISODE) {
            String link = EpisodeRows.deepLink(line.episode);
            if (link == null) return;
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            if (start(new Intent(view).setPackage(STREMIO)) || start(view)) return;
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager pm = activity.getPackageManager();
        Intent app = pm.getLeanbackLaunchIntentForPackage(APP);
        if (app == null) app = pm.getLaunchIntentForPackage(APP);
        if (app == null || !start(app)) {
            Toast.makeText(activity, "Install the New Episodes app (local.newepisodes) to set this up.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- the loader ----

    /** A small turning arc; with reduced motion a still dot. */
    static final class Spinner extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private final boolean still;
        private ObjectAnimator turn;

        Spinner(Context c, boolean still) {
            super(c);
            this.still = still;
            float d = c.getResources().getDisplayMetrics().density;
            paint.setColor(0xB3FFFFFF);
            paint.setStrokeWidth(1.6f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(still ? Paint.Style.FILL : Paint.Style.STROKE);
        }

        void start() {
            setVisibility(View.VISIBLE);
            if (still || (turn != null && turn.isStarted())) return;
            turn = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f);
            turn.setDuration(900);
            turn.setRepeatCount(ValueAnimator.INFINITE);
            turn.setInterpolator(new LinearInterpolator());
            turn.start();
        }

        void stop() {
            if (turn != null) {
                turn.cancel();
                turn = null;
            }
            setRotation(0f);
            setVisibility(View.INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (still) {
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 4f, paint);
                return;
            }
            float inset = paint.getStrokeWidth();
            arc.set(inset, inset, w - inset, h - inset);
            canvas.drawArc(arc, 0f, 270f, false, paint);
        }
    }
}
