/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

/**
 * Backing logic for the {@code unicode} library.
 * <p>
 * Lua strings are byte strings, so {@code string.len} counts bytes and {@code string.sub} slices
 * them. Every OpenComputers program that draws a box or centres a label therefore reaches for
 * {@code unicode} instead, and MineOS in particular is built on it. The functions here work in
 * code points, and the {@code w*} ones work in display columns, which is what a terminal grid
 * actually cares about.
 */
public final class UnicodeSupport {
    private UnicodeSupport() {
    }

    /**
     * The number of code points in a string.
     */
    public static int length(final String value) {
        return value.codePointCount(0, value.length());
    }

    /**
     * Code point aware {@code string.sub}. Indices are one based and may be negative, counting
     * back from the end, exactly as in Lua.
     */
    public static String sub(final String value, final int from, final int to) {
        final int length = length(value);
        int start = from;
        int end = to;

        if (start < 0) {
            start = Math.max(1, length + start + 1);
        } else if (start == 0) {
            start = 1;
        }

        if (end < 0) {
            end = length + end + 1;
        } else if (end > length) {
            end = length;
        }

        if (start > end || start > length || end < 1) {
            return "";
        }

        final int startIndex = value.offsetByCodePoints(0, start - 1);
        final int endIndex = value.offsetByCodePoints(startIndex, end - start + 1);
        return value.substring(startIndex, endIndex);
    }

    /**
     * Reverses a string by code point, so surrogate pairs survive.
     */
    public static String reverse(final String value) {
        final int[] codePoints = value.codePoints().toArray();
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = codePoints.length - 1; i >= 0; i--) {
            builder.appendCodePoint(codePoints[i]);
        }
        return builder.toString();
    }

    /**
     * The number of terminal columns a single code point occupies: two for the East Asian wide
     * and fullwidth ranges, one for everything else.
     */
    public static int charWidth(final int codePoint) {
        return isWide(codePoint) ? 2 : 1;
    }

    /**
     * Whether the code point occupies two terminal columns.
     * <p>
     * The ranges follow Unicode's East Asian Width property for the {@code W} and {@code F}
     * classes. They are spelled out rather than derived because {@link Character} exposes no
     * width property, and a screen buffer that disagrees with the operating system about how wide
     * a glyph is will smear text across the display.
     */
    public static boolean isWide(final int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)     // Hangul Jamo initial consonants
            || (codePoint >= 0x2E80 && codePoint <= 0x303E)     // CJK radicals, Kangxi, punctuation
            || (codePoint >= 0x3041 && codePoint <= 0x33FF)     // Hiragana through CJK compatibility
            || (codePoint >= 0x3400 && codePoint <= 0x4DBF)     // CJK unified ideographs extension A
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)     // CJK unified ideographs
            || (codePoint >= 0xA000 && codePoint <= 0xA4CF)     // Yi syllables and radicals
            || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)     // Hangul syllables
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)     // CJK compatibility ideographs
            || (codePoint >= 0xFE10 && codePoint <= 0xFE19)     // vertical forms
            || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)     // CJK compatibility forms
            || (codePoint >= 0xFF00 && codePoint <= 0xFF60)     // fullwidth forms
            || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)     // fullwidth signs
            || (codePoint >= 0x1F300 && codePoint <= 0x1F64F)   // emoji
            || (codePoint >= 0x1F900 && codePoint <= 0x1F9FF)   // supplemental symbols and pictographs
            || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);  // CJK extensions B and beyond
    }

    /**
     * Whether the first code point of the string is wide. Matches {@code unicode.isWide}.
     */
    public static boolean isWide(final String value) {
        return !value.isEmpty() && isWide(value.codePointAt(0));
    }

    /**
     * The total display width of a string, in terminal columns.
     */
    public static int displayWidth(final String value) {
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            final int codePoint = value.codePointAt(i);
            width += charWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    /**
     * Truncates a string so its display width stays below {@code count}, matching
     * {@code unicode.wtrunc}. A wide glyph that would straddle the limit is dropped whole.
     */
    public static String truncateToWidth(final String value, final int count) {
        if (count <= 0) {
            return "";
        }

        int width = 0;
        for (int i = 0; i < value.length(); ) {
            final int codePoint = value.codePointAt(i);
            final int next = width + charWidth(codePoint);
            if (next >= count) {
                return value.substring(0, i);
            }
            width = next;
            i += Character.charCount(codePoint);
        }
        return value;
    }
}
