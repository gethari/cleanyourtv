package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class WallpaperRotation {
    static final String SEQUENTIAL = "sequential";
    static final String SHUFFLE = "shuffle";
    static final int DEFAULT_INTERVAL_MINUTES = 30;

    static final class Playback {
        final File image;
        final long changedAt;

        Playback(File image, long changedAt) {
            this.image = image;
            this.changedAt = changedAt;
        }
    }

    static int intervalMinutes(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        double number = ((Number) value).doubleValue();
        return number >= 1 && number <= 1440 && number == Math.floor(number)
                ? (int) number : fallback;
    }

    static String order(Object value, String fallback) {
        return SEQUENTIAL.equals(value) || SHUFFLE.equals(value) ? (String) value : fallback;
    }

    static boolean companionCollectionActive(boolean collectionSelected, List<File> companionAssets,
                                             List<File> activeWallpapers) {
        if (!collectionSelected || companionAssets == null || companionAssets.isEmpty()
                || activeWallpapers == null || activeWallpapers.isEmpty()) return false;
        for (File companionAsset : companionAssets) {
            if (activeWallpapers.contains(companionAsset)) return true;
        }
        return false;
    }

    /** Selects the persisted image until its interval expires, then advances in collection order. */
    static Playback select(List<File> images, String previous, long changedAt, long now,
                           long interval, String order, Random random) {
        File current = find(images, previous);
        if (current != null && !due(changedAt, now, interval)) return new Playback(current, changedAt);
        if (images == null || images.isEmpty()) return new Playback(null, changedAt);

        File next;
        if (SHUFFLE.equals(order)) {
            next = pick(images, previous, random);
        } else {
            int currentIndex = current == null ? -1 : images.indexOf(current);
            next = images.get((currentIndex + 1) % images.size());
        }
        return new Playback(next, now);
    }

    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        if (images == null) return null;
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    static boolean due(long previous, long now, long interval) {
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval);
    }
}
