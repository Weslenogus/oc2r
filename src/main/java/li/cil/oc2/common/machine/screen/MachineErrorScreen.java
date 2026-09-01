/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

import li.cil.oc2.common.machine.components.ScreenComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Paints "this is why your computer stopped" onto a screen.
 * <p>
 * A machine that fails to boot has no program left to tell anyone what happened, so without this
 * the screen simply stays as it was - black, on a computer that has just been placed - and the
 * failure is only visible in the server log. The most common one, a computer with nothing bootable
 * on it, is also the one a player is most likely to hit and least likely to guess.
 * <p>
 * The message replaces whatever was on the screen. That is deliberate: the alternative, printing
 * under the program's output, would leave a half drawn desktop above an error that appears to
 * belong to it.
 */
public final class MachineErrorScreen {
    /**
     * Colours are given directly rather than through the palette, because the palette belongs to
     * whatever was running and may have been set to anything.
     */
    private static final int TITLE_COLOR = 0xFF5555;
    private static final int TEXT_COLOR = 0xD0D0D0;
    private static final int HINT_COLOR = 0x808080;

    private MachineErrorScreen() {
    }

    /**
     * Writes a title, a message and a hint onto a screen, clearing it first.
     *
     * @param screen  the screen to draw on.
     * @param title   the headline, e.g. "Machine stopped".
     * @param message what went wrong, wrapped to the screen's width.
     * @param hint    what the player can do about it; may be empty.
     */
    public static void render(final ScreenComponent screen, final String title,
                              final String message, final String hint) {
        synchronized (screen.getLock()) {
            // A machine that died while drawing on its canvas would otherwise leave the screen
            // showing pixels, with the explanation on a text buffer nobody is looking at.
            screen.setMode(ScreenMode.TEXT);

            final TextBuffer buffer = screen.getBuffer();
            buffer.setBackground(0x000000, false);
            buffer.setForeground(TEXT_COLOR, false);
            buffer.clearAll();

            final int width = buffer.getViewportWidth();
            if (width <= 0 || buffer.getViewportHeight() <= 0) {
                return;
            }

            int row = 1;
            buffer.setForeground(TITLE_COLOR, false);
            row = write(buffer, row, wrap(title, width - 4));

            row++;
            buffer.setForeground(TEXT_COLOR, false);
            row = write(buffer, row, wrap(message, width - 4));

            if (!hint.isEmpty()) {
                row++;
                buffer.setForeground(HINT_COLOR, false);
                write(buffer, row, wrap(hint, width - 4));
            }

            buffer.setForeground(TEXT_COLOR, false);
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static int write(final TextBuffer buffer, final int firstRow, final List<String> lines) {
        int row = firstRow;
        for (final String line : lines) {
            if (row >= buffer.getViewportHeight()) {
                break;
            }
            buffer.set(2, row, line, false);
            row++;
        }
        return row;
    }

    /**
     * Breaks text into lines that fit, on spaces where it can and mid word where it cannot.
     */
    private static List<String> wrap(final String text, final int width) {
        final List<String> lines = new ArrayList<>();
        if (width <= 0) {
            return lines;
        }

        for (final String paragraph : text.split("\n")) {
            String rest = paragraph.strip();
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }

            while (rest.length() > width) {
                int cut = rest.lastIndexOf(' ', width);
                if (cut <= 0) {
                    cut = width;
                }
                lines.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).strip();
            }
            lines.add(rest);
        }

        return lines;
    }
}
