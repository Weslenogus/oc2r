/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import li.cil.oc2.client.renderer.CanvasPainter;
import li.cil.oc2.client.renderer.LuaScreenPainter;
import li.cil.oc2.common.blockentity.LuaScreenView;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenRequestMessage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a Lua screen's character grid onto the front face of its block.
 * <p>
 * The client's copy of the buffer is kept up to date by deltas, but deltas describe changes and a
 * client that has just come into range has nothing to apply them to. Rather than putting 32KB of
 * buffer into every chunk sync, this asks for a full copy the first time it has to draw a screen it
 * has not seen, and then occasionally afterwards so a dropped delta cannot leave the display wrong
 * indefinitely.
 */
public final class LuaScreenRenderer<T extends BlockEntity & LuaScreenView> implements BlockEntityRenderer<T> {
    /**
     * Where on the front of the block the picture goes, in block face coordinates: left, top, width
     * and height with the origin at the top left corner, and how far to inset it from the face.
     * <p>
     * The monitor's whole face is the screen. The computer's is a strip across its front panel,
     * which is where its display actually is on the model, and where the RISC-V computer draws its
     * own terminal.
     */
    public record Face(float left, float top, float width, float height, float depth) {
        public static final Face WHOLE_BLOCK = new Face(0.05f, 0.05f, 0.9f, 0.9f, 0.005f);
        public static final Face COMPUTER_PANEL = new Face(0.06f, 0.14f, 0.88f, 0.22f, 0.068f);
    }

    /**
     * How long between the keep-alive requests that repair a screen which has drifted out of step.
     * Long enough to be negligible traffic, short enough that a wrong display fixes itself before
     * anyone files a bug about it.
     */
    private static final long RESYNC_INTERVAL_MILLIS = 10_000;

    /**
     * Last time a full copy was asked for, per screen. Weak keyed so a screen going out of range
     * does not keep its block entity alive.
     */
    private static final Map<LuaScreenView, Long> lastSyncRequest = new WeakHashMap<>();

    private final BlockEntityRenderDispatcher renderer;
    private final Face face;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenRenderer(final BlockEntityRendererProvider.Context context, final Face face) {
        this.renderer = context.getBlockEntityRenderDispatcher();
        this.face = face;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void render(final T screen, final float partialTicks, final PoseStack stack,
                       final MultiBufferSource bufferSource, final int light, final int overlay) {
        if (!screen.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        final Direction facing = screen.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        final Vec3 cameraPosition = renderer.camera.getEntity().getEyePosition(partialTicks);
        final Vec3 blockCenter = Vec3.atCenterOf(screen.getViewPos());

        if (cameraPosition.distanceToSqr(blockCenter) > LuaScreenPainter.MAX_RENDER_DISTANCE_SQUARED) {
            return;
        }

        // Behind the screen there is nothing to see, and a Tier 3 grid is expensive enough to be
        // worth not drawing.
        final Vec3 relativeCameraPosition = cameraPosition.subtract(blockCenter);
        if (relativeCameraPosition.dot(Vec3.atLowerCornerOf(facing.getNormal())) <= 0) {
            return;
        }

        requestResyncIfDue(screen);

        stack.pushPose();

        // Align with the front face of the block, matching how the computer renders its terminal.
        stack.translate(0.5f, 0, 0.5f);
        stack.mulPose(Axis.YN.rotationDegrees(facing.toYRot() + 180));
        stack.translate(-0.5f, 0, -0.5f);

        // Flip so the grid runs top left to bottom right, and sit just proud of the block face so
        // the text does not z-fight with it.
        stack.translate(1, 1, -face.depth());
        stack.scale(-1, -1, -1);

        synchronized (screen.getScreen().getLock()) {
            final float width;
            final float height;
            final CanvasBuffer canvas;

            if (screen.getScreen().getMode() == ScreenMode.CANVAS) {
                canvas = screen.getScreen().getOrCreateCanvas();
                width = canvas.getWidth();
                height = canvas.getHeight();
            } else {
                canvas = null;
                final TextBuffer buffer = screen.getBuffer();
                width = LuaScreenPainter.widthOf(buffer);
                height = LuaScreenPainter.heightOf(buffer);
            }

            if (width <= 0 || height <= 0) {
                stack.popPose();
                return;
            }

            // Fit to the part of the face that is display, so the picture does not run into the
            // bezel. Uniform scale, so a 160 by 50 grid or a 320 by 200 canvas keeps its aspect
            // ratio rather than being stretched square.
            final float scale = Math.min(face.width() / width, face.height() / height);
            stack.translate(face.left() + (face.width() - width * scale) / 2,
                face.top() + (face.height() - height * scale) / 2, 0);
            stack.scale(scale, scale, scale);

            // Screens are self lit: a terminal in a dark room is still readable.
            if (canvas != null) {
                // The painter draws into a unit square, so undo the per pixel scale it does not
                // need and hand it the whole area instead.
                stack.scale(width, height, 1);
                CanvasPainter.draw(screen, canvas, stack, bufferSource, LightTexture.FULL_BRIGHT);
            } else {
                LuaScreenPainter.draw(screen.getBuffer(), stack, bufferSource, LightTexture.FULL_BRIGHT);
            }
        }

        stack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(final T screen) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return (int) Math.sqrt(LuaScreenPainter.MAX_RENDER_DISTANCE_SQUARED);
    }

    ///////////////////////////////////////////////////////////////////

    private static void requestResyncIfDue(final LuaScreenView screen) {
        final long now = System.currentTimeMillis();
        final Long last = lastSyncRequest.get(screen);
        if (last != null && now - last < RESYNC_INTERVAL_MILLIS) {
            return;
        }
        lastSyncRequest.put(screen, now);
        Network.sendToServer(new LuaScreenRequestMessage(screen.getViewPos()));
    }
}
