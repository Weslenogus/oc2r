/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.screen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Encodes what changed in a {@link CanvasBuffer} since it was last synchronized.
 * <p>
 * The problem is a different shape from the text buffer's. There, a cell is three bytes and a frame
 * is eight thousand of them; here a pixel is four bytes and a frame is sixty four thousand, so a
 * full one is a quarter of a megabyte and sending it twenty times a second is not something a
 * server can do for even one screen. Two things keep that in hand.
 * <p>
 * Only touched rows are sent, as the span within each that actually changed. A program animating a
 * cursor or redrawing one window pays for that window, not for the frame around it.
 * <p>
 * What is left is then deflated. Pixels are the ideal case for it: a drawn frame is mostly flat
 * fill and repeated colour, so a full 320 by 200 clear compresses to a few hundred bytes, and even
 * detailed artwork halves. It is the difference between a canvas that can be animated and one that
 * can only be redrawn occasionally.
 * <p>
 * The payload is a plain {@code byte[]} rather than anything Minecraft specific, so it can be
 * tested directly and the packet stays a thin wrapper.
 */
public final class CanvasBufferDelta {
    /**
     * Bumped if the wire format changes, so a mismatched client asks for a full redraw rather than
     * decoding garbage.
     */
    private static final byte VERSION = 1;

    private static final int FLAG_FULL_REDRAW = 1;

    private CanvasBufferDelta() {
    }

    /**
     * Encodes the buffer's pending changes.
     * <p>
     * Does not clear the dirty state: the caller decides when the payload has actually been handed
     * to the network, so a failed send does not silently lose a frame.
     *
     * @param buffer the buffer to encode.
     * @return the payload, or an empty array if nothing changed.
     */
    public static byte[] encode(final CanvasBuffer buffer) {
        if (!buffer.isDirty()) {
            return new byte[0];
        }

        final int width = buffer.getWidth();
        final int height = buffer.getHeight();
        final int[] pixels = buffer.getPixels();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try {
            // The header stays outside the compressed section so a reader can check the version and
            // the size before deciding to inflate anything.
            final DataOutputStream header = new DataOutputStream(bytes);
            header.writeByte(VERSION);
            header.writeByte(buffer.isFullRedraw() ? FLAG_FULL_REDRAW : 0);
            writeUnsigned(header, width);
            writeUnsigned(header, height);

            int rows = 0;
            for (int y = 0; y < height; y++) {
                if (buffer.getDirtyMin(y) <= buffer.getDirtyMax(y)) {
                    rows++;
                }
            }
            writeUnsigned(header, rows);
            header.flush();

            try (final DataOutputStream out =
                     new DataOutputStream(new DeflaterOutputStream(bytes))) {
                for (int y = 0; y < height; y++) {
                    final int from = Math.max(0, buffer.getDirtyMin(y));
                    final int to = Math.min(width - 1, buffer.getDirtyMax(y));
                    if (from > to) {
                        continue;
                    }

                    writeUnsigned(out, y);
                    writeUnsigned(out, from);
                    writeUnsigned(out, to - from + 1);
                    final int base = y * width;
                    for (int x = from; x <= to; x++) {
                        out.writeInt(pixels[base + x]);
                    }
                }
            }
        } catch (final IOException e) {
            // Both streams are in memory, so this cannot happen without something much worse
            // already being wrong.
            throw new IllegalStateException("Failed encoding a canvas delta.", e);
        }

        return bytes.toByteArray();
    }

    /**
     * Applies a payload to a buffer, which is what a client does with what it receives.
     *
     * @return {@code true} if the payload was understood. A {@code false} is the client's cue to
     * ask for a full redraw rather than carry on with a buffer it can no longer trust.
     */
    public static boolean apply(final byte[] payload, final CanvasBuffer buffer) {
        if (payload.length == 0) {
            return true;
        }

        final ByteArrayInputStream bytes = new ByteArrayInputStream(payload);
        try {
            final DataInputStream header = new DataInputStream(bytes);
            if (header.readByte() != VERSION) {
                return false;
            }
            final int flags = header.readByte();
            final int width = readUnsigned(header);
            final int height = readUnsigned(header);
            final int rows = readUnsigned(header);

            if (width <= 0 || height <= 0 || width > CanvasBuffer.MAX_WIDTH
                || height > CanvasBuffer.MAX_HEIGHT || rows > height) {
                return false;
            }

            if (buffer.getWidth() != width || buffer.getHeight() != height) {
                if ((flags & FLAG_FULL_REDRAW) == 0) {
                    // A partial update against a buffer of the wrong size would land its spans in
                    // the wrong rows. Ask for the frame instead.
                    return false;
                }
                buffer.setResolution(width, height);
            }

            try (final DataInputStream in = new DataInputStream(new InflaterInputStream(bytes))) {
                final int[] pixels = buffer.getPixels();
                for (int i = 0; i < rows; i++) {
                    final int y = readUnsigned(in);
                    final int from = readUnsigned(in);
                    final int count = readUnsigned(in);
                    if (y < 0 || y >= height || from < 0 || count < 0 || from + count > width) {
                        return false;
                    }

                    final int base = y * width;
                    for (int x = 0; x < count; x++) {
                        pixels[base + from + x] = in.readInt();
                    }
                }
            }

            // The client's copy is the render target; whatever changed in it has to reach the
            // texture, so the whole update counts as dirty on this side.
            buffer.markAll();
            return true;
        } catch (final IOException | RuntimeException e) {
            return false;
        }
    }

    ///////////////////////////////////////////////////////////////////

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
