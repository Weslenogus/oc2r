/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Opens a Lua computer's panel.
 * <p>
 * A separate entry point for the same reason {@link LuaTerminalScreens} is one: the block exists
 * on both sides, and naming a client only class from it would have a dedicated server resolve that
 * name when the block class is verified, long before anything checks which side it is on.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaComputerScreens {
    private LuaComputerScreens() {
    }

    public static void open(final Level level, final BlockPos pos) {
        Minecraft.getInstance().setScreen(new LuaComputerScreen(level, pos));
    }
}
