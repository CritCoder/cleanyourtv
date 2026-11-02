package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Shows for the home sections, read from Android TV's own TvProvider: the cross-app Watch Next list
 * ("Continue watching": what the streaming apps say the owner is in the middle of) and the apps'
 * preview-channel programs ("New added": what they publish as new or recommended). Everything is local
 * to the TV; the one network use is fetching the poster art the rows point at (usually https).
 * Needs android.permission.READ_TV_LISTINGS; with it missing or the tables empty, every call just
 * returns an empty list and the sections fall back to app tiles.
 */
final class TvShows {
    static final class Show {
        final String title;
        final String poster;    // poster_art_uri, may be null
        final String intentUri; // intent_uri, may be null
        final String pkg;

        Show(String title, String poster, String intentUri, String pkg) {
            this.title = title;
            this.poster = poster;
            this.intentUri = intentUri;
            this.pkg = pkg;
        }
    }

    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    /** TvContract watch_next_type: 0 CONTINUE, 1 NEXT, 2 NEW, 3 WATCHLIST. */
    private static final int TYPE_CONTINUE = 0, TYPE_NEXT = 1;
    /** Channels whose programs read as "newly added" content. */
    private static final Pattern NEW_CHANNEL = Pattern.compile("new|latest|recent|added|trending", Pattern.CASE_INSENSITIVE);

    private TvShows() {
    }

    /** Watch Next rows of kind continue/next-episode, most recently engaged first. */
    static List<Show> continueWatching(Context ctx, int limit) {
        List<Object[]> rows = new ArrayList<>();   // {engagement, Show}
        try (Cursor c = ctx.getContentResolver().query(WATCH_NEXT, null, null, null, null)) {
            if (c == null) return Collections.emptyList();
            int title = c.getColumnIndex("title"), episode = c.getColumnIndex("episode_title");
            int poster = c.getColumnIndex("poster_art_uri"), intent = c.getColumnIndex("intent_uri");
            int pkg = c.getColumnIndex("package_name"), kind = c.getColumnIndex("watch_next_type");
            int engaged = c.getColumnIndex("last_engagement_time_utc_millis"), browsable = c.getColumnIndex("browsable");
            while (c.moveToNext()) {
                if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue;
                int t = kind >= 0 && !c.isNull(kind) ? c.getInt(kind) : TYPE_CONTINUE;
                if (t != TYPE_CONTINUE && t != TYPE_NEXT) continue;
                Show s = show(c, title, episode, poster, intent, pkg);
                if (s == null) continue;
                rows.add(new Object[]{engaged >= 0 && !c.isNull(engaged) ? c.getLong(engaged) : 0L, s});
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        Collections.sort(rows, (a, b) -> Long.compare((long) b[0], (long) a[0]));
        return dedup(rows, limit);
    }

    /**
     * Newly added content: Watch Next rows the apps mark NEW or WATCHLIST, then programs from preview
     * channels whose name reads as new/latest/trending, then the rest of the preview programs, newest
     * first within each group.
     */
    static List<Show> newlyAdded(Context ctx, int limit) {
        List<Object[]> rows = new ArrayList<>();   // {groupRank desc-key, Show}
        try (Cursor c = ctx.getContentResolver().query(WATCH_NEXT, null, null, null, null)) {
            if (c != null) {
                int title = c.getColumnIndex("title"), episode = c.getColumnIndex("episode_title");
                int poster = c.getColumnIndex("poster_art_uri"), intent = c.getColumnIndex("intent_uri");
                int pkg = c.getColumnIndex("package_name"), kind = c.getColumnIndex("watch_next_type");
                int browsable = c.getColumnIndex("browsable"), id = c.getColumnIndex("_id");
                while (c.moveToNext()) {
                    if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue;
                    int t = kind >= 0 && !c.isNull(kind) ? c.getInt(kind) : TYPE_CONTINUE;
                    if (t == TYPE_CONTINUE || t == TYPE_NEXT) continue;   // those are Continue watching's
                    Show s = show(c, title, episode, poster, intent, pkg);
                    if (s != null) rows.add(new Object[]{3e18 + rowId(c, id), s});
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Map<Long, String> channelName = new HashMap<>();
            try (Cursor c = ctx.getContentResolver().query(CHANNELS, null, null, null, null)) {
                if (c != null) {
                    int id = c.getColumnIndex("_id"), name = c.getColumnIndex("display_name");
                    while (c.moveToNext()) {
                        if (id >= 0) channelName.put(c.getLong(id), name >= 0 && !c.isNull(name) ? c.getString(name) : "");
                    }
                }
            }
            try (Cursor c = ctx.getContentResolver().query(PREVIEW, null, null, null, null)) {
                if (c != null) {
                    int title = c.getColumnIndex("title"), episode = c.getColumnIndex("episode_title");
                    int poster = c.getColumnIndex("poster_art_uri"), intent = c.getColumnIndex("intent_uri");
                    int pkg = c.getColumnIndex("package_name"), channel = c.getColumnIndex("channel_id");
                    int browsable = c.getColumnIndex("browsable"), id = c.getColumnIndex("_id");
                    while (c.moveToNext()) {
                        if (browsable >= 0 && !c.isNull(browsable) && c.getInt(browsable) == 0) continue;
                        Show s = show(c, title, episode, poster, intent, pkg);
                        if (s == null) continue;
                        String name = channel >= 0 ? channelName.get(c.getLong(channel)) : null;
                        boolean fresh = name != null && NEW_CHANNEL.matcher(name).find();
                        rows.add(new Object[]{(fresh ? 2e18 : 1e18) + rowId(c, id), s});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(rows, (a, b) -> Double.compare((double) b[0], (double) a[0]));
        return dedup(rows, limit);
    }

    private static long rowId(Cursor c, int idColumn) {
        return idColumn >= 0 && !c.isNull(idColumn) ? c.getLong(idColumn) : 0L;
    }

    private static Show show(Cursor c, int title, int episode, int poster, int intent, int pkg) {
        String name = title >= 0 && !c.isNull(title) ? c.getString(title) : null;
        if (name == null || name.trim().isEmpty()) return null;
        String ep = episode >= 0 && !c.isNull(episode) ? c.getString(episode) : null;
        if (ep != null && !ep.trim().isEmpty()) name = name + " · " + ep;
        return new Show(Cards.cleanLabel(name, ""),
                poster >= 0 && !c.isNull(poster) ? c.getString(poster) : null,
                intent >= 0 && !c.isNull(intent) ? c.getString(intent) : null,
                pkg >= 0 && !c.isNull(pkg) ? c.getString(pkg) : null);
    }

    /** One tile per package+title, keeping the higher-ranked occurrence. */
    private static List<Show> dedup(List<Object[]> ranked, int limit) {
        List<Show> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object[] row : ranked) {
            Show s = (Show) row[1];
            if (!seen.add(s.pkg + '\n' + s.title)) continue;
            out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The intent a tile fires: the row's own intent_uri, or the app's start screen without one. */
    static Intent launchIntent(Context ctx, Show s) {
        if (s.intentUri != null) {
            try {
                return Intent.parseUri(s.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (Exception ignored) {
            }
        }
        if (s.pkg == null) return null;
        Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(s.pkg);
        if (app == null) app = ctx.getPackageManager().getLaunchIntentForPackage(s.pkg);
        return app != null ? app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : null;
    }

    // ---- poster art ------------------------------------------------------------------------------

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    /** Decodes the poster at roughly reqW wide: content:// directly, http(s) through a small disk cache. */
    static Bitmap loadPoster(Context ctx, String uri, int reqW) {
        Bitmap cached = MEM.get(uri);
        if (cached != null) return cached;
        try {
            Bitmap bmp;
            if (uri.startsWith("content:")) {
                bmp = decode(() -> ctx.getContentResolver().openInputStream(Uri.parse(uri)), reqW);
            } else if (uri.startsWith("http:") || uri.startsWith("https:")) {
                File f = cacheFile(ctx, uri);
                if (!f.isFile() && !download(uri, f)) return null;
                bmp = decode(() -> new FileInputStream(f), reqW);
            } else {
                return null;
            }
            if (bmp != null) MEM.put(uri, bmp);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private interface StreamSource {
        InputStream open() throws Exception;
    }

    private static Bitmap decode(StreamSource src, int reqW) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = src.open()) {
            BitmapFactory.decodeStream(in, null, opts);
        }
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= reqW) sample *= 2;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        real.inPreferredConfig = Bitmap.Config.RGB_565;   // posters behind 16:9 crops; 2 bytes a pixel is plenty
        try (InputStream in = src.open()) {
            return BitmapFactory.decodeStream(in, null, real);
        }
    }

    private static File cacheFile(Context ctx, String uri) throws Exception {
        File dir = new File(ctx.getCacheDir(), "posters");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no poster cache dir");
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        StringBuilder name = new StringBuilder();
        for (byte b : md.digest(uri.getBytes("UTF-8"))) name.append(String.format("%02x", b));
        return new File(dir, name.toString());
    }

    /** At most ~3 MB per poster, written to a temp file first so a cut connection leaves no half file. */
    private static boolean download(String uri, File to) {
        File tmp = new File(to.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int total = 0, n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 3 * 1024 * 1024) return false;
                    out.write(buf, 0, n);
                }
            }
            return tmp.renameTo(to);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.isFile()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    // ---- getting apps to publish -----------------------------------------------------------------

    private static final AtomicBoolean NUDGED = new AtomicBoolean();

    /**
     * Asks every TV app to publish its channels and Watch Next rows, the way the stock launcher does
     * after an install: the INITIALIZE_PROGRAMS broadcast, one explicit send per package, once per
     * launcher process. Apps that have nothing to say ignore it.
     */
    static void nudgePublishersOnce(Context ctx) {
        if (!NUDGED.compareAndSet(false, true)) return;
        try {
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                ctx.sendBroadcast(new Intent("android.media.tv.action.INITIALIZE_PROGRAMS").setPackage(pkg));
            }
        } catch (Exception ignored) {
        }
    }
}
