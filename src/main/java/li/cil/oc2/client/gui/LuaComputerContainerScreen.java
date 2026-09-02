/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import li.cil.oc2.client.gui.widget.ImageButton;
import li.cil.oc2.client.gui.widget.ToggleImageButton;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.container.LuaComputerContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * The Lua computer's window, drawn on the same background and with the same controls as the
 * RISC-V computer's, because it is the same kind of thing and a player has already learned where
 * everything is.
 * <p>
 * The one control that differs is the second button. On the RISC-V machine it switches to that
 * computer's terminal; this machine has no terminal of its own, so it opens the one belonging to a
 * screen against it, and says so instead when there is none.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaComputerContainerScreen extends AbstractModContainerScreen<LuaComputerContainer> {
    private static final int CONTROLS_TOP = 8;
    private static final int BUTTON_SIZE = 12;

    ///////////////////////////////////////////////////////////////////

    public LuaComputerContainerScreen(final LuaComputerContainer container, final Inventory inventory, final Component title) {
        super(container, inventory, title);
        imageWidth = Sprites.COMPUTER_CONTAINER.width;
        imageHeight = Sprites.COMPUTER_CONTAINER.height;
        inventoryLabelY = imageHeight - 94;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Tells JEI and friends that the sidebar is part of this window, so their overlays do not sit
     * on top of the buttons.
     */
    public List<Rect2i> getExtraAreas() {
        return List.of(new Rect2i(
            leftPos - Sprites.SIDEBAR_2.width, topPos + CONTROLS_TOP,
            Sprites.SIDEBAR_2.width, Sprites.SIDEBAR_2.height));
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(new ToggleImageButton(
            leftPos - Sprites.SIDEBAR_2.width + 4, topPos + CONTROLS_TOP + 4,
            BUTTON_SIZE, BUTTON_SIZE,
            Sprites.POWER_BUTTON_BASE, Sprites.POWER_BUTTON_PRESSED, Sprites.POWER_BUTTON_ACTIVE
        ) {
            @Override
            protected void updateWidgetNarration(final NarrationElementOutput output) {
            }

            @Override
            public void onPress() {
                super.onPress();
                menu.sendPowerStateToServer(!menu.isMachineRunning());
            }

            @Override
            public boolean isToggled() {
                return menu.isMachineRunning();
            }
        }).withTooltip(
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_CAPTION),
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_DESCRIPTION));

        addRenderableWidget(new ImageButton(
            leftPos - Sprites.SIDEBAR_2.width + 4, topPos + CONTROLS_TOP + 4 + 14,
            BUTTON_SIZE, BUTTON_SIZE,
            Sprites.INVENTORY_BUTTON_ACTIVE, Sprites.INVENTORY_BUTTON_INACTIVE
        ) {
            @Override
            protected void updateWidgetNarration(final NarrationElementOutput output) {
            }

            @Override
            public void onPress() {
                openAttachedTerminal();
            }
        }.withTooltip(Component.translatable("gui.oc2r.lua_computer.open_terminal")));
    }

    @Override
    protected void renderBg(final GuiGraphics graphics, final float partialTicks, final int mouseX, final int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);

        Sprites.SIDEBAR_2.draw(graphics, leftPos - Sprites.SIDEBAR_2.width, topPos + CONTROLS_TOP);
        Sprites.COMPUTER_CONTAINER.draw(graphics, leftPos, topPos);
    }

    @Override
    protected void renderFg(final GuiGraphics graphics, final float partialTicks, final int mouseX, final int mouseY) {
        super.renderFg(graphics, partialTicks, mouseX, mouseY);

        final boolean running = menu.isMachineRunning();
        graphics.drawString(font, Component.translatable(running
                    ? "gui.oc2r.lua_computer.running"
                    : "gui.oc2r.lua_computer.stopped")
                .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
            leftPos + 8, topPos + 6, 0xFFFFFF, false);

        if (menu.getAttachedScreenCount() == 0) {
            graphics.drawString(font,
                Component.translatable("gui.oc2r.lua_computer.no_screen_attached")
                    .withStyle(ChatFormatting.RED),
                leftPos + 8, topPos + 96, 0xFFFFFF, false);
        }
    }

    ///////////////////////////////////////////////////////////////////

    private void openAttachedTerminal() {
        for (final Direction direction : Direction.values()) {
            if (menu.getLevel().getBlockEntity(menu.getPos().relative(direction))
                instanceof final LuaScreenBlockEntity screen) {
                LuaTerminalScreens.open(screen);
                return;
            }
        }

        // Nothing to open. The window already says so in red; saying it again in the action bar
        // would be the third place, and this button is right next to the message.
    }
}
