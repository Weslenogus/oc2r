/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.screen.ColorDepth;
import li.cil.oc2.common.machine.screen.ColorFormat;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.machine.screen.TextBufferDelta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextBufferTest {
    private static TextBuffer tierThree() {
        return new TextBuffer(160, 50, ColorDepth.EIGHT_BIT);
    }

    private static String row(final TextBuffer buffer, final int y, final int length) {
        final StringBuilder builder = new StringBuilder(length);
        for (int x = 0; x < length; x++) {
            builder.append(buffer.getRawChar(x, y));
        }
        return builder.toString();
    }

    @Test
    void writesAndReadsBackText() {
        final TextBuffer buffer = tierThree();
        assertTrue(buffer.set(2, 1, "hello", false));
        assertEquals("  hello", row(buffer, 1, 7));
        assertEquals('h', buffer.getChar(2, 1));
    }

    @Test
    void writesVertically() {
        final TextBuffer buffer = tierThree();
        buffer.set(0, 0, "abc", true);
        assertEquals('a', buffer.getChar(0, 0));
        assertEquals('b', buffer.getChar(0, 1));
        assertEquals('c', buffer.getChar(0, 2));
    }

    @Test
    void clipsTextAtTheRightEdgeInsteadOfWrapping() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(10, 5);
        buffer.set(8, 0, "abcdef", false);
        assertEquals("        ab", row(buffer, 0, 10));
        // Nothing may have spilled onto the next row.
        assertEquals("          ", row(buffer, 1, 10));
    }

    @Test
    void doubleWidthCharactersClaimTwoColumns() {
        final TextBuffer buffer = tierThree();
        // U+4E2D is a CJK ideograph, which is two columns wide.
        buffer.set(0, 0, "中x", false);
        assertEquals('中', buffer.getRawChar(0, 0));
        assertEquals(TextBuffer.WIDE_CHAR_CONTINUATION, buffer.getRawChar(1, 0));
        assertEquals('x', buffer.getRawChar(2, 0));
        // Reading the right half reports the character itself.
        assertEquals('中', buffer.getChar(1, 0));
    }

    @Test
    void overwritingHalfOfAWideCharacterRemovesBothHalves() {
        final TextBuffer buffer = tierThree();
        buffer.set(0, 0, "中", false);

        // Overwrite the left half.
        buffer.set(0, 0, "a", false);
        assertEquals('a', buffer.getRawChar(0, 0));
        assertEquals(' ', buffer.getRawChar(1, 0),
            "the orphaned continuation marker should have been cleared");

        // And now the right half of a fresh one.
        buffer.set(4, 0, "中", false);
        buffer.set(5, 0, "b", false);
        assertEquals(' ', buffer.getRawChar(4, 0),
            "the orphaned left half should have been cleared");
        assertEquals('b', buffer.getRawChar(5, 0));
    }

    @Test
    void aWideCharacterInTheLastColumnBecomesASpace() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(4, 2);
        buffer.set(3, 0, "中", false);
        // There is nowhere to put the second half, so drawing half a glyph is not an option.
        assertEquals(' ', buffer.getRawChar(3, 0));
    }

    @Test
    void fillsRectangles() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(10, 5);
        assertTrue(buffer.fill(2, 1, 3, 2, '#'));
        assertEquals("  ###     ", row(buffer, 1, 10));
        assertEquals("  ###     ", row(buffer, 2, 10));
        assertEquals("          ", row(buffer, 0, 10));
    }

    @Test
    void fillClipsToTheBuffer() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(4, 2);
        assertTrue(buffer.fill(-2, -2, 100, 100, '#'));
        assertEquals("####", row(buffer, 0, 4));
        assertEquals("####", row(buffer, 1, 4));
    }

    @Test
    void copiesOverlappingRegionsWithoutSmearing() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(10, 5);
        buffer.set(0, 0, "abcdefghij", false);

        // Shift right by two, which overlaps: a naive forward copy would repeat "ab" across.
        assertTrue(buffer.copy(0, 0, 8, 1, 2, 0));
        assertEquals("ababcdefgh", row(buffer, 0, 10));
    }

    @Test
    void copyScrollsRowsUpwardsWithoutLosingLines() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(4, 4);
        buffer.set(0, 0, "aaaa", false);
        buffer.set(0, 1, "bbbb", false);
        buffer.set(0, 2, "cccc", false);

        // Scroll everything up one row, the way a terminal does at the bottom of the screen.
        assertTrue(buffer.copy(0, 1, 4, 3, 0, -1));
        assertEquals("bbbb", row(buffer, 0, 4));
        assertEquals("cccc", row(buffer, 1, 4));
    }

    @Test
    void copyClipsWhenTheDestinationWouldLeaveTheBuffer() {
        final TextBuffer buffer = tierThree();
        buffer.setResolution(4, 2);
        buffer.set(0, 0, "abcd", false);
        // Most of this lands outside; what remains must still be correct rather than throwing.
        buffer.copy(0, 0, 4, 1, 2, 0);
        assertEquals("abab", row(buffer, 0, 4));
    }

    @Test
    void resolvesPaletteColoursThroughTheCurrentDepth() {
        final TextBuffer buffer = tierThree();
        buffer.setPaletteColor(3, 0x336699);
        buffer.setForeground(3, true);
        buffer.setBackground(0x000000, false);
        buffer.set(0, 0, "x", false);

        assertEquals(3, buffer.getForegroundPaletteIndexAt(0, 0));
        assertEquals(0x336699, buffer.getForegroundAt(0, 0));
        assertTrue(buffer.isForegroundFromPalette());
        assertEquals(3, buffer.getForegroundValue());
    }

    @Test
    void eightBitFormatRoundTripsTheColourCube() {
        final ColorFormat format = new ColorFormat(ColorDepth.EIGHT_BIT);
        // Every index in the fixed cube must survive being expanded and packed again, or colours
        // would drift every time a program read a cell and wrote it back.
        for (int index = 16; index < 256; index++) {
            final int rgb = format.inflate(index);
            assertEquals(index, format.deflate(rgb),
                "index " + index + " did not round trip (rgb " + Integer.toHexString(rgb) + ")");
        }
    }

    @Test
    void fourBitFormatUsesTheStandardPalette() {
        final ColorFormat format = new ColorFormat(ColorDepth.FOUR_BIT);
        assertEquals(16, format.getPaletteSize());
        assertEquals(0xFFFFFF, format.inflate(0));
        assertEquals(0x000000, format.inflate(15));
        assertEquals(0, format.deflate(0xFFFFFF));
        assertEquals(15, format.deflate(0x000000));
        assertTrue(format.isFromPalette(7));
    }

    @Test
    void oneBitFormatIsBlackAndWhite() {
        final ColorFormat format = new ColorFormat(ColorDepth.ONE_BIT);
        assertEquals(0, format.getPaletteSize());
        assertEquals(0x000000, format.inflate(0));
        assertEquals(0xFFFFFF, format.inflate(1));
        assertEquals(0, format.deflate(0x101010));
        assertEquals(1, format.deflate(0xEEEEEE));
        assertFalse(format.isFromPalette(0));
    }

    @Test
    void tracksDirtySpansPerRow() {
        final TextBuffer buffer = tierThree();
        buffer.clearDirty();
        assertFalse(buffer.isDirty());

        buffer.set(10, 3, "abc", false);
        assertTrue(buffer.isDirty());
        assertTrue(buffer.isRowDirty(3));
        assertFalse(buffer.isRowDirty(4));
        assertEquals(10, buffer.getDirtyMin(3));
        assertEquals(12, buffer.getDirtyMax(3));

        buffer.clearDirty();
        assertFalse(buffer.isDirty());
    }

    @Test
    void deltaRoundTripsAFullRedraw() {
        final TextBuffer source = tierThree();
        source.setPaletteColor(5, 0x123456);
        source.setForeground(5, true);
        source.set(1, 1, "Hello, MineOS!", false);

        final byte[] payload = TextBufferDelta.encode(source);
        assertTrue(payload.length > 0);

        final TextBuffer target = tierThree();
        assertTrue(TextBufferDelta.apply(payload, target));

        assertEquals(source.getWidth(), target.getWidth());
        assertEquals(source.getDepth(), target.getDepth());
        assertArrayEquals(source.getFormat().getPalette(), target.getFormat().getPalette());
        assertEquals("Hello, MineOS!", row(target, 1, 15).substring(1));
        assertEquals(0x123456, target.getForegroundAt(1, 1));
    }

    @Test
    void deltaSendsOnlyWhatChanged() {
        final TextBuffer source = tierThree();
        final TextBuffer target = tierThree();
        assertTrue(TextBufferDelta.apply(TextBufferDelta.encode(source), target));
        source.clearDirty();
        target.clearDirty();

        source.set(4, 7, "changed", false);
        final byte[] payload = TextBufferDelta.encode(source);

        // One short span, not eight thousand cells. The exact size does not matter; the order of
        // magnitude does, because this is what goes on the wire every tick.
        assertTrue(payload.length < 64, "incremental payload was " + payload.length + " bytes");
        assertTrue(TextBufferDelta.apply(payload, target));
        assertEquals("changed", row(target, 7, 11).substring(4));
    }

    @Test
    void deltaOfAnUnchangedBufferIsEmpty() {
        final TextBuffer buffer = tierThree();
        buffer.clearDirty();
        assertEquals(0, TextBufferDelta.encode(buffer).length);
    }

    @Test
    void deltaRejectsGarbageRatherThanCorruptingTheScreen() {
        final TextBuffer buffer = tierThree();
        assertFalse(TextBufferDelta.apply(new byte[]{99, 0, 0, 0}, buffer),
            "a payload with an unknown version must be refused");
    }

    @Test
    void changingResolutionClearsAndForcesAFullRedraw() {
        final TextBuffer buffer = tierThree();
        buffer.set(0, 0, "text", false);
        buffer.clearDirty();

        assertTrue(buffer.setResolution(80, 25));
        assertTrue(buffer.isFullRedraw());
        assertEquals(' ', buffer.getRawChar(0, 0));
        assertFalse(buffer.setResolution(80, 25), "setting the same resolution is not a change");
    }

    @Test
    void changingDepthReplacesTheColourFormat() {
        final TextBuffer buffer = tierThree();
        assertEquals(ColorDepth.EIGHT_BIT, buffer.getDepth());
        assertTrue(buffer.setDepth(ColorDepth.FOUR_BIT));
        assertEquals(ColorDepth.FOUR_BIT, buffer.getDepth());
        // The Tier 2 palette is the dye colours, not the Tier 3 greys.
        assertNotEquals(new ColorFormat(ColorDepth.EIGHT_BIT).getPaletteColor(0),
            buffer.getPaletteColor(0));
    }

    @Test
    void knowsWhetherAnythingHasBeenDrawnOnIt() {
        // What decides whether the terminal window may put a hint on the screen. Getting it wrong
        // in one direction covers a program's output; in the other it leaves a player staring at a
        // black rectangle with no idea that their computer is switched off.
        final TextBuffer buffer = tierThree();
        assertTrue(buffer.isBlank());

        buffer.set(0, 0, " ", false);
        assertTrue(buffer.isBlank(), "spaces are not output");

        buffer.set(80, 25, "x", false);
        assertFalse(buffer.isBlank());

        buffer.clearAll();
        assertTrue(buffer.isBlank());

        // A wide glyph writes a continuation into the cell after it, which is not text of its own.
        buffer.set(10, 10, "\u4f60", false);
        assertFalse(buffer.isBlank());
    }
}
