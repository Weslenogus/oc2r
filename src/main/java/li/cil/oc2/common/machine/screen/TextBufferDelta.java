/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Encodes what changed in a {@link TextBuffer} since it was last synchronized, so a screen can be
 * kept up to date on clients without resending it.
 * <p>
 * A Tier 3 screen is 8000 cells. Sending all of them every tick would be roughly 32KB per screen
 * per tick before any framing, which is not a sensible thing to put on the wire twenty times a
 * second for a display that mostly shows a blinking cursor. What actually changes between ticks is
 * usually a few dozen cells, so the encoding is built around that: one span per dirty row, and
 * within a span, runs of cells sharing a colour pair, which is what a line of text looks like.
 * <p>
 * The format is deliberately a plain {@code byte[]} rather than anything Minecraft specific, so it
 * can be tested directly and so the packet class stays a thin wrapper.
 */
public final class TextBufferDelta {
    /**
     * Bumped if the wire format changes, so a mismatched client is told to expect a full redraw
     * rather than decoding garbage.
     */
    private static final byte VERSION = 1;

    private static final int FLAG_FULL_REDRAW = 1;
    private static final int FLAG_PALETTE = 2;

    private TextBufferDelta() {
    }

    /**
     * Encodes the buffer's pending changes.
     * <p>
     * Does not clear the dirty state: the caller decides when the payload has actually been
     * handed to the network, so that a failed send does not silently lose a frame.
     *
     * @param buffer the buffer to encode.
     * @return the payload, or an empty array if nothing changed.
     */
    public static byte[] encode(final TextBuffer buffer) {
        if (!buffer.isDirty()) {
            return new byte[0];
        }

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        try (final DataOutputStream out = new DataOutputStream(bytes)) {
            final boolean full = buffer.isFullRedraw();
            // The palette rides along with a full redraw because that is exactly when it can have
            // changed in a way spans cannot describe.
            final int flags = (full ? FLAG_FULL_REDRAW : 0) | (full ? FLAG_PALETTE : 0);

            out.writeByte(VERSION);
            out.writeByte(flags);
            writeUnsigned(out, buffer.getWidth());
            writeUnsigned(out, buffer.getHeight());
            writeUnsigned(out, buffer.getViewportWidth());
            writeUnsigned(out, buffer.getViewportHeight());
            out.writeByte(buffer.getDepth().getBits());

            if ((flags & FLAG_PALETTE) != 0) {
                final int[] palette = buffer.getFormat().getPalette();
                writeUnsigned(out, palette.length);
                for (final int color : palette) {
                    out.writeByte((color >>> 16) & 0xFF);
                    out.writeByte((color >>> 8) & 0xFF);
                    out.writeByte(color & 0xFF);
                }
            }

            int spanCount = 0;
            for (int row = 0; row < buffer.getHeight(); row++) {
                if (full || buffer.isRowDirty(row)) {
                    spanCount++;
                }
            }
            writeUnsigned(out, spanCount);

            for (int row = 0; row < buffer.getHeight(); row++) {
                final boolean dirty = full || buffer.isRowDirty(row);
                if (!dirty) {
                    continue;
                }

                final int from = full ? 0 : Math.max(0, buffer.getDirtyMin(row));
                final int to = full ? buffer.getWidth() - 1
                    : Math.min(buffer.getWidth() - 1, buffer.getDirtyMax(row));

                writeUnsigned(out, row);
                writeUnsigned(out, from);
                writeUnsigned(out, to - from + 1);

                int x = from;
                while (x <= to) {
                    // Extend the run for as long as the colour pair holds. A line of text is one
                    // run; a syntax highlighted line is a handful.
                    final short color = buffer.getPackedColor(x, row);
                    int end = x;
                    while (end + 1 <= to && buffer.getPackedColor(end + 1, row) == color) {
                        end++;
                    }

                    writeUnsigned(out, end - x + 1);
                    out.writeShort(color);
                    for (int i = x; i <= end; i++) {
                        writeUnsigned(out, buffer.getRawChar(i, row));
                    }

                    x = end + 1;
                }
            }
        } catch (final IOException e) {
            // A ByteArrayOutputStream does not do I/O, so this cannot happen in practice.
            throw new UncheckedIOException(e);
        }

        return bytes.toByteArray();
    }

    /**
     * Applies a payload produced by {@link #encode(TextBuffer)} to a buffer, which is what the
     * client does with its copy of the screen.
     *
     * @return {@code true} if the payload was understood and applied.
     */
    public static boolean apply(final byte[] payload, final TextBuffer buffer) {
        if (payload.length == 0) {
            return true;
        }

        try (final DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readByte() != VERSION) {
                return false;
            }

            final int flags = in.readByte() & 0xFF;
            final int width = readUnsigned(in);
            final int height = readUnsigned(in);
            final int viewportWidth = readUnsigned(in);
            final int viewportHeight = readUnsigned(in);
            final ColorDepth depth = ColorDepth.fromBits(in.readByte());

            // Order matters: the depth swap clears the buffer, so it has to happen before the
            // cells arrive, and the resolution has to be right before the spans are placed.
            buffer.setDepth(depth);
            buffer.setResolution(width, height);
            buffer.setViewport(viewportWidth, viewportHeight);

            if ((flags & FLAG_PALETTE) != 0) {
                final int count = readUnsigned(in);
                final int[] palette = new int[count];
                for (int i = 0; i < count; i++) {
                    palette[i] = ((in.readByte() & 0xFF) << 16)
                        | ((in.readByte() & 0xFF) << 8)
                        | (in.readByte() & 0xFF);
                }
                buffer.getFormat().setPalette(palette);
            }

            final int spanCount = readUnsigned(in);
            for (int span = 0; span < spanCount; span++) {
                final int row = readUnsigned(in);
                final int from = readUnsigned(in);
                final int length = readUnsigned(in);

                int x = from;
                final int to = from + length - 1;
                while (x <= to) {
                    final int runLength = readUnsigned(in);
                    final short color = in.readShort();
                    for (int i = 0; i < runLength; i++) {
                        buffer.setCellRaw(x + i, row, (char) readUnsigned(in), color);
                    }
                    x += runLength;
                }
            }

            return true;
        } catch (final IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * A variable length unsigned integer, seven bits per byte. Cell characters are almost always
     * ASCII and coordinates are almost always small, so this is one byte where a fixed width
     * encoding would spend two or four.
     */
    private static void writeUnsigned(final DataOutputStream out, final int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    private static int readUnsigned(final DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            final byte b = in.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("malformed varint");
            }
        }
    }
}
