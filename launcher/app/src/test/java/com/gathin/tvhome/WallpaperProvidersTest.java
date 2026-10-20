package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WallpaperProvidersTest {
    @Test public void autoSelectsConfiguredProviders() {
        assertTrue(WallpaperProviders.accepts("auto", "unsplash"));
        assertTrue(WallpaperProviders.accepts("auto", "future-provider"));
    }

    @Test public void explicitProviderSelectionDoesNotStartOtherProviders() {
        assertTrue(WallpaperProviders.accepts("unsplash", "unsplash"));
        assertFalse(WallpaperProviders.accepts("unsplash", "future-provider"));
        assertFalse(WallpaperProviders.accepts("local", "unsplash"));
    }

    @Test public void anUnknownProviderIsNotTreatedAsConfigured() {
        assertFalse(WallpaperProviders.accepts("future-provider", "unsplash"));
    }
}
