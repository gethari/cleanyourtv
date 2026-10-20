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
}
