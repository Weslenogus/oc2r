/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.renderer.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import li.cil.oc2.client.renderer.LuaScreenPainter;
import li.cil.oc2.common.block.LuaScreenBlock;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenRequestMessage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
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
public final class LuaScreenRenderer implements BlockEntityRenderer<LuaScreenBlockEntity> {
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
    private static final Map<LuaScreenBlockEntity, Long> lastSyncRequest = new WeakHashMap<>();

    private final BlockEntityRenderDispatcher renderer;

    ///////////////////////////////////////////////////////////////////

    public LuaScreenRenderer(final BlockEntityRendererProvider.Context context) {
        this.renderer = context.getBlockEntityRenderDispatcher();
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void render(final LuaScreenBlockEntity screen, final float partialTicks, final PoseStack stack,
                       final MultiBufferSource bufferSource, final int light, final int overlay) {
        final Direction facing = screen.getBlockState().getValue(LuaScreenBlock.FACING);
        final Vec3 cameraPosition = renderer.camera.getEntity().getEyePosition(partialTicks);
        final Vec3 blockCenter = Vec3.atCenterOf(screen.getBlockPos());

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
        stack.translate(1, 1, -0.005f);
        stack.scale(-1, -1, -1);

        synchronized (screen.getScreen().getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            final float width = LuaScreenPainter.widthOf(buffer);
            final float height = LuaScreenPainter.heightOf(buffer);
            if (width <= 0 || height <= 0) {
                stack.popPose();
                return;
            }

            // Fit the grid to the block face, leaving a small margin so the text does not run into
            // the bezel. Uniform scale, so a 160 by 50 screen keeps its aspect ratio.
            final float margin = 0.05f;
            final float scale = Math.min((1 - margin * 2) / width, (1 - margin * 2) / height);
            stack.translate((1 - width * scale) / 2, (1 - height * scale) / 2, 0);
            stack.scale(scale, scale, scale);

            // Screens are self lit: a terminal in a dark room is still readable.
            LuaScreenPainter.draw(buffer, stack, bufferSource, LightTexture.FULL_BRIGHT);
        }

        stack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(final LuaScreenBlockEntity screen) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return (int) Math.sqrt(LuaScreenPainter.MAX_RENDER_DISTANCE_SQUARED);
    }

    ///////////////////////////////////////////////////////////////////

    private static void requestResyncIfDue(final LuaScreenBlockEntity screen) {
        final long now = System.currentTimeMillis();
        final Long last = lastSyncRequest.get(screen);
        if (last != null && now - last < RESYNC_INTERVAL_MILLIS) {
            return;
        }
        lastSyncRequest.put(screen, now);
        Network.sendToServer(new LuaScreenRequestMessage(screen));
    }
}
