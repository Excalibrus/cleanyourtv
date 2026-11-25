package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** On-demand installed-app picker. Uses the launcher's glass resources; no backdrop capture or blur. */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    private boolean closed;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;

    /** input_tiles: one of the TV's inputs, that a tile can switch to. */
    static final class LibraryInput {
        final String id;
        /** "HDMI 4": shown under the name. */
        final String port;
        /** The TV's own name for it (Settings > Inputs, or the CEC device's), or null. */
        final String name;
        /** The icon when none is picked: InputIcons.HDMI or InputIcons.AV. */
        final String icon;

        LibraryInput(String id, String port, String name, String icon) {
            this.id = id;
            this.port = port;
            this.name = name;
            this.icon = icon;
        }
    }

    /** input_tiles: the inputs a tile can use; empty when the switch is off or the TV lists none. */
    private List<LibraryInput> inputs = new ArrayList<>();
    private Consumer<String> openInput;
    private LinearLayout inputRow;
    private int inputFocus;

    /** input_tiles: call before show(). */
    void setInputs(List<LibraryInput> inputs, Consumer<String> openInput) {
        this.inputs = new ArrayList<>(inputs);
        this.openInput = openInput;
    }

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    private void resize(int width, int height) {
        popup.setWidth(width);
        popup.setHeight(height);
        if (popup.isShowing()) popup.update(width, height);
    }

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode, boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable dismissed) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundResource(R.drawable.glass_panel);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.min(dp(800), Math.max(dp(200), decor.getWidth() - dp(64)));
        int width = catalogWidth;
        int height = dp(280);
        popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissActions();
            stopHighlight();
            icons.clear();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (editor != null) editor.dismiss();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(true);
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    installed.put(pkg, 0L);
                }
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                labels.clear();
                labels.putAll(names);
                fill(new ArrayList<>(unique.values()));
                if (loads.takeReveal()) reveal();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        totalApps = catalogue.size();
        apps = catalogue;
        Map<String, Boolean> home = new HashMap<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            home.put(pkg, onHome.test(pkg));
        }
        catalogTime = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            if (selectionMode) return LibraryApps.compareCatalog(home.get(pa), label(a), pa,
                    home.get(pb), label(b), pb, collator);
            int priority = LibraryApps.comparePriority(home.get(pa), installTimes.get(pa),
                    home.get(pb), installTimes.get(pb), catalogTime);
            if (priority != 0) return priority;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try { loaded = app.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shown = result != null ? result : activity.getDrawable(R.drawable.ic_apps);
                icons.put(pkg, shown);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag())) icon.setImageDrawable(shown);
            });
        });
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Reuse translated actions instead of shipping another hint in every locale. */
    private String hint(boolean home) {
        if (selectionMode) return activity.getString(android.R.string.ok) + ": "
                + activity.getString(home ? R.string.library_remove_shortcut : R.string.library_add_home);
        return activity.getString(home || okOpens ? R.string.library_hint_open : R.string.library_hint_add);
    }

    private void showCatalog() {
        stopHighlight();
        resize(catalogWidth, dp(inputs.isEmpty() ? 280 : 436));
        panel.removeAllViews();
        inputRow = null;
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, totalApps, totalApps), 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        instructions = text(hint(false), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            heading(activity.getString(R.string.library_empty));
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }
        shelf = new FrameLayout(activity);
        // The glass uses physical x coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setAdapter(new Catalog());
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(20));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!inputs.isEmpty()) addInputs();
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (selectionMode) {
            updateSelectionFocus(app, add);
            changed.run();
            fill(new ArrayList<>(apps));
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                        : catalog.getLayoutManager().getDecoratedLeft(card)
                            - params.leftMargin - catalog.getPaddingLeft();
            }
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void updateSelectionFocus(ResolveInfo app, boolean add) {
        int position = apps.indexOf(app);
        int next = add ? -1 : LibraryApps.nextAfterRemoval(position, apps.size());
        selectedOffset = 0;
        if (!add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                        : catalog.getLayoutManager().getDecoratedLeft(card)
                            - params.leftMargin - catalog.getPaddingLeft();
            }
        }
        selectedPackage = next >= 0 ? apps.get(next).activityInfo.packageName : app.activityInfo.packageName;
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        addAction(rows, R.string.library_open, () -> open(app));
        boolean home = onHome.test(pkg);
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home,
                () -> changeHome(app, !home));
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info != null) addAction(rows, R.string.library_app_info, () -> {
            close();
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        placeMenu(menu, menuPanel, rows, anchor);
    }

    /** Sizes an action menu to its longest row and shows it by anchor, inside the library. */
    private void placeMenu(PopupWindow menu, View menuPanel, LinearLayout rows, View anchor) {
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(58));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0], panelLocation[1],
                panelLocation[0] + panel.getWidth(), panelLocation[1] + panel.getHeight());
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuPanel.setAlpha(0f);
        menuPanel.setPivotY(0f);
        menuPanel.setScaleY(0.92f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        addAction(rows, activity.getString(label), action);
    }

    private void addAction(LinearLayout rows, String label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    // ---- input_tiles ------------------------------------------------------------------------------

    /** The open tile editor, over the library; null when none is. */
    private PopupWindow editor;
    private LinearLayout editorRows;
    private ImageView editorPreview;
    private String editingKey;
    private int editorFocus;

    private void addInputs() {
        TextView heading = text(activity.getString(R.string.inputs), 15, 0xA6FFFFFF);
        heading.setPadding(dp(12), dp(8), dp(12), 0);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(dp(14), dp(10), dp(14), dp(8));
        inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setClipChildren(false);
        inputRow.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(inputRow, new FrameLayout.LayoutParams(-2, -1));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(128)));
        fillInputs();
    }

    /** The saved tiles, then "+ Input tile". */
    private void fillInputs() {
        if (inputRow == null) return;
        inputRow.removeAllViews();
        List<InputTiles.Tile> tiles = InputTiles.load(activity);
        for (int i = 0; i < tiles.size(); i++) inputRow.addView(tileCard(tiles.get(i), i));
        inputRow.addView(newTileCard(tiles.size()));
    }

    private LibraryInput inputFor(String id) {
        for (LibraryInput input : inputs) if (input.id.equals(id)) return input;
        return null;
    }

    private String fallbackIcon(String inputId) {
        LibraryInput input = inputFor(inputId);
        return input == null ? InputIcons.HDMI : input.icon;
    }

    /** "HDMI 4 · VIP5305", or the port alone when the TV has no name for it. */
    private String inputLabel(String inputId) {
        LibraryInput input = inputFor(inputId);
        if (input == null) return activity.getString(R.string.not_available);
        return input.name == null || input.name.isEmpty() ? input.port : input.port + " · " + input.name;
    }

    private GradientDrawable ring(boolean focused, int radius) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(radius));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(focused ? 3 : 1), focused ? 0xF2FFFFFF : 0x24FFFFFF);
        return ring;
    }

    /** A card in the row, the size of a small Home tile, with focus, keys and the glass hidden while it has focus. */
    private FrameLayout rowCard(int index) {
        FrameLayout card = new FrameLayout(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(156), dp(88));
        params.setMargins(dp(6), 0, dp(6), 0);
        card.setLayoutParams(params);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setForeground(ring(false, 12));
        card.setOnFocusChangeListener((v, focused) -> {
            ((FrameLayout) v).setForeground(ring(focused, 12));
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(reducedMotion ? 0 : 140).start();
            if (!focused) return;
            inputFocus = index;
            if (instructions != null) instructions.setText(R.string.library_hint_open);
            // The glass belongs to the apps' shelf: it waits there, hidden, for focus to come back up.
            if (spring != null) spring.cancel();
            if (highlight != null) highlight.setAlpha(0f);
            highlightShown = false;
        });
        card.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                restoreCardFocus();
                return true;
            }
            int last = inputRow == null ? 0 : inputRow.getChildCount() - 1;
            return key == KeyEvent.KEYCODE_DPAD_DOWN
                    || (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                    || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == last;
        });
        return card;
    }

    private View tileCard(InputTiles.Tile tile, int index) {
        FrameLayout card = rowCard(index);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(0xFF16181D);
        card.setBackground(shape);
        ImageView banner = new ImageView(activity);
        banner.setScaleType(ImageView.ScaleType.FIT_XY);
        banner.setImageDrawable(new InputIcons.TileBanner(activity, tile, fallbackIcon(tile.inputId)));
        banner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        if (tile.home) {
            View check = new View(activity);
            check.setBackgroundResource(R.drawable.library_check);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(6), dp(6), 0);
            card.addView(check, badge);
        }
        String name = tile.title.isEmpty() ? tile.caption : tile.caption.isEmpty() ? tile.title
                : tile.title + " · " + tile.caption;
        card.setContentDescription(name);
        card.setOnClickListener(v -> openTileInput(tile));
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> {
            showTileActions(tile, v);
            return true;
        });
        return card;
    }

    private View newTileCard(int index) {
        FrameLayout card = rowCard(index);
        card.setBackground(surface(0x0CFFFFFF, 12));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        TextView plus = text("+", 26, 0xCCFFFFFF);
        plus.setGravity(Gravity.CENTER);
        content.addView(plus, new LinearLayout.LayoutParams(-2, -2));
        TextView label = text(activity.getString(R.string.input_tile_new), 13, 0xB3FFFFFF);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = dp(4);
        content.addView(label, labelParams);
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        card.setContentDescription(activity.getString(R.string.input_tile_new));
        card.setOnClickListener(v -> createTile());
        return card;
    }

    /** Down from the apps: the card last focused in the row, or the first. */
    private boolean focusInputs() {
        if (inputRow == null || inputRow.getChildCount() == 0) return false;
        inputRow.getChildAt(Math.min(inputFocus, inputRow.getChildCount() - 1)).requestFocus();
        return true;
    }

    /** After a menu or dialog: back to the editor's row when it is open, else to the row's card. */
    private void focusInputLater() {
        if (editor != null && editor.isShowing() && editorRows != null) {
            LinearLayout rows = editorRows;
            rows.post(() -> {
                if (rows != editorRows || actionPopup != null || rows.getChildCount() == 0) return;
                rows.getChildAt(Math.min(editorFocus, rows.getChildCount() - 1)).requestFocus();
            });
            return;
        }
        if (inputRow == null) return;
        LinearLayout row = inputRow;
        row.post(() -> {
            if (row != inputRow || actionPopup != null || editor != null || !popup.isShowing()
                    || row.getChildCount() == 0) return;
            row.getChildAt(Math.min(inputFocus, row.getChildCount() - 1)).requestFocus();
        });
    }

    private void openTileInput(InputTiles.Tile tile) {
        Consumer<String> open = openInput;
        close();
        if (open != null) open.accept(tile.inputId);
    }

    /** Saves the tile, then Home, the row and the editor follow it. */
    private void saveTile(InputTiles.Tile tile) {
        if (!InputTiles.put(activity, tile)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        changed.run();
        fillInputs();
        if (editor != null && tile.key.equals(editingKey)) fillEditor();
        focusInputLater();
    }

    /** "+ Input tile": a tile for the first input no tile has yet, named as the TV names it, then its editor. */
    private void createTile() {
        if (inputs.isEmpty()) return;
        List<InputTiles.Tile> tiles = InputTiles.load(activity);
        LibraryInput input = inputs.get(0);
        for (LibraryInput candidate : inputs) {
            boolean used = false;
            for (InputTiles.Tile t : tiles) if (t.inputId.equals(candidate.id)) { used = true; break; }
            if (!used) { input = candidate; break; }
        }
        InputTiles.Tile tile = new InputTiles.Tile(InputTiles.newKey(tiles), input.id,
                defaultTitle(input), defaultCaption(input), input.icon, true);
        if (!InputTiles.put(activity, tile)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        changed.run();
        fillInputs();
        inputFocus = Math.max(0, inputRow == null ? 0 : inputRow.getChildCount() - 2);
        showEditor(tile.key);
    }

    private void showTileActions(InputTiles.Tile tile, View anchor) {
        dismissActions();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = menuWindow(menuPanel, dp(220));
        addAction(rows, R.string.library_open, () -> openTileInput(tile));
        addAction(rows, R.string.input_tile_edit, () -> {
            dismissActions();
            showEditor(tile.key);
        });
        addAction(rows, tile.home ? R.string.library_remove_shortcut : R.string.library_add_home, () -> {
            dismissActions();
            saveTile(tile.withHome(!tile.home));
        });
        addAction(rows, R.string.input_tile_delete, () -> {
            dismissActions();
            deleteTile(tile.key);
        });
        placeMenu(menu, menuPanel, rows, anchor);
    }

    private void deleteTile(String key) {
        if (!InputTiles.remove(activity, key)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (editor != null && key.equals(editingKey)) editor.dismiss();
        changed.run();
        fillInputs();
        focusInputLater();
    }

    private InputTiles.Tile editing() {
        return editingKey == null ? null : InputTiles.find(InputTiles.load(activity), editingKey);
    }

    /** The editor: the tile's settings on the left, each saved as it changes; the tile as it looks on the right. */
    private void showEditor(String key) {
        dismissActions();
        if (editor != null) editor.dismiss();
        editingKey = key;
        editorFocus = 0;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackgroundResource(R.drawable.glass_panel);
        box.setPadding(dp(16), dp(14), dp(20), dp(16));
        editorRows = new LinearLayout(activity);
        editorRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(editorRows, new LinearLayout.LayoutParams(dp(330), -2));
        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(dp(22), dp(6), 0, 0);
        FrameLayout frame = new FrameLayout(activity);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(0xFF16181D);
        frame.setBackground(shape);
        frame.setClipToOutline(true);
        editorPreview = new ImageView(activity);
        editorPreview.setScaleType(ImageView.ScaleType.FIT_XY);
        frame.addView(editorPreview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dp(224), dp(126));
        frameParams.topMargin = dp(4);
        side.addView(frame, frameParams);
        box.addView(side, new LinearLayout.LayoutParams(-2, -2));
        PopupWindow window = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0);
        window.setOnDismissListener(() -> {
            if (editor == window) {
                editor = null;
                editorRows = null;
                editorPreview = null;
                editingKey = null;
            }
            focusInputLater();
        });
        editor = window;
        fillEditor();
        window.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        focusInputLater();
    }

    private void fillEditor() {
        InputTiles.Tile tile = editing();
        if (tile == null || editorRows == null) {
            if (editor != null) editor.dismiss();
            return;
        }
        editorPreview.setImageDrawable(new InputIcons.TileBanner(activity, tile, fallbackIcon(tile.inputId)));
        editorRows.removeAllViews();
        addEditorRow(R.string.input_tile_input, inputLabel(tile.inputId), v -> chooseInput(tile, v));
        addEditorRow(R.string.input_tile_title, tile.title, v -> askText(R.string.input_tile_title, tile.title,
                text -> saveTile(tile.withTitle(text))));
        addEditorRow(R.string.input_tile_caption, tile.caption, v -> askText(R.string.input_tile_caption, tile.caption,
                text -> saveTile(tile.withCaption(text))));
        addEditorRow(R.string.input_tile_icon, iconName(tile.icon, fallbackIcon(tile.inputId)), v -> pickIcon(tile));
        addEditorRow(R.string.input_tile_on_home, activity.getString(tile.home ? R.string.input_tile_yes
                : R.string.input_tile_no), v -> saveTile(tile.withHome(!tile.home)));
        addEditorRow(R.string.input_tile_delete, "", v -> deleteTile(tile.key));
        addEditorRow(R.string.input_tile_done, "", v -> {
            if (editor != null) editor.dismiss();
        });
    }

    private String iconName(String key, String fallback) {
        if (key != null && key.startsWith(InputIcons.FILE_PREFIX)) return InputIcons.fileName(key);
        InputIcons.Icon icon = InputIcons.find(key);
        if (icon == null) icon = InputIcons.find(fallback);
        return icon == null ? activity.getString(R.string.input_tile_none) : icon.name;
    }

    private void addEditorRow(int label, String value, View.OnClickListener action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, editorRows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView status = row.findViewById(R.id.status);
        status.setText(value);
        status.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(dp(190));
        row.setFocusableInTouchMode(true);
        int index = editorRows.getChildCount();
        row.setOnClickListener(v -> {
            editorFocus = index;
            action.onClick(v);
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) editorFocus = index;
        });
        row.setOnKeyListener((v, key, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                || key == KeyEvent.KEYCODE_DPAD_DOWN && editorRows != null && index == editorRows.getChildCount() - 1));
        editorRows.addView(row);
    }

    private PopupWindow menuWindow(View content, int width) {
        PopupWindow menu = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        actionPopup = menu;
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            focusInputLater();
        });
        return menu;
    }

    /** The inputs to switch to, as a menu by the editor's Input row. */
    /** A new tile's title: the name the TV gives the input ("PlayStation 5"), else its port. */
    private static String defaultTitle(LibraryInput input) {
        return input.name != null && !input.name.isEmpty() ? input.name : input.port;
    }

    /** A new tile's caption: the port under a name, else nothing. */
    private static String defaultCaption(LibraryInput input) {
        return input.name != null && !input.name.isEmpty() ? input.port : "";
    }

    /** Another input for the tile; a title, caption or icon still at the old input's default follows the new one. */
    private InputTiles.Tile switchInput(InputTiles.Tile tile, LibraryInput to) {
        InputTiles.Tile next = tile.withInput(to.id);
        LibraryInput from = inputFor(tile.inputId);
        if (from == null) return next;
        if (tile.title.equals(InputTiles.clean(defaultTitle(from)))) next = next.withTitle(defaultTitle(to));
        if (tile.caption.equals(InputTiles.clean(defaultCaption(from)))) next = next.withCaption(defaultCaption(to));
        if (tile.icon.equals(from.icon)) next = next.withIcon(to.icon);
        return next;
    }

    private void chooseInput(InputTiles.Tile tile, View anchor) {
        dismissActions();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = menuWindow(menuPanel, dp(240));
        int current = 0;
        for (LibraryInput input : inputs) {
            if (input.id.equals(tile.inputId)) current = rows.getChildCount();
            addAction(rows, inputLabel(input.id), () -> {
                dismissActions();
                saveTile(switchInput(tile, input));
            });
        }
        placeMenu(menu, menuPanel, rows, anchor);
        if (current < rows.getChildCount()) rows.getChildAt(current).requestFocus();
    }

    private void askText(int title, String current, Consumer<String> done) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setText(current);
        field.setSelection(current.length());
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    hideKeyboard(field);
                    done.accept(InputTiles.clean(field.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> hideKeyboard(field))
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.setOnDismissListener(d -> focusInputLater());
        dialog.show();
    }

    /** The keyboard would otherwise stay up over the editor once the dialog is gone. */
    private void hideKeyboard(View field) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private GradientDrawable iconCell(boolean focused, boolean current) {
        GradientDrawable bg = surface(focused ? 0x33FFFFFF : current ? 0x1A75BBA4 : 0x00FFFFFF, 14);
        bg.setStroke(dp(focused ? 2 : 1), focused ? 0xE6FFFFFF : current ? 0x8075BBA4 : 0x00FFFFFF);
        return bg;
    }

    /** Every badge, then the owner's pictures, six across; the tile's current icon is marked and focused. */
    private void pickIcon(InputTiles.Tile tile) {
        dismissActions();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.glass_panel);
        box.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView title = text(activity.getString(R.string.input_icon_title), 17, Color.WHITE);
        title.setPadding(dp(6), 0, dp(6), dp(10));
        box.addView(title);
        String fallback = fallbackIcon(tile.inputId);
        String current = tile.icon.isEmpty() ? fallback : tile.icon;
        List<String> keys = new ArrayList<>();
        for (InputIcons.Icon icon : InputIcons.ALL) keys.add(icon.key);
        List<String> own = InputIcons.files(activity);
        keys.addAll(own);
        int columns = 6;
        PopupWindow picker = menuWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT);
        View focus = null;
        LinearLayout line = null;
        for (int i = 0; i < keys.size(); i++) {
            if (i % columns == 0) {
                line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(line);
            }
            String key = keys.get(i);
            boolean isCurrent = key.equals(current);
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), dp(8), dp(6), dp(6));
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(true);
            cell.setClickable(true);
            String name = iconName(key, fallback);
            cell.setContentDescription(name);
            cell.setBackground(iconCell(false, isCurrent));
            ImageView badge = new ImageView(activity);
            badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
            badge.setImageDrawable(InputIcons.drawable(activity, key, fallback));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(badge, new LinearLayout.LayoutParams(dp(72), dp(34)));
            TextView caption = text(name, 11, 0xCCFFFFFF);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
            captionParams.topMargin = dp(6);
            cell.addView(caption, captionParams);
            cell.setOnFocusChangeListener((v, focused) -> v.setBackground(iconCell(focused, isCurrent)));
            cell.setOnClickListener(v -> {
                dismissActions();
                saveTile(tile.withIcon(key));
            });
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(dp(96), dp(78));
            cellParams.setMargins(dp(3), dp(3), dp(3), dp(3));
            line.addView(cell, cellParams);
            if (focus == null || isCurrent) focus = cell;
        }
        TextView hint = text(activity.getString(R.string.input_icon_own), 11, 0x80FFFFFF);
        hint.setPadding(dp(6), dp(10), dp(6), 0);
        box.addView(hint);
        picker.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        if (focus != null) focus.requestFocus();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), dp(170));
            params.setMargins(dp(6), dp(2), dp(6), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(12), dp(10), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(44));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(2), dp(2), 0);
            row.addView(check, badge);
            return new Row(row);
        }
        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            bindIcon(holder.itemView.findViewById(R.id.icon), app);
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(label(app));
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = holder.itemView.findViewById(R.id.status);
            boolean home = onHome.test(pkg);
            boolean recent = !selectionMode && LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            status.setText(home ? R.string.library_on_home : recent ? R.string.library_recent : R.string.library_add_home);
            status.setVisibility(home || !okOpens ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setBackground(surface(home ? 0x1475BBA4 : 0x0CFFFFFF, 18));
            View check = holder.itemView.findViewById(R.id.dot);
            check.getLayoutParams().width = dp(home ? 18 : 8);
            check.getLayoutParams().height = dp(home ? 18 : 8);
            if (home) check.setBackgroundResource(R.drawable.library_check);
            else check.setBackground(surface(0xFF70D9A0, 8));
            check.setVisibility(home || recent ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> {
                boolean onHomeNow = onHome.test(pkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            });
            holder.itemView.setOnLongClickListener(selectionMode ? null : v -> { showActions(app, v); return true; });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    selectedPackage = pkg;
                    instructions.setText(hint(onHome.test(pkg)));
                    v.post(() -> { if (v.hasFocus() && catalog != null) highlight(v, true); });
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                }
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition();
                if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN && focusInputs()) return true;
                return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == apps.size() - 1);
            });
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
