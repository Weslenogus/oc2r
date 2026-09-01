/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

import li.cil.oc2.common.machine.lua.UnicodeSupport;

/**
 * The character grid behind a screen, and the thing every {@code gpu} call ultimately edits.
 * <p>
 * Two arrays, one character and one packed colour pair per cell. At the Tier 3 maximum of 160x50
 * that is 8000 cells and 32KB of state, which matters because it is saved with the world, kept in
 * memory per screen, and streamed to every client watching.
 * <p>
 * Changes are tracked as one dirty span per row rather than cell by cell. Text output almost
 * always writes a run within a single row, so a span costs nothing to maintain and collapses a
 * full screen repaint into 50 entries instead of 8000. {@link #isFullRedraw()} covers the cases
 * where a span cannot describe what happened, such as a resolution or palette change.
 * <p>
 * Coordinates here are zero based. The {@code gpu} component converts from the one based
 * coordinates Lua uses.
 */
public final class TextBuffer {
    /**
     * Marks the right half of a double width character. The left half holds the real character;
     * this cell exists so column arithmetic stays honest and the renderer knows to skip it.
     */
    public static final char WIDE_CHAR_CONTINUATION = '\0';

    private final int maxWidth;
    private final int maxHeight;
    private final ColorDepth maxDepth;

    private final char[] chars;
    private final short[] colors;

    private final int[] dirtyMin;
    private final int[] dirtyMax;
    private boolean fullRedraw = true;

    private int width;
    private int height;
    private int viewportWidth;
    private int viewportHeight;

    private ColorFormat format;

    private int foreground = 0xFFFFFF;
    private int foregroundPaletteIndex = -1;
    private int background = 0x000000;
    private int backgroundPaletteIndex = -1;

    private byte packedForeground;
    private byte packedBackground;

    ///////////////////////////////////////////////////////////////////

    public TextBuffer(final int maxWidth, final int maxHeight, final ColorDepth maxDepth) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxDepth = maxDepth;

        this.chars = new char[maxWidth * maxHeight];
        this.colors = new short[maxWidth * maxHeight];
        this.dirtyMin = new int[maxHeight];
        this.dirtyMax = new int[maxHeight];

        this.width = maxWidth;
        this.height = maxHeight;
        this.viewportWidth = maxWidth;
        this.viewportHeight = maxHeight;
        this.format = new ColorFormat(maxDepth);

        repackColors();
        clearAll();
        markAllDirty();
    }

    ///////////////////////////////////////////////////////////////////

    public int getMaxWidth() {
        return maxWidth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public ColorDepth getMaxDepth() {
        return maxDepth;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public ColorDepth getDepth() {
        return format.getDepth();
    }

    public ColorFormat getFormat() {
        return format;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Changes the resolution, clearing the buffer if it actually changed.
     *
     * @return {@code true} if the resolution changed.
     */
    public boolean setResolution(final int newWidth, final int newHeight) {
        if (newWidth < 1 || newHeight < 1 || newWidth > maxWidth || newHeight > maxHeight) {
            throw new IllegalArgumentException("unsupported resolution");
        }
        if (newWidth == width && newHeight == height) {
            return false;
        }

        width = newWidth;
        height = newHeight;
        viewportWidth = newWidth;
        viewportHeight = newHeight;
        clearAll();
        markAllDirty();
        return true;
    }

    /**
     * Changes the visible area without touching the resolution, which is how programs letterbox a
     * screen without giving up the buffer behind it.
     *
     * @return {@code true} if the viewport changed.
     */
    public boolean setViewport(final int newWidth, final int newHeight) {
        if (newWidth < 1 || newHeight < 1 || newWidth > width || newHeight > height) {
            throw new IllegalArgumentException("unsupported viewport size");
        }
        if (newWidth == viewportWidth && newHeight == viewportHeight) {
            return false;
        }

        viewportWidth = newWidth;
        viewportHeight = newHeight;
        fullRedraw = true;
        return true;
    }

    /**
     * Changes the colour depth. The stored indices mean something different afterwards, so the
     * buffer is cleared rather than reinterpreted.
     *
     * @return {@code true} if the depth changed.
     */
    public boolean setDepth(final ColorDepth depth) {
        if (!depth.isAtMost(maxDepth)) {
            throw new IllegalArgumentException("unsupported depth");
        }
        if (depth == format.getDepth()) {
            return false;
        }

        format = new ColorFormat(depth);
        repackColors();
        clearAll();
        markAllDirty();
        return true;
    }

    public int getPaletteColor(final int index) {
        return format.getPaletteColor(index);
    }

    /**
     * Changes a palette entry.
     * <p>
     * Every cell already using this entry changes colour, and there is no cheap way to find them,
     * so this forces a full redraw.
     *
     * @return the previous colour.
     */
    public int setPaletteColor(final int index, final int rgb) {
        final int previous = format.getPaletteColor(index);
        format.setPaletteColor(index, rgb);
        repackColors();
        fullRedraw = true;
        return previous;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The current foreground as {@code gpu.getForeground} reports it: the palette index when the
     * colour came from the palette, the RGB value otherwise.
     */
    public int getForegroundValue() {
        return foregroundPaletteIndex >= 0 ? foregroundPaletteIndex : foreground;
    }

    public boolean isForegroundFromPalette() {
        return foregroundPaletteIndex >= 0;
    }

    public int getBackgroundValue() {
        return backgroundPaletteIndex >= 0 ? backgroundPaletteIndex : background;
    }

    public boolean isBackgroundFromPalette() {
        return backgroundPaletteIndex >= 0;
    }

    /**
     * The current foreground as a 24 bit colour, resolving a palette index if necessary.
     */
    public int getForegroundColor() {
        return foregroundPaletteIndex >= 0 ? format.getPaletteColor(foregroundPaletteIndex) : foreground;
    }

    public int getBackgroundColor() {
        return backgroundPaletteIndex >= 0 ? format.getPaletteColor(backgroundPaletteIndex) : background;
    }

    public void setForeground(final int value, final boolean isPaletteIndex) {
        if (isPaletteIndex) {
            foregroundPaletteIndex = format.deflatePaletteIndex(value);
            foreground = format.getPaletteColor(value);
            packedForeground = (byte) foregroundPaletteIndex;
        } else {
            foregroundPaletteIndex = -1;
            foreground = value & 0xFFFFFF;
            packedForeground = (byte) format.deflate(foreground);
        }
    }

    public void setBackground(final int value, final boolean isPaletteIndex) {
        if (isPaletteIndex) {
            backgroundPaletteIndex = format.deflatePaletteIndex(value);
            background = format.getPaletteColor(value);
            packedBackground = (byte) backgroundPaletteIndex;
        } else {
            backgroundPaletteIndex = -1;
            background = value & 0xFFFFFF;
            packedBackground = (byte) format.deflate(background);
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Writes text starting at the given cell, in the current colours.
     *
     * @param x        the leftmost column, zero based.
     * @param y        the topmost row, zero based.
     * @param value    the text to write.
     * @param vertical whether to advance downwards instead of rightwards.
     * @return {@code true} if anything was written.
     */
    public boolean set(final int x, final int y, final String value, final boolean vertical) {
        if (value.isEmpty()) {
            return false;
        }

        boolean changed = false;
        int column = x;
        int row = y;

        for (int i = 0; i < value.length(); ) {
            final int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);

            final boolean wide = UnicodeSupport.isWide(codePoint);
            final int advance = wide ? 2 : 1;

            // Cells off the left or top edge are skipped rather than clipped: half of a double
            // width character is not a character, and writing only its continuation marker would
            // leave the row describing a glyph that is not there.
            if (row >= 0 && row < height && column >= 0 && column < width) {
                changed |= write(column, row, (char) codePoint, wide);
            }

            if (vertical) {
                row++;
                if (row >= height) {
                    break;
                }
            } else {
                column += advance;
                if (column >= width) {
                    break;
                }
            }
        }

        return changed;
    }

    /**
     * Fills a rectangle with a single character in the current colours.
     *
     * @return {@code true} if anything changed.
     */
    public boolean fill(final int x, final int y, final int fillWidth, final int fillHeight, final char value) {
        final int left = Math.max(0, x);
        final int top = Math.max(0, y);
        final int right = Math.min(width, x + fillWidth);
        final int bottom = Math.min(height, y + fillHeight);
        if (left >= right || top >= bottom) {
            return false;
        }

        final boolean wide = UnicodeSupport.isWide(value);
        final short packed = packed();
        boolean changed = false;

        for (int row = top; row < bottom; row++) {
            // Both edges of the rectangle can cut a double width character in half. Repair them
            // first, otherwise the surviving half would keep claiming two columns.
            changed |= splitWideAt(left, row);
            changed |= splitWideAt(right, row);

            final int base = row * maxWidth;
            for (int column = left; column < right; ) {
                if (wide) {
                    if (column + 1 >= right) {
                        // No room for the second half; a space keeps the cell from showing a
                        // stray glyph rather than leaving it half drawn.
                        changed |= setCell(base + column, ' ', packed);
                        column++;
                    } else {
                        changed |= setCell(base + column, value, packed);
                        changed |= setCell(base + column + 1, WIDE_CHAR_CONTINUATION, packed);
                        column += 2;
                    }
                } else {
                    changed |= setCell(base + column, value, packed);
                    column++;
                }
            }

            markDirty(row, left, right - 1);
        }

        return changed;
    }

    /**
     * Moves a rectangle of cells by the given offset, the way a terminal scrolls.
     * <p>
     * Source and destination may overlap, so rows are walked in whichever direction keeps the copy
     * from reading cells it has already written.
     *
     * @return {@code true} if anything changed.
     */
    public boolean copy(final int x, final int y, final int copyWidth, final int copyHeight,
                        final int deltaX, final int deltaY) {
        if (copyWidth <= 0 || copyHeight <= 0 || (deltaX == 0 && deltaY == 0)) {
            return false;
        }

        // Clip the source to the buffer, then clip again by how far the destination would fall
        // outside it, so both ends of the copy stay in bounds.
        int sourceLeft = Math.max(0, x);
        int sourceTop = Math.max(0, y);
        int sourceRight = Math.min(width, x + copyWidth);
        int sourceBottom = Math.min(height, y + copyHeight);

        sourceLeft = Math.max(sourceLeft, -deltaX);
        sourceTop = Math.max(sourceTop, -deltaY);
        sourceRight = Math.min(sourceRight, width - deltaX);
        sourceBottom = Math.min(sourceBottom, height - deltaY);

        if (sourceLeft >= sourceRight || sourceTop >= sourceBottom) {
            return false;
        }

        final int rows = sourceBottom - sourceTop;
        final int columns = sourceRight - sourceLeft;

        final int firstRow = deltaY > 0 ? rows - 1 : 0;
        final int lastRow = deltaY > 0 ? -1 : rows;
        final int rowStep = deltaY > 0 ? -1 : 1;

        for (int i = firstRow; i != lastRow; i += rowStep) {
            final int fromRow = sourceTop + i;
            final int toRow = fromRow + deltaY;
            final int fromBase = fromRow * maxWidth + sourceLeft;
            final int toBase = toRow * maxWidth + sourceLeft + deltaX;

            // System.arraycopy already handles overlap within a row correctly.
            System.arraycopy(chars, fromBase, chars, toBase, columns);
            System.arraycopy(colors, fromBase, colors, toBase, columns);

            final int destinationLeft = sourceLeft + deltaX;
            splitWideAt(destinationLeft, toRow);
            splitWideAt(destinationLeft + columns, toRow);
            markDirty(toRow, destinationLeft, destinationLeft + columns - 1);
        }

        return true;
    }

    /**
     * Clears the whole buffer to spaces in the current background colour.
     */
    public void clearAll() {
        final short packed = packed();
        java.util.Arrays.fill(chars, ' ');
        java.util.Arrays.fill(colors, packed);
        markAllDirty();
    }

    /**
     * Whether anything has been drawn: true when every cell in the viewport is blank.
     * <p>
     * Used to decide whether it is safe to put a hint on the screen. A buffer with a program's
     * output on it must never be drawn over, so "nothing here" has to be a question that can be
     * asked cheaply, every frame.
     */
    public boolean isBlank() {
        for (int y = 0; y < getViewportHeight(); y++) {
            for (int x = 0; x < getViewportWidth(); x++) {
                final char value = chars[y * width + x];
                if (value != ' ' && value != WIDE_CHAR_CONTINUATION) {
                    return false;
                }
            }
        }
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    public char getChar(final int x, final int y) {
        if (!isInBounds(x, y)) {
            return ' ';
        }
        final char value = chars[y * maxWidth + x];
        // Reading the right half of a double width character reports the character itself, which
        // is what a program inspecting the cell under the cursor expects to see.
        if (value == WIDE_CHAR_CONTINUATION && x > 0) {
            return chars[y * maxWidth + x - 1];
        }
        return value;
    }

    /**
     * The raw cell contents, including the continuation marker. Used by the renderer, which has to
     * know not to draw the right half of a wide glyph twice.
     */
    public char getRawChar(final int x, final int y) {
        return isInBounds(x, y) ? chars[y * maxWidth + x] : ' ';
    }

    public short getPackedColor(final int x, final int y) {
        return isInBounds(x, y) ? colors[y * maxWidth + x] : 0;
    }

    public int getForegroundAt(final int x, final int y) {
        return format.inflate(unpackForeground(getPackedColor(x, y)));
    }

    public int getBackgroundAt(final int x, final int y) {
        return format.inflate(unpackBackground(getPackedColor(x, y)));
    }

    /**
     * The palette index of the foreground at a cell, or {@code -1} if it is not a palette colour.
     */
    public int getForegroundPaletteIndexAt(final int x, final int y) {
        final int index = unpackForeground(getPackedColor(x, y));
        return format.isFromPalette(index) ? index : -1;
    }

    public int getBackgroundPaletteIndexAt(final int x, final int y) {
        final int index = unpackBackground(getPackedColor(x, y));
        return format.isFromPalette(index) ? index : -1;
    }

    public static int unpackForeground(final short packed) {
        return (packed >> 8) & 0xFF;
    }

    public static int unpackBackground(final short packed) {
        return packed & 0xFF;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Writes a cell's character and packed colour pair directly.
     * <p>
     * This is the client's side of {@link TextBufferDelta}: it receives cells that were already
     * resolved against the server's colour format, so it must not re-derive them from the current
     * drawing colours the way {@link #set} does.
     */
    public void setCellRaw(final int x, final int y, final char value, final short packedColor) {
        if (!isInBounds(x, y)) {
            return;
        }
        final int index = y * maxWidth + x;
        chars[index] = value;
        colors[index] = packedColor;
        markDirty(y, x, x);
    }

    public boolean isDirty() {
        if (fullRedraw) {
            return true;
        }
        for (int row = 0; row < height; row++) {
            if (dirtyMin[row] <= dirtyMax[row]) {
                return true;
            }
        }
        return false;
    }

    public boolean isFullRedraw() {
        return fullRedraw;
    }

    /**
     * The first changed column in a row, or a value greater than {@link #getDirtyMax(int)} if the
     * row is clean.
     */
    public int getDirtyMin(final int row) {
        return dirtyMin[row];
    }

    public int getDirtyMax(final int row) {
        return dirtyMax[row];
    }

    public boolean isRowDirty(final int row) {
        return dirtyMin[row] <= dirtyMax[row];
    }

    public void clearDirty() {
        fullRedraw = false;
        for (int row = 0; row < maxHeight; row++) {
            dirtyMin[row] = Integer.MAX_VALUE;
            dirtyMax[row] = Integer.MIN_VALUE;
        }
    }

    public void markAllDirty() {
        fullRedraw = true;
        for (int row = 0; row < maxHeight; row++) {
            dirtyMin[row] = 0;
            dirtyMax[row] = width - 1;
        }
    }

    ///////////////////////////////////////////////////////////////////

    private boolean isInBounds(final int x, final int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private short packed() {
        return (short) (((packedForeground & 0xFF) << 8) | (packedBackground & 0xFF));
    }

    private void repackColors() {
        packedForeground = (byte) (foregroundPaletteIndex >= 0
            ? foregroundPaletteIndex
            : format.deflate(foreground));
        packedBackground = (byte) (backgroundPaletteIndex >= 0
            ? backgroundPaletteIndex
            : format.deflate(background));
    }

    private boolean write(final int x, final int y, final char value, final boolean wide) {
        final short packed = packed();
        final int base = y * maxWidth;

        // A wide character in the last column has nowhere to put its second half. A space is the
        // honest result: the alternative is a glyph that overhangs the edge of the screen.
        if (wide && x + 1 >= width) {
            final boolean repaired = splitWideAt(x, y);
            markDirty(y, x, x);
            return setCell(base + x, ' ', packed) | repaired;
        }

        boolean changed = splitWideAt(x, y);
        changed |= setCell(base + x, value, packed);
        markDirty(y, x, x);

        if (wide) {
            changed |= splitWideAt(x + 1, y);
            changed |= setCell(base + x + 1, WIDE_CHAR_CONTINUATION, packed);
            markDirty(y, x + 1, x + 1);
        }

        return changed;
    }

    /**
     * Repairs a double width character that is about to lose one of its halves to a write at the
     * given column, replacing the orphaned half with a space.
     * <p>
     * Without this a half written wide glyph keeps claiming two columns, and everything to its
     * right on that row renders one cell off.
     *
     * @return {@code true} if a repair was needed.
     */
    private boolean splitWideAt(final int x, final int y) {
        if (y < 0 || y >= height || x < 0 || x >= width) {
            return false;
        }

        final int base = y * maxWidth;
        boolean changed = false;

        // Overwriting the right half orphans the left half.
        if (chars[base + x] == WIDE_CHAR_CONTINUATION && x > 0) {
            chars[base + x - 1] = ' ';
            markDirty(y, x - 1, x - 1);
            changed = true;
        }

        // Overwriting the left half orphans the continuation marker to its right.
        if (UnicodeSupport.isWide(chars[base + x])
            && x + 1 < width
            && chars[base + x + 1] == WIDE_CHAR_CONTINUATION) {
            chars[base + x + 1] = ' ';
            markDirty(y, x + 1, x + 1);
            changed = true;
        }

        return changed;
    }

    private boolean setCell(final int index, final char value, final short packed) {
        if (chars[index] == value && colors[index] == packed) {
            return false;
        }
        chars[index] = value;
        colors[index] = packed;
        return true;
    }

    private void markDirty(final int row, final int from, final int to) {
        if (row < 0 || row >= maxHeight) {
            return;
        }
        final int left = Math.max(0, from);
        final int right = Math.min(width - 1, to);
        if (left > right) {
            return;
        }
        dirtyMin[row] = Math.min(dirtyMin[row], left);
        dirtyMax[row] = Math.max(dirtyMax[row], right);
    }
}
