package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WallpaperRotationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyNonemptySupportedImagesAreIncluded() throws Exception {
        File directory = temporary.newFolder();
        Files.write(new File(directory, "mountain.JPG").toPath(), new byte[]{1});
        Files.write(new File(directory, "valley.webp").toPath(), new byte[]{1});
        Files.write(new File(directory, "credits.md").toPath(), new byte[]{1});
        Files.createFile(new File(directory, "empty.png").toPath());
        new File(directory, "folder.jpg").mkdir();
        List<File> images = WallpaperRotation.images(directory);
        assertEquals(2, images.size());
        assertEquals("mountain.JPG", images.get(0).getName());
        assertEquals("valley.webp", images.get(1).getName());
    }

    @Test public void missingDirectoryAndNullProduceAnEmptyCollection() {
        assertTrue(WallpaperRotation.images(null).isEmpty());
        assertTrue(WallpaperRotation.images(new File(temporary.getRoot(), "missing")).isEmpty());
    }

    @Test public void selectionDoesNotImmediatelyRepeatWhenAlternativesExist() {
        List<File> images = Arrays.asList(new File("first.jpg"), new File("second.jpg"), new File("third.jpg"));
        Random random = new Random(42);
        for (int attempt = 0; attempt < 100; attempt++) {
            assertNotEquals("second.jpg", WallpaperRotation.pick(images, "second.jpg", random).getName());
        }
    }

    @Test public void emptyAndSingleImageCollectionsAreSafe() {
        assertNull(WallpaperRotation.pick(Collections.emptyList(), "", new Random(42)));
        File image = new File("only.jpg");
        assertEquals(image, WallpaperRotation.pick(Collections.singletonList(image), image.getName(), new Random(42)));
    }

    @Test public void persistedImageCanBeFoundAndMissingImageIsIgnored() {
        List<File> images = Arrays.asList(new File("first.jpg"), new File("second.jpg"));
        assertEquals(images.get(1), WallpaperRotation.find(images, "second.jpg"));
        assertNull(WallpaperRotation.find(images, "removed.jpg"));
    }

    @Test public void rotatesOnlyWhenDueAndHandlesClockMovingBackward() {
        assertFalse(WallpaperRotation.due(1_000, 2_000, 60_000));
        assertTrue(WallpaperRotation.due(1_000, 61_000, 60_000));
        assertTrue(WallpaperRotation.due(2_000, 1_000, 60_000));
        assertTrue(WallpaperRotation.due(0, 1_000, 60_000));
        assertFalse(WallpaperRotation.due(0, 1_000, 0));
    }

    @Test public void acceptsPresetAndCustomIntervalsAndFallsBackForInvalidValues() {
        assertEquals(5, WallpaperRotation.intervalMinutes(5, 30));
        assertEquals(15, WallpaperRotation.intervalMinutes(15, 30));
        assertEquals(30, WallpaperRotation.intervalMinutes(30, 15));
        assertEquals(1440, WallpaperRotation.intervalMinutes(1440, 30));
        assertEquals(30, WallpaperRotation.intervalMinutes(0, 30));
        assertEquals(30, WallpaperRotation.intervalMinutes(1441, 30));
        assertEquals(30, WallpaperRotation.intervalMinutes(5.5, 30));
        assertEquals(WallpaperRotation.SEQUENTIAL,
                WallpaperRotation.order("sequential", WallpaperRotation.SHUFFLE));
        assertEquals(WallpaperRotation.SHUFFLE,
                WallpaperRotation.order("random", WallpaperRotation.SHUFFLE));
    }

    @Test public void sequentialPlaybackKeepsImageUntilDueThenAdvancesAndWraps() {
        List<File> images = Arrays.asList(new File("first.jpg"), new File("second.jpg"), new File("third.jpg"));
        WallpaperRotation.Playback current = WallpaperRotation.select(images, "second.jpg", 10_000,
                10_001, 60_000, WallpaperRotation.SEQUENTIAL, new Random(42));
        assertEquals(images.get(1), current.image);
        assertEquals(10_000, current.changedAt);

        WallpaperRotation.Playback next = WallpaperRotation.select(images, "second.jpg", 10_000,
                70_000, 60_000, WallpaperRotation.SEQUENTIAL, new Random(42));
        assertEquals(images.get(2), next.image);
        assertEquals(70_000, next.changedAt);

        WallpaperRotation.Playback wrapped = WallpaperRotation.select(images, "third.jpg", 10_000,
                70_000, 60_000, WallpaperRotation.SEQUENTIAL, new Random(42));
        assertEquals(images.get(0), wrapped.image);
    }

    @Test public void restartResumesPersistedImageAndSingleImageNeverRepeats() {
        List<File> images = Arrays.asList(new File("first.jpg"), new File("second.jpg"));
        String persistedName = "second.jpg";
        long persistedTime = 20_000;
        WallpaperRotation.Playback afterRestart = WallpaperRotation.select(images, persistedName,
                persistedTime, 20_001, 300_000, WallpaperRotation.SEQUENTIAL, new Random(42));
        assertEquals("second.jpg", afterRestart.image.getName());
        assertEquals(persistedTime, afterRestart.changedAt);

        File only = new File("only.jpg");
        WallpaperRotation.Playback due = WallpaperRotation.select(Collections.singletonList(only),
                only.getName(), persistedTime, 320_000, 300_000, WallpaperRotation.SEQUENTIAL,
                new Random(42));
        assertEquals(only, due.image);
        assertEquals(320_000, due.changedAt);
    }

    @Test public void shuffledPlaybackDoesNotImmediatelyRepeatAndMissingCurrentStartsAtFirst() {
        List<File> images = Arrays.asList(new File("first.jpg"), new File("second.jpg"), new File("third.jpg"));
        WallpaperRotation.Playback next = WallpaperRotation.select(images, "second.jpg", 1,
                60_001, 60_000, WallpaperRotation.SHUFFLE, new Random(42));
        assertNotEquals("second.jpg", next.image.getName());
        WallpaperRotation.Playback initial = WallpaperRotation.select(images, "removed.jpg", 0,
                100, 60_000, WallpaperRotation.SEQUENTIAL, new Random(42));
        assertEquals(images.get(0), initial.image);
    }

    @Test public void companionPriorityRequiresSelectedCollectionAssetsToBeActive() {
        File legacyWallpaper = new File("wallpaper.jpg");
        File companionAsset = new File("companion-hash.jpg");
        assertFalse(WallpaperRotation.companionCollectionActive(false,
                Collections.singletonList(companionAsset), Collections.singletonList(legacyWallpaper)));
        assertFalse(WallpaperRotation.companionCollectionActive(true,
                Collections.singletonList(companionAsset), Collections.singletonList(legacyWallpaper)));
        assertTrue(WallpaperRotation.companionCollectionActive(true,
                Collections.singletonList(companionAsset), Collections.singletonList(companionAsset)));
    }
}
