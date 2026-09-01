/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import li.cil.oc2.common.blockentity.LuaScreenView;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Opens the terminal window for a screen.
 * <p>
 * A separate entry point so the block, which exists on both sides, never mentions a client only
 * class by name. On a dedicated server that reference would be resolved when the block class is
 * verified, long before anything checks which side it is running on.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaTerminalScreens {
    private LuaTerminalScreens() {
    }

    public static void open(final LuaScreenView view) {
        Minecraft.getInstance().setScreen(new LuaTerminalScreen(view));
    }
}
