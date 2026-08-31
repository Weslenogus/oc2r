/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.CanvasCardComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.lua.LuaJArchitecture;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.CanvasBufferDelta;
import li.cil.oc2.common.machine.screen.ScreenMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pixel canvas: drawing, clipping, blending, and what a frame costs on the wire.
 */
public class CanvasTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLACK = 0xFF000000;

    private static EepromComponent eepromWith(final String code) {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
        eeprom.setCode(code);
        return eeprom;
    }

    private static CanvasBuffer canvas(final int width, final int height) {
        final CanvasBuffer canvas = new CanvasBuffer(width, height);
        canvas.clearDirty();
        return canvas;
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void startsOpaqueBlackSoNothingRendersAsAHole() {
        final CanvasBuffer canvas = new CanvasBuffer(4, 4);
        assertEquals(BLACK, canvas.getPixel(0, 0));
        assertEquals(BLACK, canvas.getPixel(3, 3));
        assertTrue(canvas.isFullRedraw(), "a new canvas has never been sent to anyone");
    }

    @Test
    void drawingOutsideTheCanvasIsIgnoredRatherThanFatal() {
        final CanvasBuffer canvas = canvas(8, 8);
        // A program computes coordinates; some of them will be off the edge, and that must not be
        // an error the program has to guard every call against.
        canvas.setPixel(-1, 4, RED);
        canvas.setPixel(8, 4, RED);
        canvas.setPixel(4, -1, RED);
        canvas.setPixel(4, 8, RED);
        canvas.fillRect(-4, -4, 6, 6, RED);
        canvas.drawLine(-20, 4, 20, 4, GREEN);

        assertFalse(canvas.isFullRedraw());
        assertEquals(RED, canvas.getPixel(0, 0), "the visible part of the rectangle should be drawn");
        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(GREEN, canvas.getPixel(0, 4), "the line should be clipped, not dropped");
        assertEquals(GREEN, canvas.getPixel(7, 4));
        assertEquals(0, canvas.getPixel(-1, -1), "reading outside answers rather than throwing");
    }

    @Test
    void alphaIsComposited() {
        final CanvasBuffer canvas = canvas(2, 1);
        canvas.setPixel(0, 0, 0xFF000000);
        canvas.setPixel(0, 0, 0x80FFFFFF);

        final int blended = canvas.getPixel(0, 0);
        assertEquals(0xFF, blended >>> 24, "over an opaque pixel the result stays opaque");
        final int red = (blended >>> 16) & 0xFF;
        assertTrue(red > 0x70 && red < 0x90, "half of white over black should be about half grey, got " + red);

        // Fully transparent changes nothing, fully opaque replaces.
        canvas.setPixel(1, 0, RED);
        canvas.setPixel(1, 0, 0x00FFFFFF);
        assertEquals(RED, canvas.getPixel(1, 0));
        canvas.setPixel(1, 0, GREEN);
        assertEquals(GREEN, canvas.getPixel(1, 0));
    }

    @Test
    void linesReachBothEndpoints() {
        final CanvasBuffer canvas = canvas(16, 16);
        canvas.drawLine(2, 3, 12, 9, RED);
        assertEquals(RED, canvas.getPixel(2, 3));
        assertEquals(RED, canvas.getPixel(12, 9));

        // A line of no length is a point, not nothing.
        canvas.drawLine(5, 5, 5, 5, GREEN);
        assertEquals(GREEN, canvas.getPixel(5, 5));
    }

    @Test
    void rectangleOutlinesAreHollowAndOnePixelWide() {
        final CanvasBuffer canvas = canvas(8, 8);
        canvas.drawRect(1, 1, 5, 4, RED);

        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(RED, canvas.getPixel(5, 1));
        assertEquals(RED, canvas.getPixel(1, 4));
        assertEquals(RED, canvas.getPixel(5, 4));
        assertNotEquals(RED, canvas.getPixel(3, 2), "the middle should be untouched");
        assertNotEquals(RED, canvas.getPixel(0, 0), "nothing outside the rectangle");
    }

    @Test
    void aTranslucentOutlineDoesNotDoubleBlendItsCorners() {
        // The four edges of a rectangle meet at the corners. Drawn as four overlapping fills, each
        // corner would be composited twice and come out darker than the sides, which is visible.
        final CanvasBuffer canvas = canvas(8, 8);
        canvas.clear(BLACK);
        canvas.drawRect(0, 0, 8, 8, 0x80FFFFFF);
        assertEquals(canvas.getPixel(4, 0), canvas.getPixel(0, 0),
            "a corner should be the same colour as the edge it joins");
    }

    @Test
    void fillsAPolygonWithoutNotchingItsCorners() {
        final CanvasBuffer canvas = canvas(16, 16);
        // A triangle. The half open scanline rule is what keeps the shared vertices from being
        // counted twice and leaving gaps.
        canvas.fillPolygon(new int[]{2, 2, 13, 2, 8, 13}, RED);

        assertEquals(RED, canvas.getPixel(8, 3), "inside near the top edge");
        assertEquals(RED, canvas.getPixel(8, 11), "inside near the apex");
        assertNotEquals(RED, canvas.getPixel(2, 12), "outside, below the left edge");
        assertNotEquals(RED, canvas.getPixel(14, 8), "outside, right of the right edge");

        // Every row of the body should have been touched, no dropped scanlines.
        for (int y = 3; y <= 11; y++) {
            assertEquals(RED, canvas.getPixel(8, y), "row " + y + " was not filled");
        }
    }

    @Test
    void copyHandlesOverlapWhichIsWhatScrollingIs() {
        final CanvasBuffer canvas = canvas(8, 8);
        for (int y = 0; y < 8; y++) {
            canvas.fillRect(0, y, 8, 1, 0xFF000000 | y);
        }

        // Scroll up by one, the way a terminal would.
        canvas.copy(0, 1, 8, 7, 0, 0);
        for (int y = 0; y < 7; y++) {
            assertEquals(0xFF000000 | (y + 1), canvas.getPixel(0, y),
                "row " + y + " should hold what was below it");
        }
    }

    @Test
    void blitDrawsAnImageAndHonoursItsAlpha() {
        final CanvasBuffer canvas = canvas(8, 8);
        canvas.clear(BLACK);

        final int[] image = {RED, 0x00000000, RED, RED};
        canvas.blit(image, 2, 1, 1, true);
        assertEquals(RED, canvas.getPixel(1, 1));
        assertEquals(BLACK, canvas.getPixel(2, 1), "a transparent source pixel leaves what was there");

        // Without blending it is a straight copy, transparency included.
        canvas.blit(image, 2, 4, 4, false);
        assertEquals(0x00000000, canvas.getPixel(5, 4));
    }

    @Test
    void resizingKeepsWhatFits() {
        final CanvasBuffer canvas = canvas(8, 8);
        canvas.setPixel(1, 1, RED);
        canvas.setPixel(7, 7, GREEN);

        assertTrue(canvas.setResolution(4, 4));
        assertEquals(RED, canvas.getPixel(1, 1), "a program shrinking its canvas keeps its picture");
        assertEquals(4, canvas.getWidth());
        assertFalse(canvas.setResolution(4, 4), "resizing to the same size is not a change");

        canvas.setResolution(8, 8);
        assertEquals(RED, canvas.getPixel(1, 1), "growing keeps what was there");
    }

    @Test
    void refusesASizeItCannotDrive() {
        final CanvasBuffer canvas = new CanvasBuffer(100_000, -4);
        assertEquals(CanvasBuffer.MAX_WIDTH, canvas.getWidth());
        assertEquals(1, canvas.getHeight());
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void aProgramCanDriveTheCanvasAndSwitchBackToText() throws Exception {
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.setDirectCallsPerTickFactor(8);
        final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
        final CanvasCardComponent card = new CanvasCardComponent(UUID.randomUUID().toString());
        final GraphicsCardComponent gpu = new GraphicsCardComponent(UUID.randomUUID().toString());

        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local canvas = component.proxy(component.list("canvas")())
            local screen = component.list("screen")()
            canvas.bind(screen)

            local w, h = canvas.getResolution()
            note("resolution " .. w .. "x" .. h)

            -- Colours are written the way every OpenComputers program writes them, without an
            -- alpha byte, and have to come out opaque rather than invisible.
            canvas.clear(0x101820)
            canvas.setColor(0xFF0000)
            canvas.fill(11, 11, 20, 10)
            canvas.line(1, 1, 40, 30)
            canvas.fillPolygon({100, 100, 140, 100, 120, 140}, 0x00FF00)
            note("pixel " .. string.format("%08X", canvas.get(15, 15)))
            note("clear " .. string.format("%08X", canvas.get(300, 190)))
            note("polygon " .. string.format("%08X", canvas.get(120, 120)))

            -- Half alpha over the background should land between the two.
            canvas.set(200, 100, 0x80FFFFFF)
            local blended = canvas.get(200, 100)
            note("blended " .. tostring(blended ~= 0x101820 and blended ~= 0xFFFFFFFF))

            local gpuAddress = component.list("gpu")()
            note("switched to text " .. tostring(component.invoke(gpuAddress, "bind", screen)))
            component.invoke(gpuAddress, "set", 1, 1, "back to text")
            note("canvas kept " .. string.format("%08X", canvas.get(15, 15)))

            computer.shutdown()
            """));
        host.add(recorder);
        host.add(screen);
        host.add(card);
        host.add(gpu);

        final LuaMachine machine = new LuaMachine(host, UUID.randomUUID().toString(),
            LuaJArchitecture::new);
        machine.start();
        TestMachineHost.run(machine, 400);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of(
            "resolution 320x200",
            "pixel FFFF0000",
            "clear FF101820",
            "polygon FF00FF00",
            "blended true",
            "switched to text true",
            "canvas kept FFFF0000"), recorder.notes);

        // Drawing text is what takes the screen back out of canvas mode, and neither buffer loses
        // what was on it.
        assertEquals(ScreenMode.TEXT, screen.getMode());
        assertEquals('b', screen.getBuffer().getRawChar(0, 0));
        assertEquals(0xFFFF0000, screen.getOrCreateCanvas().getPixel(14, 14));
    }

    @Test
    void theScreenSendsWhicheverBufferIsOnShow() {
        // A screen flipped to its canvas is not also paying to keep a hidden text grid up to date
        // on every client watching it.
        final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
        screen.getBuffer().clearDirty();
        screen.setMode(ScreenMode.CANVAS);
        final CanvasBuffer canvas = screen.getOrCreateCanvas();
        canvas.clearDirty();

        canvas.fillRect(4, 4, 8, 8, 0xFF00FF00);
        assertTrue(canvas.isDirty());
        assertFalse(screen.getBuffer().isDirty(), "the hidden text grid should have nothing to send");

        // Switching marks the buffer now on show whole, because clients have been watching the
        // other one and know nothing about this one.
        screen.setMode(ScreenMode.TEXT);
        assertTrue(screen.getBuffer().isFullRedraw());
    }

    @Test
    void onlyTouchedRowsGoOnTheWire() {
        final CanvasBuffer canvas = canvas(320, 200);
        canvas.fillRect(10, 10, 20, 5, RED);

        final byte[] delta = CanvasBufferDelta.encode(canvas);
        // Five rows of twenty pixels, not sixty four thousand pixels.
        assertTrue(delta.length < 512, "a small change cost " + delta.length + " bytes");

        final CanvasBuffer client = new CanvasBuffer(320, 200);
        assertTrue(CanvasBufferDelta.apply(delta, client));
        assertEquals(RED, client.getPixel(15, 12));
        assertEquals(BLACK, client.getPixel(15, 20));
    }

    @Test
    void aFullFrameCompressesEnoughToAnimate() {
        final CanvasBuffer canvas = new CanvasBuffer(320, 200);
        canvas.clear(0xFF203040);
        canvas.fillRect(20, 20, 200, 100, RED);
        canvas.drawLine(0, 0, 319, 199, GREEN);

        final byte[] delta = CanvasBufferDelta.encode(canvas);
        // Uncompressed this frame is 256000 bytes. Drawn content is mostly flat fill, which is the
        // ideal case for deflate, and that is what makes a canvas animatable rather than something
        // you redraw once and leave.
        assertTrue(delta.length < 16 * 1024,
            "a full frame cost " + delta.length + " bytes, too much to send repeatedly");

        final CanvasBuffer client = new CanvasBuffer(8, 8);
        assertTrue(CanvasBufferDelta.apply(delta, client), "a full frame should resize the client");
        assertEquals(320, client.getWidth());
        assertEquals(RED, client.getPixel(100, 50));
        assertEquals(0xFF203040, client.getPixel(300, 180));
    }

    @Test
    void nothingChangedCostsNothing() {
        final CanvasBuffer canvas = canvas(320, 200);
        assertEquals(0, CanvasBufferDelta.encode(canvas).length);
        assertTrue(CanvasBufferDelta.apply(new byte[0], new CanvasBuffer(320, 200)));
    }

    @Test
    void refusesAPayloadItCannotTrust() {
        final CanvasBuffer client = new CanvasBuffer(320, 200);
        // A packet can be malformed or from a mismatched version; the client says so and asks for a
        // full frame rather than writing whatever it decoded into its buffer.
        assertFalse(CanvasBufferDelta.apply(new byte[]{99, 0, 1, 1, 0}, client));
        assertFalse(CanvasBufferDelta.apply(new byte[]{1, 0, (byte) 0xFF, (byte) 0xFF, 3}, client));

        // A partial update that does not match the client's size cannot be placed, so it is refused
        // rather than landing its rows in the wrong place.
        final CanvasBuffer other = canvas(64, 64);
        other.setPixel(1, 1, RED);
        assertFalse(CanvasBufferDelta.apply(CanvasBufferDelta.encode(other), client));
    }
}
