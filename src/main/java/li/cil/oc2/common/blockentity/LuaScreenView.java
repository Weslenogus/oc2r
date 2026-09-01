/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.screen.TextBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A block whose face is a Lua screen, and which a terminal window can be opened on.
 * <p>
 * There are two: the {@link LuaComputerBlockEntity}, which has a display of its own the way the
 * RISC-V computer does, and the {@link LuaScreenBlockEntity}, which is an external monitor for the
 * computers around it. From the outside they behave the same - the same buffer, the same deltas on
 * the wire, the same window - so everything from the network messages up talks to this rather than
 * to either class.
 * <p>
 * A computer with a screen built in is not a luxury. Without one a placed computer shows nothing at
 * all until a second block is placed against it, and nothing on the block says so; a machine that
 * boots, runs, and displays nowhere is indistinguishable from one that is broken.
 */
public interface LuaScreenView {
    /**
     * The screen component. On the server this is a real component on a machine's bus; on the
     * client it is only somewhere to apply deltas.
     */
    ScreenComponent getScreen();

    /**
     * The address key events are attributed to. Mouse events come from the screen instead, which is
     * how an operating system tells a keyboard from a pointer.
     */
    String getKeyboardAddress();

    /**
     * Delivers an input signal to whichever machines this display belongs to.
     */
    void signalMachines(String name, Object... args);

    /**
     * Whether a machine that this display belongs to is running, which is what the terminal
     * window's power button shows.
     */
    boolean isMachineRunning();

    /**
     * Turns a machine that this display belongs to on or off.
     */
    void setMachineRunning(boolean value);

    /**
     * The block entity this is. Everything here is one, and the network needs it to work out who is
     * tracking the block.
     */
    BlockEntity getBlockEntity();

    ///////////////////////////////////////////////////////////////////

    default TextBuffer getBuffer() {
        return getScreen().getBuffer();
    }

    default String getScreenAddress() {
        return getScreen().getComponentAddress();
    }

    default BlockPos getViewPos() {
        return getBlockEntity().getBlockPos();
    }
}
