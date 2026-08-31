/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import li.cil.oc2.api.API;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a {@link CanvasBuffer} as a textured quad.
 * <p>
 * The text screen next door is drawn a glyph at a time, because a character grid is glyphs and
 * placing them individually is what keeps them on the grid. A canvas is not: it is an image, and
 * the way to put an image on the screen is to upload it once and draw two triangles. Doing it a
 * pixel at a time would be a quarter of a million quads a frame.
 * <p>
 * So each canvas gets a texture on the GPU, refreshed only when the buffer says it changed, and the
 * quad goes through the same batched vertex pipeline as everything else the renderer draws. That is
 * what makes an animating canvas cost about as much as a painting on a wall.
 * <h2>Texture lifetime</h2>
 * Textures are held per screen and weakly, so a screen going out of range does not keep its block
 * entity alive, and are closed when the screen is collected or the world unloads. A texture is
 * rebuilt when the canvas is resized, since its dimensions are fixed once uploaded.
 */
@OnlyIn(Dist.CLIENT)
public final class CanvasPainter {
    /**
     * Past this the canvas is too small on screen to be worth uploading and drawing. Matches what
     * the text painter uses, so the two modes come into view together.
     */
    public static final double MAX_RENDER_DISTANCE_SQUARED = 24 * 24;

    private static final Map<Object, Entry> textures = new WeakHashMap<>();

    private CanvasPainter() {
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Draws the canvas into a one by one square with its origin at the pose's origin.
     * <p>
     * Callers scale the pose to place it, exactly as they do for the text grid, so this does not
     * need to know whether it is being drawn on the side of a block or into a window.
     *
     * @param owner  what the texture is cached against; a block entity, or the screen itself.
     * @param canvas the buffer to draw.
     * @param pose   the transform to draw under.
     * @param light  the packed light value.
     */
    public static void draw(final Object owner, final CanvasBuffer canvas, final PoseStack pose,
                            final MultiBufferSource bufferSource, final int light) {
        final Entry entry = upload(owner, canvas);
        if (entry == null) {
            return;
        }

        // Translucent rather than cutout, because a canvas is ARGB and a program drawing at partial
        // alpha means it. Culled, because the back of a screen is not something to draw.
        final VertexConsumer consumer = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(entry.location));
        final PoseStack.Pose last = pose.last();

        // Two triangles, wound counter clockwise so the front face is the one pointing out of the
        // block. The v axis runs down, matching the buffer's row order.
        vertex(consumer, last, 0, 1, 0, 1, light);
        vertex(consumer, last, 1, 1, 1, 1, light);
        vertex(consumer, last, 1, 0, 1, 0, light);
        vertex(consumer, last, 0, 0, 0, 0, light);
    }

    private static void vertex(final VertexConsumer consumer, final PoseStack.Pose pose,
                               final float x, final float y, final float u, final float v,
                               final int light) {
        consumer.vertex(pose.pose(), x, y, 0)
            .color(0xFF, 0xFF, 0xFF, 0xFF)
            .uv(u, v)
            .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(pose.normal(), 0, 0, 1)
            .endVertex();
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Makes sure the owner's texture matches the buffer, uploading only what has changed.
     */
    private static Entry upload(final Object owner, final CanvasBuffer canvas) {
        Entry entry = textures.get(owner);

        if (entry != null && (entry.width != canvas.getWidth() || entry.height != canvas.getHeight())) {
            // A texture's size is fixed once created, so a resized canvas needs a new one.
            entry.close();
            textures.remove(owner);
            entry = null;
        }

        if (entry == null) {
            entry = Entry.create(canvas.getWidth(), canvas.getHeight());
            if (entry == null) {
                return null;
            }
            textures.put(owner, entry);
            // Nothing has been written to a new texture, so the whole buffer has to go up whatever
            // the buffer thinks is dirty.
            canvas.markAll();
        }

        if (!canvas.isDirty()) {
            return entry;
        }

        final NativeImage image = entry.texture.getPixels();
        if (image == null) {
            return entry;
        }

        final int[] pixels = canvas.getPixels();
        final int width = canvas.getWidth();
        for (int y = 0; y < canvas.getHeight(); y++) {
            final int from = Math.max(0, canvas.getDirtyMin(y));
            final int to = Math.min(width - 1, canvas.getDirtyMax(y));
            if (from > to) {
                continue;
            }
            final int base = y * width;
            for (int x = from; x <= to; x++) {
                // NativeImage is ABGR where the canvas is ARGB, so red and blue swap on the way in.
                image.setPixelRGBA(x, y, toAbgr(pixels[base + x]));
            }
        }

        entry.texture.upload();
        canvas.clearDirty();
        return entry;
    }

    private static int toAbgr(final int argb) {
        return (argb & 0xFF00FF00)
            | ((argb & 0x00FF0000) >>> 16)
            | ((argb & 0x000000FF) << 16);
    }

    /**
     * Releases every texture, for a world unload. Holding GPU memory for screens in a world the
     * player has left is a leak that grows with every server they visit.
     */
    public static void clear() {
        for (final Iterator<Entry> iterator = textures.values().iterator(); iterator.hasNext(); ) {
            iterator.next().close();
            iterator.remove();
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static final class Entry {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final int width;
        private final int height;

        private Entry(final DynamicTexture texture, final ResourceLocation location,
                      final int width, final int height) {
            this.texture = texture;
            this.location = location;
            this.width = width;
            this.height = height;
        }

        static Entry create(final int width, final int height) {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getTextureManager() == null) {
                return null;
            }
            final DynamicTexture texture = new DynamicTexture(width, height, false);
            final ResourceLocation location = minecraft.getTextureManager()
                .register(API.MOD_ID + "/canvas", texture);
            return new Entry(texture, location, width, height);
        }

        void close() {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getTextureManager() != null) {
                minecraft.getTextureManager().release(location);
            }
            texture.close();
        }
    }
}
