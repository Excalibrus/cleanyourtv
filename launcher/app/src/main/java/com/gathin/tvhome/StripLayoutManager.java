package com.gathin.tvhome;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

/**
 * strip_row: the dock apps in a row of big tiles of their own, and every other app in one row of smaller tiles under
 * it that scrolls sideways while the dock stays put. The dock row is dockSize cells across the grid's inner width,
 * the strip stripColumns cells. Every item is laid out, those scrolled off screen too (a TV has a few dozen apps at
 * most), so the tile Left or Right goes to always exists to take focus and to be found by position.
 */
final class StripLayoutManager extends RecyclerView.LayoutManager implements RecyclerView.SmoothScroller.ScrollVectorProvider {

    /** Whether a scroll that brings a tile on screen animates (animations on, reduced motion off). */
    interface Motion {
        boolean smooth();
    }

    private final Context ctx;
    private final int dockSize;
    private final int stripColumns;
    private final Motion motion;
    /** How far the strip has scrolled to the left, in pixels; the dock row never moves. */
    private int stripScroll;
    private final Rect insets = new Rect();

    StripLayoutManager(Context ctx, int dockSize, int stripColumns, Motion motion) {
        this.ctx = ctx;
        this.dockSize = Math.max(1, dockSize);
        this.stripColumns = Math.max(1, stripColumns);
        this.motion = motion;
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean canScrollHorizontally() {
        return true;
    }

    private int inner() {
        return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private int stripCell() {
        return inner() / stripColumns;
    }

    /** The furthest the strip can scroll: its cells past the inner width, or 0 when they all fit. */
    private int maxScroll(int itemCount) {
        return Math.max(0, (itemCount - dockSize) * stripCell() - inner());
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        detachAndScrapAttachedViews(recycler);
        int count = state.getItemCount();
        if (count == 0 || getWidth() == 0) {
            stripScroll = 0;
            return;
        }
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int dockCell = inner() / dockSize;
        int stripTop = top;
        for (int i = 0; i < Math.min(dockSize, count); i++) {
            View v = addMeasured(recycler, i, dockCell);
            int x = left + i * dockCell;
            layoutDecorated(v, x, top, x + getDecoratedMeasuredWidth(v), top + getDecoratedMeasuredHeight(v));
            stripTop = Math.max(stripTop, top + getDecoratedMeasuredHeight(v));
        }
        int cell = stripCell();
        stripScroll = Math.max(0, Math.min(stripScroll, maxScroll(count)));
        for (int i = dockSize; i < count; i++) {
            View v = addMeasured(recycler, i, cell);
            int x = left + (i - dockSize) * cell - stripScroll;
            layoutDecorated(v, x, stripTop, x + getDecoratedMeasuredWidth(v), stripTop + getDecoratedMeasuredHeight(v));
        }
    }

    /** Adds the item at position, measured exactly a cell wide (its decoration included) and as tall as it wants. */
    private View addMeasured(RecyclerView.Recycler recycler, int position, int cell) {
        View v = recycler.getViewForPosition(position);
        addView(v);
        calculateItemDecorationsForChild(v, insets);
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, cell - insets.left - insets.right), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return v;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int target = Math.max(0, Math.min(maxScroll(state.getItemCount()), stripScroll + dx));
        int moved = target - stripScroll;
        if (moved == 0) return 0;
        stripScroll = target;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null && getPosition(child) >= dockSize) child.offsetLeftAndRight(-moved);
        }
        return moved;
    }

    /** Brings a strip tile's whole cell inside the padding, a tile at a time; dock tiles are always on screen. */
    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                 boolean immediate, boolean focusedChildVisible) {
        if (getPosition(child) < dockSize) return false;
        int dx = scrollToShow(getDecoratedLeft(child), getDecoratedRight(child));
        if (dx == 0) return false;
        if (immediate || !motion.smooth()) parent.scrollBy(dx, 0);
        else parent.smoothScrollBy(dx, 0);
        return true;
    }

    private int scrollToShow(int cellLeft, int cellRight) {
        if (cellLeft < getPaddingLeft()) return cellLeft - getPaddingLeft();
        int limit = getWidth() - getPaddingRight();
        return cellRight > limit ? cellRight - limit : 0;
    }

    @Override
    public void scrollToPosition(int position) {
        if (position < dockSize || getWidth() == 0) return;
        int cell = stripCell();
        int cellLeft = (position - dockSize) * cell;
        if (cellLeft < stripScroll) stripScroll = cellLeft;
        else if (cellLeft + cell > stripScroll + inner()) stripScroll = cellLeft + cell - inner();
        requestLayout();
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(ctx);
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }

    @Override
    public PointF computeScrollVectorForPosition(int targetPosition) {
        if (targetPosition < dockSize) return new PointF(0f, 0f);
        int cellLeft = (targetPosition - dockSize) * stripCell();
        return new PointF(Integer.signum(cellLeft - stripScroll), 0f);
    }
}
