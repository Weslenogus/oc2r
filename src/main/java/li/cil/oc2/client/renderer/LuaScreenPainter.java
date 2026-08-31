/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import li.cil.oc2.common.machine.screen.TextBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws a {@link TextBuffer} through Minecraft's font, for both the in-world block face and the
 * terminal window.
 * <p>
 * A cell at a time, rather than a run at a time. Minecraft's font is proportional, so a run drawn
 * as one string would put its glyphs wherever their widths landed and the grid would drift; an
 * {@code i} and an {@code m} have to occupy the same column for a box drawing character to line up
 * with the one below it. Each glyph is therefore placed at its cell and scaled horizontally to fit,
 * which is what turns a proportional font into the fixed grid a terminal needs.
 * <p>
 * That costs one draw call per non-blank cell, up to 8000 on a full Tier 3 screen. They batch into
 * a single buffer, so the cost is CPU side rather than draw calls to the GPU, and the block
 * renderer stops drawing past {@link #MAX_RENDER_DISTANCE_SQUARED} where the text is too small to
 * read anyway.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaScreenPainter {
    /**
     * Width of one cell in font units. Six is the width of most glyphs in Minecraft's default
     * font, so typical Latin text needs no scaling at all.
     */
    public static final float CELL_WIDTH = 6;

    /**
     * Height of one cell in font units, matching the font's line height.
     */
    public static final float CELL_HEIGHT = 9;

    /**
     * Beyond this, a screen is not drawn. At 160 columns across one block, a cell is a fraction of
     * a pixel from any distance, and drawing thousands of them for a smear is wasted work.
     */
    public static final double MAX_RENDER_DISTANCE_SQUARED = 24 * 24;

    private LuaScreenPainter() {
    }

    /**
     * Draws the buffer with its top left corner at the origin of the current pose, one cell per
     * {@link #CELL_WIDTH} by {@link #CELL_HEIGHT} units.
     *
     * @param buffer      the buffer to draw. The caller must hold the owning screen's lock.
     * @param pose        the transform to draw under.
     * @param buffers     where to batch the glyphs.
     * @param packedLight the light value to draw at.
     */
    public static void draw(final TextBuffer buffer, final PoseStack pose,
                            final MultiBufferSource buffers, final int packedLight) {
        final Font font = Minecraft.getInstance().font;
        final int width = buffer.getViewportWidth();
        final int height = buffer.getViewportHeight();

        final StringBuilder cell = new StringBuilder(2);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final char value = buffer.getRawChar(x, y);
                if (value == TextBuffer.WIDE_CHAR_CONTINUATION) {
                    // The left half already drew this glyph across both columns.
                    continue;
                }

                final int background = 0xFF000000 | buffer.getBackgroundAt(x, y);
                final int foreground = 0xFF000000 | buffer.getForegroundAt(x, y);
                final boolean isBlank = value == ' ';

                cell.setLength(0);
                cell.append(isBlank ? ' ' : value);
                final String text = cell.toString();

                // A blank cell still needs its background drawn, and drawInBatch will happily do
                // that for a space, so there is no separate path for it.
                final float glyphWidth = font.width(text);

                pose.pushPose();
                pose.translate(x * CELL_WIDTH, y * CELL_HEIGHT, 0);

                if (glyphWidth > CELL_WIDTH) {
                    // Squeeze anything too wide, which is mostly the box drawing characters an
                    // operating system builds its interface out of. They have to fill the cell
                    // exactly or a drawn border shows gaps at every join.
                    pose.scale(CELL_WIDTH / glyphWidth, 1, 1);
                } else if (glyphWidth > 0) {
                    // Centre anything narrower instead of stretching it. A full stop is two pixels
                    // wide; blown up to six it stops looking like a full stop.
                    pose.translate((CELL_WIDTH - glyphWidth) / 2, 0, 0);
                }

                font.drawInBatch(text, 0, 0, foreground, false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, background, packedLight);

                pose.popPose();
            }
        }
    }

    /**
     * Width of a buffer's visible area in font units.
     */
    public static float widthOf(final TextBuffer buffer) {
        return buffer.getViewportWidth() * CELL_WIDTH;
    }

    /**
     * Height of a buffer's visible area in font units.
     */
    public static float heightOf(final TextBuffer buffer) {
        return buffer.getViewportHeight() * CELL_HEIGHT;
    }
}
