/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.MachineErrorScreen;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screen a player sees when a machine stops. Worth covering because it is the one piece of the
 * runtime that only runs when something has already gone wrong, and it must not itself go wrong.
 */
public final class MachineErrorScreenTest {
    private static ScreenComponent screen() {
        return new ScreenComponent(UUID.randomUUID().toString());
    }

    private static String text(final ScreenComponent screen) {
        final TextBuffer buffer = screen.getBuffer();
        final StringBuilder builder = new StringBuilder();
        for (int y = 0; y < buffer.getViewportHeight(); y++) {
            for (int x = 0; x < buffer.getViewportWidth(); x++) {
                final char value = buffer.getRawChar(x, y);
                builder.append(value == TextBuffer.WIDE_CHAR_CONTINUATION ? ' ' : value);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    @Test
    void theReasonEndsUpOnTheScreen() {
        final ScreenComponent screen = screen();
        MachineErrorScreen.render(screen, "Machine stopped", "no bootable medium found",
            "Right click the computer to start it again.");

        final String shown = text(screen);
        assertTrue(shown.contains("Machine stopped"), shown);
        assertTrue(shown.contains("no bootable medium found"), shown);
        assertTrue(shown.contains("Right click the computer"), shown);
        assertTrue(screen.getBuffer().isDirty(), "the message would never reach a client");
    }

    @Test
    void aLongReasonIsWrappedRatherThanTruncated() {
        final ScreenComponent screen = screen();
        final String reason = ("init:12: attempt to index a nil value, which happened while the "
            + "operating system was starting up and had not yet drawn anything to look at").repeat(3);
        MachineErrorScreen.render(screen, "Machine stopped", reason, "");

        final String shown = text(screen);
        assertTrue(shown.contains("init:12: attempt to index a nil value"), shown);
        // Every line has to fit; a line longer than the screen would simply have been cut off.
        for (final String line : shown.split("\n")) {
            assertTrue(line.length() <= screen.getBuffer().getViewportWidth(), line);
        }
        assertTrue(shown.contains("look at"), "the tail of the message was dropped:\n" + shown);
    }

    @Test
    void aMachineThatDiedDrawingOnItsCanvasStillShowsTheMessage() {
        final ScreenComponent screen = screen();
        final CanvasBuffer canvas = screen.getOrCreateCanvas();
        canvas.clear(0xFF203040);
        screen.setMode(ScreenMode.CANVAS);

        MachineErrorScreen.render(screen, "Machine stopped", "not enough energy", "");

        assertEquals(ScreenMode.TEXT, screen.getMode(),
            "the message was written to a buffer the screen is not showing");
        assertTrue(text(screen).contains("not enough energy"));
    }
}
