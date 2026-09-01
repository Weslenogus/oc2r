/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.CanvasBufferDelta;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.machine.screen.TextBufferDelta;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenDeltaMessage;
import li.cil.oc2.common.network.message.LuaScreenRequestMessage;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeping a client's copy of a Lua screen in step with the server's.
 * <p>
 * Shared by the two things that have a screen - the computer and the monitor block - because the
 * job is identical for both and getting it subtly different on one of them would show up as a
 * display that drifts.
 * <p>
 * Only what changed goes on the wire, and only when something did. A screen showing a blinking
 * cursor costs a handful of bytes per second; one that has not been drawn to costs nothing at all.
 */
public final class LuaScreenSync {
    private static final byte[] EMPTY = new byte[0];

    private LuaScreenSync() {
    }

    /**
     * Sends whatever changed this tick to the clients tracking the block.
     */
    public static void tick(final LuaScreenView view) {
        final ScreenMode mode;
        final byte[] payload;
        synchronized (view.getScreen().getLock()) {
            mode = view.getScreen().getMode();
            payload = encode(view, mode, false);
            // Cleared only after encoding, so a frame cannot be lost between the two.
            clearDirty(view, mode);
        }

        if (payload.length > 0) {
            Network.sendToClientsTrackingBlockEntity(
                new LuaScreenDeltaMessage(view.getViewPos(), mode, payload), view.getBlockEntity());
        }
    }

    /**
     * Sends the whole screen to one player, for a client that has just started tracking this block
     * and has nothing to apply deltas to.
     */
    public static void sendFullSync(final LuaScreenView view, final ServerPlayer player) {
        final ScreenMode mode;
        final byte[] payload;
        synchronized (view.getScreen().getLock()) {
            mode = view.getScreen().getMode();
            payload = encode(view, mode, true);
            clearDirty(view, mode);
        }

        if (payload.length > 0) {
            Network.sendToClient(new LuaScreenDeltaMessage(view.getViewPos(), mode, payload), player);
        }
    }

    /**
     * Applies a delta received from the server. Client side only.
     * <p>
     * A payload that will not decode leaves the buffer alone and asks for the whole thing again.
     * That happens when a client's copy has drifted, most often because it started tracking the
     * block part way through a frame, and carrying on with a buffer that no longer matches the
     * server's would leave it wrong until something happened to overwrite every pixel of it.
     */
    public static void applyDelta(final LuaScreenView view, final ScreenMode mode, final byte[] payload) {
        final boolean applied;
        synchronized (view.getScreen().getLock()) {
            view.getScreen().setMode(mode);
            applied = mode == ScreenMode.CANVAS
                ? CanvasBufferDelta.apply(payload, view.getScreen().getOrCreateCanvas())
                : TextBufferDelta.apply(payload, view.getBuffer());
        }

        if (!applied) {
            requestFullSync(view);
        }
    }

    /**
     * Asks the server for the whole screen again. The client's copy is only ever built from deltas,
     * so once it has drifted there is nothing on this side that can put it right.
     */
    public static void requestFullSync(final LuaScreenView view) {
        Network.sendToServer(new LuaScreenRequestMessage(view.getViewPos()));
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Encodes whichever buffer is on show.
     * <p>
     * Only the visible one is sent. A screen that has been flipped to its canvas is not also paying
     * to keep a hidden text grid up to date on every client watching it; switching back marks that
     * buffer whole, which is what makes the first frame after a switch a full one.
     */
    private static byte[] encode(final LuaScreenView view, final ScreenMode mode, final boolean full) {
        if (mode == ScreenMode.CANVAS) {
            final CanvasBuffer canvas = view.getScreen().getOrCreateCanvas();
            if (full) {
                canvas.markAll();
            }
            return canvas.isDirty() ? CanvasBufferDelta.encode(canvas) : EMPTY;
        }

        final TextBuffer buffer = view.getBuffer();
        if (full) {
            buffer.markAllDirty();
        }
        return buffer.isDirty() ? TextBufferDelta.encode(buffer) : EMPTY;
    }

    private static void clearDirty(final LuaScreenView view, final ScreenMode mode) {
        if (mode == ScreenMode.CANVAS) {
            view.getScreen().getOrCreateCanvas().clearDirty();
        } else {
            view.getBuffer().clearDirty();
        }
    }
}
