package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TileLayoutTest {
    @Test public void rejectsOutOfRangeThemeValues() {
        assertEquals(100, TileLayout.bounded(0, 50, 100, 100));
        assertEquals(100, TileLayout.bounded(150, 50, 100, 100));
        assertEquals(70, TileLayout.bounded(70, 50, 100, 100));
        assertEquals(0, TileLayout.bounded(0, 0, 100, 100));
    }
}
