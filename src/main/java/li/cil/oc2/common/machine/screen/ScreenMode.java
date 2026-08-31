/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

/**
 * Which of a screen's two buffers it is showing.
 * <p>
 * A screen holds both a character grid and a pixel canvas, and displays one of them. The two are
 * not variations on each other: text is cells with a palette and a font, and is what every
 * OpenComputers 1 program including MineOS draws into, while the canvas is a plain ARGB image with
 * no notion of a cell. Trying to serve both from one buffer would mean either a text mode that
 * cannot be laid out in cells or a canvas whose pixels are stuck on a character grid, so the screen
 * keeps both and a card decides which one is on show.
 */
public enum ScreenMode {
    /**
     * The character grid. What a screen starts in, and what it goes back to when a text card draws.
     */
    TEXT,

    /**
     * The pixel canvas, driven by a canvas card.
     */
    CANVAS;

    public static ScreenMode fromOrdinal(final int ordinal) {
        final ScreenMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TEXT;
    }
}
