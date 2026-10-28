package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Personal tile size and vertical position, independent of the number of columns. */
final class TileLayout {
    static final String SCALE = "tile_scale";
    static final String POSITION = "tile_position";
    final int scale;
    final int position;

    TileLayout(Context context) {
        int[] defaults = Config.tileLayoutValues(context);
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        scale = bounded(choices.getInt(SCALE, defaults[0]), 50, 100, 100);
        position = bounded(choices.getInt(POSITION, defaults[1]), 0, 100, 100);
    }

    static int bounded(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    int width(int original) { return Math.max(1, Math.round(original * scale / 100f)); }
    int top(int minimum, int bottom) { return minimum + Math.round(Math.max(0, bottom - minimum) * position / 100f); }
}
