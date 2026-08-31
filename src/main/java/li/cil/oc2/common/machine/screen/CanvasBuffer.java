/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

/**
 * A 32 bit ARGB pixel buffer, and the drawing a canvas graphics card does into it.
 * <p>
 * The text buffer next door is a grid of characters with a sixteen entry palette, which is what
 * every OpenComputers 1 program expects and what MineOS lays itself out against. This is the other
 * mode: no cells, no palette, one integer per pixel, for programs that want to draw rather than
 * print. A screen holds both and reports which one it is showing.
 * <h2>Dirty tracking</h2>
 * Changes are tracked as one span per row, the same shape the text buffer uses and for the same
 * reason: what a frame usually touches is a handful of rows, and sending a whole 320 by 200 frame
 * is a quarter of a megabyte. A row's span is the smallest range covering everything written to it,
 * so drawing at both edges of a row does cost the row, which is the price of not keeping a list.
 * <h2>Blending</h2>
 * Every primitive takes a colour whose alpha is honoured: fully opaque replaces, anything less is
 * composited over what is there. That is what makes overlapping shapes and glyph blitting look
 * right, and it is why the buffer stores alpha at all rather than packing to RGB.
 * <h2>Threading</h2>
 * Not thread safe. A screen owns one and guards it, exactly as it does its text buffer, because
 * the machine thread draws into it while the server thread reads it to build packets.
 */
public final class CanvasBuffer {
    /**
     * The largest canvas that may be asked for.
     * <p>
     * A frame is width times height times four bytes, and a full one has to fit through a packet:
     * at this size that is 1MB before compression, which is already more than a screen should be
     * sending every tick and is meant as a ceiling rather than a suggestion.
     */
    public static final int MAX_WIDTH = 640;
    public static final int MAX_HEIGHT = 400;

    /**
     * What a canvas is when nothing has asked for anything else. Chosen to be a quarter of the
     * maximum, so the common case is a quarter of the bandwidth, and to be a shape programs
     * recognise.
     */
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 200;

    private static final int OPAQUE_BLACK = 0xFF000000;

    private int width;
    private int height;
    private int[] pixels;

    /**
     * Inclusive bounds of what changed in each row. A row is clean when its start is past its end.
     */
    private final int[] dirtyMin = new int[MAX_HEIGHT];
    private final int[] dirtyMax = new int[MAX_HEIGHT];

    private boolean fullRedraw = true;

    ///////////////////////////////////////////////////////////////////

    public CanvasBuffer() {
        this(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public CanvasBuffer(final int width, final int height) {
        this.width = clampWidth(width);
        this.height = clampHeight(height);
        this.pixels = new int[this.width * this.height];
        clear(OPAQUE_BLACK);
    }

    ///////////////////////////////////////////////////////////////////

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * The pixels themselves, row major, for the renderer and the delta encoder. Not a copy: callers
     * read it under the screen's lock and do not write to it.
     */
    public int[] getPixels() {
        return pixels;
    }

    /**
     * Resizes the canvas, keeping what fits in the new bounds.
     * <p>
     * Keeping the contents rather than clearing matters because a program that shrinks its canvas
     * to fit a window has not asked for its picture to be thrown away, and one that grows it
     * expects to find what it had in the corner.
     *
     * @return {@code true} if the size actually changed.
     */
    public boolean setResolution(final int newWidth, final int newHeight) {
        final int w = clampWidth(newWidth);
        final int h = clampHeight(newHeight);
        if (w == width && h == height) {
            return false;
        }

        final int[] resized = new int[w * h];
        final int rows = Math.min(h, height);
        final int columns = Math.min(w, width);
        for (int y = 0; y < rows; y++) {
            System.arraycopy(pixels, y * width, resized, y * w, columns);
        }

        pixels = resized;
        width = w;
        height = h;
        markAll();
        return true;
    }

    public static int clampWidth(final int value) {
        return Math.max(1, Math.min(MAX_WIDTH, value));
    }

    public static int clampHeight(final int value) {
        return Math.max(1, Math.min(MAX_HEIGHT, value));
    }

    ///////////////////////////////////////////////////////////////////
    // Dirty tracking

    public boolean isDirty() {
        if (fullRedraw) {
            return true;
        }
        for (int y = 0; y < height; y++) {
            if (dirtyMin[y] <= dirtyMax[y]) {
                return true;
            }
        }
        return false;
    }

    public boolean isFullRedraw() {
        return fullRedraw;
    }

    public int getDirtyMin(final int y) {
        return dirtyMin[y];
    }

    public int getDirtyMax(final int y) {
        return dirtyMax[y];
    }

    /**
     * Marks the whole canvas as needing to be sent, which is what a client that has just come into
     * range needs and what a resize leaves behind.
     */
    public void markAll() {
        fullRedraw = true;
        for (int y = 0; y < height; y++) {
            dirtyMin[y] = 0;
            dirtyMax[y] = width - 1;
        }
    }

    /**
     * Forgets what changed. Called once the payload has actually been handed to the network, not
     * when it was built, so a failed send does not silently lose a frame.
     */
    public void clearDirty() {
        fullRedraw = false;
        for (int y = 0; y < height; y++) {
            dirtyMin[y] = Integer.MAX_VALUE;
            dirtyMax[y] = Integer.MIN_VALUE;
        }
    }

    private void markRow(final int y, final int fromX, final int toX) {
        if (fromX < dirtyMin[y]) {
            dirtyMin[y] = fromX;
        }
        if (toX > dirtyMax[y]) {
            dirtyMax[y] = toX;
        }
    }

    ///////////////////////////////////////////////////////////////////
    // Primitives

    /**
     * Fills the whole canvas. Always a replace, whatever the colour's alpha: clearing to a
     * translucent colour and getting a slightly tinted version of the old frame is not what anyone
     * means by clear.
     */
    public void clear(final int argb) {
        java.util.Arrays.fill(pixels, argb);
        markAll();
    }

    public int getPixel(final int x, final int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    public void setPixel(final int x, final int y, final int argb) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        final int index = y * width + x;
        pixels[index] = blend(pixels[index], argb);
        markRow(y, x, x);
    }

    /**
     * Fills a rectangle, clipped to the canvas.
     */
    public void fillRect(final int x, final int y, final int rectWidth, final int rectHeight, final int argb) {
        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(width, x + rectWidth) - 1;
        final int y1 = Math.min(height, y + rectHeight) - 1;
        if (x0 > x1 || y0 > y1) {
            return;
        }

        final boolean opaque = (argb >>> 24) == 0xFF;
        for (int row = y0; row <= y1; row++) {
            final int base = row * width;
            if (opaque) {
                java.util.Arrays.fill(pixels, base + x0, base + x1 + 1, argb);
            } else {
                for (int column = x0; column <= x1; column++) {
                    pixels[base + column] = blend(pixels[base + column], argb);
                }
            }
            markRow(row, x0, x1);
        }
    }

    /**
     * Draws the outline of a rectangle, one pixel wide.
     */
    public void drawRect(final int x, final int y, final int rectWidth, final int rectHeight, final int argb) {
        if (rectWidth <= 0 || rectHeight <= 0) {
            return;
        }
        if (rectWidth == 1 || rectHeight == 1) {
            // Degenerate, and drawing the four edges of it would blend the overlap twice.
            fillRect(x, y, rectWidth, rectHeight, argb);
            return;
        }
        fillRect(x, y, rectWidth, 1, argb);
        fillRect(x, y + rectHeight - 1, rectWidth, 1, argb);
        fillRect(x, y + 1, 1, rectHeight - 2, argb);
        fillRect(x + rectWidth - 1, y + 1, 1, rectHeight - 2, argb);
    }

    /**
     * Draws a line, endpoints included, by Bresenham.
     */
    public void drawLine(final int x0, final int y0, final int x1, final int y1, final int argb) {
        int x = x0;
        int y = y0;
        final int deltaX = Math.abs(x1 - x0);
        final int deltaY = -Math.abs(y1 - y0);
        final int stepX = x0 < x1 ? 1 : -1;
        final int stepY = y0 < y1 ? 1 : -1;
        int error = deltaX + deltaY;

        while (true) {
            setPixel(x, y, argb);
            if (x == x1 && y == y1) {
                return;
            }
            final int doubled = error * 2;
            if (doubled >= deltaY) {
                if (x == x1) {
                    return;
                }
                error += deltaY;
                x += stepX;
            }
            if (doubled <= deltaX) {
                if (y == y1) {
                    return;
                }
                error += deltaX;
                y += stepY;
            }
        }
    }

    /**
     * Draws a closed polygon's edges. Points are {@code x, y} pairs.
     */
    public void drawPolygon(final int[] points, final int argb) {
        final int count = points.length / 2;
        if (count < 2) {
            return;
        }
        for (int i = 0; i < count; i++) {
            final int next = (i + 1) % count;
            drawLine(points[i * 2], points[i * 2 + 1], points[next * 2], points[next * 2 + 1], argb);
        }
    }

    /**
     * Fills a polygon by scanline, with the even-odd rule.
     * <p>
     * Even-odd rather than nonzero winding because it needs no notion of edge direction, which
     * means a program can hand over points in whatever order it built them and get the shape it
     * drew. Self intersecting shapes come out with holes, which is the documented behaviour of
     * every other even-odd filler a program is likely to have met.
     */
    public void fillPolygon(final int[] points, final int argb) {
        final int count = points.length / 2;
        if (count < 3) {
            return;
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            final int y = points[i * 2 + 1];
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        minY = Math.max(0, minY);
        maxY = Math.min(height - 1, maxY);

        final int[] crossings = new int[count];
        for (int y = minY; y <= maxY; y++) {
            int found = 0;
            for (int i = 0; i < count; i++) {
                final int next = (i + 1) % count;
                final int ax = points[i * 2];
                final int ay = points[i * 2 + 1];
                final int bx = points[next * 2];
                final int by = points[next * 2 + 1];
                if (ay == by) {
                    // Horizontal edges contribute no crossing; the two edges meeting at each end
                    // already decide whether this row is inside.
                    continue;
                }
                // Half open in y, so a vertex shared by two edges is counted once rather than
                // twice, which is what stops a single pixel notch at every corner.
                if ((y >= ay && y < by) || (y >= by && y < ay)) {
                    crossings[found++] = ax + (y - ay) * (bx - ax) / (by - ay);
                }
            }
            if (found < 2) {
                continue;
            }

            java.util.Arrays.sort(crossings, 0, found);
            for (int i = 0; i + 1 < found; i += 2) {
                fillRect(crossings[i], y, crossings[i + 1] - crossings[i] + 1, 1, argb);
            }
        }
    }

    /**
     * Draws the outline of an ellipse inscribed in the given rectangle, by the midpoint algorithm.
     */
    public void drawEllipse(final int x, final int y, final int ellipseWidth, final int ellipseHeight,
                            final int argb) {
        ellipse(x, y, ellipseWidth, ellipseHeight, argb, false);
    }

    public void fillEllipse(final int x, final int y, final int ellipseWidth, final int ellipseHeight,
                            final int argb) {
        ellipse(x, y, ellipseWidth, ellipseHeight, argb, true);
    }

    private void ellipse(final int x, final int y, final int ellipseWidth, final int ellipseHeight,
                         final int argb, final boolean fill) {
        if (ellipseWidth <= 0 || ellipseHeight <= 0) {
            return;
        }

        // Worked in doubled coordinates so that even diameters, which have no centre pixel, come
        // out symmetric rather than a pixel heavy on one side.
        final int a = ellipseWidth - 1;
        final int b = ellipseHeight - 1;
        final double centerX = x + a / 2.0;
        final double centerY = y + b / 2.0;
        final double radiusX = a / 2.0;
        final double radiusY = b / 2.0;

        for (int row = 0; row < ellipseHeight; row++) {
            final int pixelY = y + row;
            if (pixelY < 0 || pixelY >= height) {
                continue;
            }
            final double normalized = radiusY == 0 ? 0 : (pixelY - centerY) / radiusY;
            final double squared = 1 - normalized * normalized;
            if (squared < 0) {
                continue;
            }
            final double halfSpan = radiusX * Math.sqrt(squared);
            final int left = (int) Math.round(centerX - halfSpan);
            final int right = (int) Math.round(centerX + halfSpan);

            if (fill) {
                fillRect(left, pixelY, right - left + 1, 1, argb);
            } else {
                final boolean edgeRow = row == 0 || row == ellipseHeight - 1;
                if (edgeRow) {
                    fillRect(left, pixelY, right - left + 1, 1, argb);
                } else {
                    setPixel(left, pixelY, argb);
                    setPixel(right, pixelY, argb);
                }
            }
        }
    }

    /**
     * Copies a rectangle of the canvas somewhere else on it.
     * <p>
     * A straight move rather than a composite, and correct when source and destination overlap,
     * which is what scrolling is.
     */
    public void copy(final int x, final int y, final int rectWidth, final int rectHeight,
                     final int destinationX, final int destinationY) {
        // Clip both ends before moving anything, so the copy never reads or writes outside.
        int sx = x;
        int sy = y;
        int dx = destinationX;
        int dy = destinationY;
        int w = rectWidth;
        int h = rectHeight;

        if (sx < 0) { w += sx; dx -= sx; sx = 0; }
        if (sy < 0) { h += sy; dy -= sy; sy = 0; }
        if (dx < 0) { w += dx; sx -= dx; dx = 0; }
        if (dy < 0) { h += dy; sy -= dy; dy = 0; }
        w = Math.min(w, Math.min(width - sx, width - dx));
        h = Math.min(h, Math.min(height - sy, height - dy));
        if (w <= 0 || h <= 0) {
            return;
        }

        // Bottom up when moving down, so a region does not overwrite rows it has yet to read.
        final boolean downwards = dy > sy;
        for (int i = 0; i < h; i++) {
            final int row = downwards ? h - 1 - i : i;
            System.arraycopy(pixels, (sy + row) * width + sx, pixels, (dy + row) * width + dx, w);
            markRow(dy + row, dx, dx + w - 1);
        }
    }

    /**
     * Draws an image into the canvas.
     *
     * @param source       the pixels, row major, ARGB.
     * @param sourceWidth  the image's width; its height follows from the array's length.
     * @param x            where the image's left edge lands.
     * @param y            where its top edge lands.
     * @param blend        whether to composite using each pixel's alpha, or replace outright.
     */
    public void blit(final int[] source, final int sourceWidth, final int x, final int y,
                     final boolean blend) {
        if (sourceWidth <= 0 || source.length == 0) {
            return;
        }
        final int sourceHeight = source.length / sourceWidth;

        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(width, x + sourceWidth) - 1;
        final int y1 = Math.min(height, y + sourceHeight) - 1;
        if (x0 > x1 || y0 > y1) {
            return;
        }

        for (int row = y0; row <= y1; row++) {
            final int sourceBase = (row - y) * sourceWidth - x;
            final int base = row * width;
            if (blend) {
                for (int column = x0; column <= x1; column++) {
                    pixels[base + column] = blend(pixels[base + column], source[sourceBase + column]);
                }
            } else {
                System.arraycopy(source, sourceBase + x0, pixels, base + x0, x1 - x0 + 1);
            }
            markRow(row, x0, x1);
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Composites one colour over another, source-over, in straight (non premultiplied) alpha.
     * <p>
     * The two common cases are short circuited because they are almost all of them: a fully opaque
     * source is the answer, and a fully transparent one changes nothing.
     */
    public static int blend(final int destination, final int source) {
        final int alpha = source >>> 24;
        if (alpha == 0xFF) {
            return source;
        }
        if (alpha == 0) {
            return destination;
        }

        final int inverse = 255 - alpha;
        final int destinationAlpha = destination >>> 24;
        final int outAlpha = alpha + destinationAlpha * inverse / 255;
        if (outAlpha == 0) {
            return 0;
        }

        final int r = component(source, 16) * alpha + component(destination, 16) * destinationAlpha * inverse / 255;
        final int g = component(source, 8) * alpha + component(destination, 8) * destinationAlpha * inverse / 255;
        final int b = component(source, 0) * alpha + component(destination, 0) * destinationAlpha * inverse / 255;

        return (outAlpha << 24)
            | (clampByte(r / outAlpha) << 16)
            | (clampByte(g / outAlpha) << 8)
            | clampByte(b / outAlpha);
    }

    private static int component(final int color, final int shift) {
        return (color >>> shift) & 0xFF;
    }

    private static int clampByte(final int value) {
        return value < 0 ? 0 : Math.min(value, 0xFF);
    }
}
