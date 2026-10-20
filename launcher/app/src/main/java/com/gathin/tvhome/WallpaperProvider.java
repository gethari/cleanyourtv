package com.gathin.tvhome;

import android.graphics.Bitmap;

/** A network or account-backed source that can supply launcher wallpapers when explicitly configured. */
interface WallpaperProvider {
    String id();

    boolean isConfigured();

    boolean due();

    Image load(int width, int height);

    /** A decoded image and the attribution the source requires the launcher to show. */
    final class Image {
        final Bitmap bitmap;
        final String id;
        final String credit;
        final String description;
        final String link;

        Image(Bitmap bitmap, String id, String credit, String description, String link) {
            this.bitmap = bitmap;
            this.id = id;
            this.credit = credit;
            this.description = description;
            this.link = link;
        }
    }
}
