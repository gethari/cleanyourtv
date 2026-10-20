package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class WallpaperPlayer {
    private final UnsplashWallpaper unsplash;
    private final android.widget.TextView credit;
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final boolean companionCollectionActive;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        unsplash = new UnsplashWallpaper(context, config);
        credit = new android.widget.TextView(context);
        credit.setTextColor(android.graphics.Color.WHITE);
        credit.setTextSize(12);
        credit.setPadding(12, 6, 12, 6);
        credit.setBackgroundColor(0x99000000);
        credit.setVisibility(View.GONE);
        credit.setFocusable(true);
        credit.setOnFocusChangeListener((view, focused) ->
                view.setBackgroundColor(focused ? 0xFF444444 : 0x99000000));
        FrameLayout.LayoutParams creditLayout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT);
        creditLayout.setMargins(0, 0, 24, 12);
        root.addView(credit, creditLayout);
        files = new ArrayList<>(config.wallpapers);
        PlaybackSettings playback = playbackSettings(context, config, files);
        interval = playback.intervalMinutes * 60_000L;
        order = playback.order;
        companionCollectionActive = playback.companionCollectionActive;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!files.isEmpty() || unsplash.enabled()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View shade = new View(context);
            shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(shade, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading) return;
        // An active companion collection is the TV-owned source and stays offline-capable.
        if (!companionCollectionActive && unsplash.enabled() && (unsplash.due() || displayed.isEmpty())) {
            loading = true;
            decoder.execute(() -> {
                UnsplashWallpaper.Photo photo = unsplash.load(width, height);
                root.post(() -> {
                    loading = false;
                    if (closed || !active) {
                        if (photo != null) photo.bitmap.recycle();
                        return;
                    }
                    if (photo != null) {
                        image.setImageBitmap(photo.bitmap);
                        displayed = "unsplash:" + photo.id;
                        credit.setText("Photo: " + photo.photographer + " / Unsplash");
                        credit.setContentDescription("Photo by " + photo.photographer
                                + " on Unsplash. Open photo credits.");
                        credit.setVisibility(View.VISIBLE);
                        credit.setOnClickListener(view -> {
                            try {
                                root.getContext().startActivity(new android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(photo.link)));
                            } catch (android.content.ActivityNotFoundException unavailable) {
                                android.widget.Toast.makeText(root.getContext(),
                                        "Photo by " + photo.photographer + " on Unsplash",
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                        Log.i("TvHome.Wallpaper", "Showing Unsplash photo " + photo.id);
                    } else {
                        updateOffline();
                    }
                });
            });
            return;
        }
        if (!companionCollectionActive && unsplash.enabled() && displayed.startsWith("unsplash:")) return;
        updateOffline();
    }

    private void updateOffline() {
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        long changed = preferences.getLong("changed", 0);
        WallpaperRotation.Playback selection = WallpaperRotation.select(files, previous, changed,
                System.currentTimeMillis(), interval, order, random);
        File selected = selection.image;
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                credit.setVisibility(View.GONE);
                image.setImageBitmap(bitmap);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0
                        || selection.changedAt != changed) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", selection.changedAt).apply();
                }
                Log.i("TvHome.Wallpaper", "Showing " + displayed);
            });
        });
    }

    private static final class PlaybackSettings {
        final int intervalMinutes;
        final String order;
        final boolean companionCollectionActive;

        PlaybackSettings(int intervalMinutes, String order, boolean companionCollectionActive) {
            this.intervalMinutes = intervalMinutes;
            this.order = order;
            this.companionCollectionActive = companionCollectionActive;
        }
    }

    /** Reads playback policy from the active collection, falling back to legacy theme settings. */
    private static PlaybackSettings playbackSettings(Context context, Config config,
                                                     List<File> activeWallpapers) {
        int interval = WallpaperRotation.intervalMinutes(config.wallpaperRotationMinutes,
                WallpaperRotation.DEFAULT_INTERVAL_MINUTES);
        String order = WallpaperRotation.SHUFFLE;
        boolean selectedCollectionFound = false;
        try {
            JSONObject state = CompanionProvider.store(context).state();
            JSONObject settings = state.optJSONObject("settings");
            if (settings != null) {
                interval = WallpaperRotation.intervalMinutes(settings.opt("wallpaper_rotation_minutes"), interval);
                order = WallpaperRotation.order(settings.opt("wallpaper_order"), order);
            }
            String activeId = state.optString("active_collection", "");
            JSONArray collections = state.optJSONArray("collections");
            if (collections != null) {
                for (int i = 0; i < collections.length(); i++) {
                    JSONObject collection = collections.optJSONObject(i);
                    if (collection == null || !activeId.equals(collection.optString("id"))) continue;
                    JSONArray assets = collection.optJSONArray("assets");
                    selectedCollectionFound = assets != null && assets.length() > 0;
                    interval = WallpaperRotation.intervalMinutes(collection.opt("interval_minutes"), interval);
                    order = WallpaperRotation.order(collection.opt("order"), order);
                    break;
                }
            }
        } catch (Exception ignored) {
            // A malformed or unavailable companion state must not stop the legacy wallpaper.
        }
        boolean companionActive = WallpaperRotation.companionCollectionActive(selectedCollectionFound,
                CompanionProvider.selectedAssets(context), activeWallpapers);
        return new PlaybackSettings(interval, order, companionActive);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > Math.max(1, width) * 2
                    || options.outHeight / sample > Math.max(1, height) * 2) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
