package com.gathin.tvhome;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

/** Selects one configured provider; add new providers here without changing the wallpaper player. */
final class WallpaperProviders {
    interface Factory {
        String id();

        WallpaperProvider create(Context context, Config config);
    }

    private static final List<Factory> FACTORIES = Arrays.asList(new UnsplashWallpaper.Factory());

    private WallpaperProviders() { }

    static WallpaperProvider create(Context context, Config config) {
        if (!config.wallpaperEnabled || "local".equals(config.wallpaperProvider)) return null;
        String requested = config.wallpaperProvider;
        for (Factory factory : FACTORIES) {
            if (!accepts(requested, factory.id())) continue;
            WallpaperProvider provider = factory.create(context, config);
            if (provider.isConfigured()) return provider;
        }
        return null;
    }

    static boolean accepts(String requested, String providerId) {
        return "auto".equals(requested) || providerId.equals(requested);
    }
}
