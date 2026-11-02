package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two rows on the home screen between the clock and the dock: "Continue watching", the apps Home opened
 * most recently ({@link LastOpened}), and "New added", the most recently installed non-system apps. The
 * rows live inside the header block, so they ride up with the grid scroll like the rest of the header.
 * Rebuilt from the adapter's tiles on every reload; a row with nothing to show stays hidden.
 */
final class HomeSections {
    private static final int MAX_TILES = 8;
    private static final float TILE_W_DP = 104f, TILE_H_DP = 58f, RADIUS_DP = 10f;

    private final Context ctx;
    private final AppAdapter adapter;
    private final RecyclerView grid;
    private final float density;
    private final View recentTitle, recentScroll, newTitle, newScroll;
    private final LinearLayout recentRow, newRow;

    HomeSections(Context ctx, View sectionsBlock, AppAdapter adapter, RecyclerView grid) {
        this.ctx = ctx;
        this.adapter = adapter;
        this.grid = grid;
        this.density = ctx.getResources().getDisplayMetrics().density;
        recentTitle = sectionsBlock.findViewById(R.id.recentTitle);
        recentScroll = sectionsBlock.findViewById(R.id.recentScroll);
        recentRow = sectionsBlock.findViewById(R.id.recentRow);
        newTitle = sectionsBlock.findViewById(R.id.newTitle);
        newScroll = sectionsBlock.findViewById(R.id.newScroll);
        newRow = sectionsBlock.findViewById(R.id.newRow);
    }

    /** Rebuilds both rows from the adapter's current tiles. Call after every adapter reload. */
    void refresh() {
        List<AppAdapter.Entry> apps = new ArrayList<>();
        for (AppAdapter.Entry e : adapter.homeEntries()) {
            if (e.kind == AppAdapter.Entry.APP || e.kind == AppAdapter.Entry.CARD) apps.add(e);
        }

        List<AppAdapter.Entry> recent = new ArrayList<>();
        for (AppAdapter.Entry e : apps) {
            if (LastOpened.get(ctx, e.pkg) > 0) recent.add(e);
        }
        Collections.sort(recent, (a, b) -> Long.compare(LastOpened.get(ctx, b.pkg), LastOpened.get(ctx, a.pkg)));
        if (recent.size() > MAX_TILES) recent = recent.subList(0, MAX_TILES);

        PackageManager pm = ctx.getPackageManager();
        List<AppAdapter.Entry> added = new ArrayList<>();
        List<Long> addedAt = new ArrayList<>();
        for (AppAdapter.Entry e : apps) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
                // Preloads all share the firmware's build date; only apps the owner installed count as new.
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                long t = pm.getPackageInfo(e.pkg, 0).firstInstallTime;
                if (t <= 0) continue;
                int at = 0;
                while (at < addedAt.size() && addedAt.get(at) >= t) at++;
                added.add(at, e);
                addedAt.add(at, t);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (added.size() > MAX_TILES) added = added.subList(0, MAX_TILES);

        fill(recentRow, recent, recentTitle, recentScroll);
        fill(newRow, added, newTitle, newScroll);
    }

    /** Focuses the first tile of the first shown row; false when both rows are hidden. */
    boolean focusFirst() {
        LinearLayout row = recentScroll.getVisibility() == View.VISIBLE ? recentRow
                : newScroll.getVisibility() == View.VISIBLE ? newRow : null;
        return row != null && row.getChildCount() > 0 && row.getChildAt(0).requestFocus();
    }

    private void fill(LinearLayout row, List<AppAdapter.Entry> entries, View title, View scroll) {
        row.removeAllViews();
        boolean show = !entries.isEmpty();
        title.setVisibility(show ? View.VISIBLE : View.GONE);
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        for (AppAdapter.Entry e : entries) row.addView(tile(e));
    }

    private View tile(AppAdapter.Entry e) {
        FrameLayout card = new FrameLayout(ctx);
        int w = dp(TILE_W_DP), h = dp(TILE_H_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(dp(10));
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.tile_bg);
        card.setForeground(ctx.getDrawable(R.drawable.section_focus));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(e.label);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS_DP));
            }
        });
        card.setClipToOutline(true);

        if (e.banner != null) {
            ImageView banner = new ImageView(ctx);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(copy(e.banner));
            card.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (e.icon != null) {
            ImageView icon = new ImageView(ctx);
            icon.setImageDrawable(copy(e.icon));
            card.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        }

        card.setOnClickListener(v -> adapter.launchEntry(e));
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).setDuration(160).start();
            if (focused) {
                // Focus can only arrive here from the top of the page: make sure the header is fully back.
                int off = grid.computeVerticalScrollOffset();
                if (off > 0) grid.smoothScrollBy(0, -off);
            }
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            ViewGroup row = (ViewGroup) v.getParent();
            int i = row.indexOfChild(v);
            // Swallow the row's ends so focus never wraps somewhere far away.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return i == 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return i == row.getChildCount() - 1;
            return false;
        });
        return card;
    }

    /** A private copy: the same drawable instance is already shown by the app's grid tile. */
    private Drawable copy(Drawable d) {
        return d.getConstantState() != null ? d.getConstantState().newDrawable(ctx.getResources()) : d;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
