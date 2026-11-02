package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two rows on the home screen between the clock and the dock. "Continue watching" holds the shows the
 * streaming apps publish to Android TV's Watch Next list, and "New added" what they publish as new
 * content ({@link TvShows}); a tile opens the show right in its app. An app that has published nothing
 * yet is covered by the fallback: Continue watching shows the apps Home opened most recently
 * ({@link LastOpened}) and New added the most recently installed non-system apps, so the rows work
 * from day one and turn into shows as the apps fill the provider. Rows ride up with the grid scroll
 * (MainActivity translates the header block). A row with nothing at all stays hidden.
 */
final class HomeSections {
    private static final int MAX_TILES = 8;
    private static final float TILE_W_DP = 124f, TILE_H_DP = 70f, RADIUS_DP = 10f;
    /** One worker for the provider queries and the poster decodes; shared so recreate() adds no threads. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Context ctx;
    private final AppAdapter adapter;
    private final RecyclerView grid;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View recentTitle, recentScroll, newTitle, newScroll;
    private final LinearLayout recentRow, newRow;
    /** Bumped per refresh; a stale query or poster result finds it changed and drops itself. */
    private int generation;

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

    /** Rebuilds both rows. The provider is read off the UI thread; the views update when it answers. */
    void refresh() {
        final int gen = ++generation;
        final List<AppAdapter.Entry> apps = new ArrayList<>();
        for (AppAdapter.Entry e : adapter.homeEntries()) {
            if (e.kind == AppAdapter.Entry.APP || e.kind == AppAdapter.Entry.CARD) apps.add(e);
        }
        IO.execute(() -> {
            TvShows.nudgePublishersOnce(ctx);
            final List<TvShows.Show> watching = TvShows.continueWatching(ctx, MAX_TILES);
            final List<TvShows.Show> added = TvShows.newlyAdded(ctx, MAX_TILES);
            final List<AppAdapter.Entry> recentApps = watching.isEmpty() ? recentApps(apps) : Collections.emptyList();
            final List<AppAdapter.Entry> newApps = added.isEmpty() ? newestApps(apps) : Collections.emptyList();
            main.post(() -> {
                if (gen != generation) return;
                fill(recentRow, recentTitle, recentScroll, watching, recentApps, gen);
                fill(newRow, newTitle, newScroll, added, newApps, gen);
            });
        });
    }

    /** Focuses the first tile of the first shown row; false when both rows are hidden. */
    boolean focusFirst() {
        LinearLayout row = recentScroll.getVisibility() == View.VISIBLE ? recentRow
                : newScroll.getVisibility() == View.VISIBLE ? newRow : null;
        return row != null && row.getChildCount() > 0 && row.getChildAt(0).requestFocus();
    }

    // ---- data ------------------------------------------------------------------------------------

    private List<AppAdapter.Entry> recentApps(List<AppAdapter.Entry> apps) {
        List<AppAdapter.Entry> recent = new ArrayList<>();
        for (AppAdapter.Entry e : apps) {
            if (LastOpened.get(ctx, e.pkg) > 0) recent.add(e);
        }
        Collections.sort(recent, (a, b) -> Long.compare(LastOpened.get(ctx, b.pkg), LastOpened.get(ctx, a.pkg)));
        return recent.size() > MAX_TILES ? recent.subList(0, MAX_TILES) : recent;
    }

    private List<AppAdapter.Entry> newestApps(List<AppAdapter.Entry> apps) {
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
        return added.size() > MAX_TILES ? added.subList(0, MAX_TILES) : added;
    }

    // ---- views -----------------------------------------------------------------------------------

    private void fill(LinearLayout row, View title, View scroll,
                      List<TvShows.Show> shows, List<AppAdapter.Entry> apps, int gen) {
        row.removeAllViews();
        boolean show = !shows.isEmpty() || !apps.isEmpty();
        title.setVisibility(show ? View.VISIBLE : View.GONE);
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        for (TvShows.Show s : shows) row.addView(showTile(s, gen));
        for (AppAdapter.Entry e : apps) row.addView(appTile(e));
    }

    /** A show: its poster over a dark card that carries the title until (or instead of) the art. */
    private View showTile(TvShows.Show s, int gen) {
        FrameLayout card = card(s.title, () -> open(s));
        TextView name = new TextView(ctx);
        name.setText(s.title);
        name.setTextColor(0xD9FFFFFF);
        name.setTextSize(11f);
        name.setMaxLines(3);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        int pad = dp(8);
        name.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        card.addView(name, nameLp);
        if (s.poster != null) {
            ImageView poster = new ImageView(ctx);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setAlpha(0f);
            card.addView(poster, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            final int reqW = dp(TILE_W_DP) * 2;   // the focus zoom looks crisp with twice the rest size
            IO.execute(() -> {
                final Bitmap art = TvShows.loadPoster(ctx, s.poster, reqW);
                if (art == null) return;
                main.post(() -> {
                    if (gen != generation) return;
                    poster.setImageBitmap(art);
                    poster.animate().alpha(1f).setDuration(180).start();
                });
            });
        }
        return card;
    }

    /** The fallback tile: the app's banner, or its icon on the dark card. */
    private View appTile(AppAdapter.Entry e) {
        FrameLayout card = card(e.label, () -> adapter.launchEntry(e));
        if (e.banner != null) {
            ImageView banner = new ImageView(ctx);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(copy(e.banner));
            card.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (e.icon != null) {
            ImageView icon = new ImageView(ctx);
            icon.setImageDrawable(copy(e.icon));
            card.addView(icon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        }
        return card;
    }

    /** The shared card: size, corners, focus ring and zoom, click, row-end key fences. */
    private FrameLayout card(String label, Runnable click) {
        FrameLayout card = new FrameLayout(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W_DP), dp(TILE_H_DP));
        lp.setMarginEnd(dp(10));
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.tile_bg);
        card.setForeground(ctx.getDrawable(R.drawable.section_focus));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(label);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS_DP));
            }
        });
        card.setClipToOutline(true);
        card.setOnClickListener(v -> click.run());
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

    private void open(TvShows.Show s) {
        Intent intent = TvShows.launchIntent(ctx, s);
        try {
            if (intent == null) throw new IllegalStateException();
            ctx.startActivity(intent);
            if (s.pkg != null) LastOpened.record(ctx, s.pkg);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, s.title), Toast.LENGTH_SHORT).show();
        }
    }

    /** A private copy: the same drawable instance is already shown by the app's grid tile. */
    private Drawable copy(Drawable d) {
        return d.getConstantState() != null ? d.getConstantState().newDrawable(ctx.getResources()) : d;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
