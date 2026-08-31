/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

/**
 * Maps between the 24 bit colours programs talk in and the byte per channel a screen cell stores.
 * <p>
 * A Tier 3 screen holds 8000 cells and both a foreground and a background colour for each. Storing
 * two full RGB values per cell would be 64KB of state to keep, save and stream to clients per
 * screen; packing each into a byte brings that to 16KB and, more importantly, lets a cell's colour
 * pair travel as a single {@code short}.
 * <p>
 * The layout follows OpenComputers 1 exactly, because operating systems index into it directly.
 * MineOS in particular precomputes colour indices rather than going through {@code gpu} every time,
 * so getting the 6x8x5 cube's stride wrong would not fail loudly, it would just render wrong.
 */
public final class ColorFormat {
    /**
     * The Tier 2 palette, which doubles as the low sixteen entries at Tier 3 until a program
     * changes it. These are the Minecraft dye colours, in the order OpenComputers 1 uses.
     */
    public static final int[] DEFAULT_FOUR_BIT_PALETTE = {
        0xFFFFFF, 0xFFCC33, 0xCC66CC, 0x6699FF,
        0xFFFF33, 0x33CC33, 0xFF6699, 0x333333,
        0xCCCCCC, 0x336699, 0x9933CC, 0x333399,
        0x663300, 0x336600, 0xFF3333, 0x000000,
    };

    /**
     * Colour of a lit pixel on a Tier 1 screen.
     */
    public static final int MONOCHROME_COLOR = 0xFFFFFF;

    // Dimensions of the fixed colour cube occupying indices 16 through 255 at Tier 3. Green gets
    // the most levels and blue the fewest, following how much the eye resolves of each.
    private static final int CUBE_REDS = 6;
    private static final int CUBE_GREENS = 8;
    private static final int CUBE_BLUES = 5;

    private final ColorDepth depth;
    private final int[] palette;

    public ColorFormat(final ColorDepth depth) {
        this.depth = depth;
        this.palette = switch (depth) {
            case ONE_BIT -> new int[0];
            case FOUR_BIT -> DEFAULT_FOUR_BIT_PALETTE.clone();
            case EIGHT_BIT -> defaultEightBitPalette();
        };
    }

    /**
     * The Tier 3 default palette: sixteen evenly spaced greys.
     * <p>
     * Deliberately not the Tier 2 colours. Those already exist in the fixed cube, so spending the
     * sixteen editable entries on greys is what gives a Tier 3 screen its extra shading, and it is
     * what OpenOS assumes when it renders anti-aliased text.
     */
    public static int[] defaultEightBitPalette() {
        final int[] result = new int[16];
        for (int i = 0; i < result.length; i++) {
            final int shade = 0xFF * (i + 1) / (result.length + 1);
            result[i] = (shade << 16) | (shade << 8) | shade;
        }
        return result;
    }

    public ColorDepth getDepth() {
        return depth;
    }

    /**
     * How many editable palette entries this depth has; zero at Tier 1.
     */
    public int getPaletteSize() {
        return palette.length;
    }

    public int getPaletteColor(final int index) {
        checkPaletteIndex(index);
        return palette[index];
    }

    public void setPaletteColor(final int index, final int rgb) {
        checkPaletteIndex(index);
        palette[index] = rgb & 0xFFFFFF;
    }

    /**
     * Replaces the whole palette, used when restoring a screen from a save.
     */
    public void setPalette(final int[] values) {
        System.arraycopy(values, 0, palette, 0, Math.min(values.length, palette.length));
    }

    public int[] getPalette() {
        return palette.clone();
    }

    /**
     * Whether a stored index refers to an editable palette entry rather than a fixed colour.
     * Reported by {@code gpu.get} and {@code gpu.getForeground}.
     */
    public boolean isFromPalette(final int index) {
        return switch (depth) {
            case ONE_BIT -> false;
            case FOUR_BIT -> index >= 0 && index < 16;
            case EIGHT_BIT -> index >= 0 && index < 16;
        };
    }

    /**
     * Expands a stored index back into a 24 bit colour.
     */
    public int inflate(final int index) {
        return switch (depth) {
            case ONE_BIT -> index == 0 ? 0x000000 : MONOCHROME_COLOR;
            case FOUR_BIT -> palette[index & 0x0F];
            case EIGHT_BIT -> {
                if (index < 16) {
                    yield palette[Math.max(0, index)];
                }
                final int offset = (index & 0xFF) - 16;
                final int blue = offset % CUBE_BLUES;
                final int green = (offset / CUBE_BLUES) % CUBE_GREENS;
                final int red = (offset / CUBE_BLUES / CUBE_GREENS) % CUBE_REDS;
                yield (scale(red, CUBE_REDS) << 16)
                    | (scale(green, CUBE_GREENS) << 8)
                    | scale(blue, CUBE_BLUES);
            }
        };
    }

    /**
     * Finds the stored index that best represents a 24 bit colour.
     */
    public int deflate(final int rgb) {
        return switch (depth) {
            case ONE_BIT -> distance(rgb, 0x000000) < distance(rgb, MONOCHROME_COLOR) ? 0 : 1;
            case FOUR_BIT -> nearestPaletteIndex(rgb);
            case EIGHT_BIT -> {
                // Snap into the cube first, then check whether an editable entry happens to be a
                // better match. Both have to be considered: a program that sets its palette to
                // pastels and then asks for a pastel by RGB should get its own entry back.
                final int red = Math.round((extract(rgb, 16) * (CUBE_REDS - 1)) / 255.0f);
                final int green = Math.round((extract(rgb, 8) * (CUBE_GREENS - 1)) / 255.0f);
                final int blue = Math.round((extract(rgb, 0) * (CUBE_BLUES - 1)) / 255.0f);
                final int cubeIndex = 16 + (red * CUBE_GREENS + green) * CUBE_BLUES + blue;

                final int paletteIndex = nearestPaletteIndex(rgb);
                yield distance(rgb, inflate(cubeIndex)) <= distance(rgb, inflate(paletteIndex))
                    ? cubeIndex
                    : paletteIndex;
            }
        };
    }

    /**
     * The stored index for an explicitly requested palette entry, as used by
     * {@code gpu.setForeground(index, true)}.
     */
    public int deflatePaletteIndex(final int index) {
        checkPaletteIndex(index);
        return index;
    }

    private int nearestPaletteIndex(final int rgb) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            final double distance = distance(rgb, palette[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void checkPaletteIndex(final int index) {
        if (palette.length == 0) {
            throw new IllegalArgumentException("palette not available");
        }
        if (index < 0 || index >= palette.length) {
            throw new IllegalArgumentException("invalid palette index");
        }
    }

    private static int scale(final int step, final int steps) {
        return ((step * 0xFF) / (steps - 1)) & 0xFF;
    }

    private static int extract(final int rgb, final int shift) {
        return (rgb >>> shift) & 0xFF;
    }

    /**
     * Perceptual distance between two colours, weighted by the luminance contribution of each
     * channel. Plain Euclidean distance in RGB would happily swap a green for a blue of the same
     * arithmetic distance, which reads as a much bigger change than the numbers suggest.
     */
    private static double distance(final int a, final int b) {
        final double dr = extract(a, 16) - extract(b, 16);
        final double dg = extract(a, 8) - extract(b, 8);
        final double db = extract(a, 0) - extract(b, 0);
        return 0.2126 * dr * dr + 0.7152 * dg * dg + 0.0722 * db * db;
    }
}
