/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import li.cil.oc2.client.gui.widget.ToggleImageButton;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.block.LuaComputerBlock;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenPowerMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * What right clicking a Lua computer opens: the machine itself, rather than its display.
 * <p>
 * The two blocks answer two different questions, and putting the terminal on both made the case
 * pointless to click. The screen is where a machine is used; the case is where it is switched on
 * and where you find out what is in it. So this is the case's panel: the power button, what the
 * machine is made of, and whether it has a screen to draw on at all - which is the mistake this
 * arrangement invites, and the one thing nothing else would tell you about.
 * <p>
 * The parts list is worked out here rather than sent from the server, because a Lua machine is a
 * fixed configuration: every one of them has the same processor, memory, cards and disk, by
 * construction. The only part that varies is how many screens are touching it, and the client can
 * see those for itself. Addresses are deliberately left out - they change every reboot and are a
 * program's business; {@code devices} at the shell prints them.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaComputerScreen extends Screen {
    private static final int WIDTH = 216;
    private static final int HEIGHT = 132;
    private static final int PADDING = 8;
    private static final int BUTTON_SIZE = 12;
    private static final int LINE_HEIGHT = 10;

    private static final int FRAME_COLOR = 0xFF3A4247;
    private static final int FRAME_SHADOW = 0xFF14181B;
    private static final int PANEL_COLOR = 0xFF1B2023;

    private static final int TITLE_COLOR = 0xFFE6ECEF;
    private static final int TEXT_COLOR = 0xFFB9C4C9;
    private static final int DIM_COLOR = 0xFF7E888D;

    /**
     * The parts every Lua machine has, in the order the shell's {@code devices} lists them.
     */
    private static final String[] BUILT_IN = {
        "cpu", "memory", "gpu", "canvas", "disk", "tmpfs", "rom", "eeprom",
    };

    private final Level level;
    private final BlockPos pos;

    private int left;
    private int top;

    ///////////////////////////////////////////////////////////////////

    public LuaComputerScreen(final Level level, final BlockPos pos) {
        super(Component.translatable("gui.oc2r.lua_computer"));
        this.level = level;
        this.pos = pos;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        super.init();

        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;

        final ToggleImageButton power = addRenderableWidget(new ToggleImageButton(
            left + PADDING + 4, top + PADDING + 4, BUTTON_SIZE, BUTTON_SIZE,
            Sprites.POWER_BUTTON_BASE, Sprites.POWER_BUTTON_PRESSED, Sprites.POWER_BUTTON_ACTIVE
        ) {
            @Override
            protected void updateWidgetNarration(final NarrationElementOutput output) {
            }

            @Override
            public void onPress() {
                super.onPress();
                Network.sendToServer(new LuaScreenPowerMessage(pos, !isRunning()));
            }

            @Override
            public boolean isToggled() {
                return isRunning();
            }
        });
        power.withTooltip(
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_CAPTION),
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_DESCRIPTION));
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        renderBackground(graphics);

        graphics.fill(left - 1, top - 1, left + WIDTH + 1, top + HEIGHT + 1, FRAME_SHADOW);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, FRAME_COLOR);
        graphics.fill(left + 1, top + 1, left + WIDTH - 1, top + HEIGHT - 1, PANEL_COLOR);

        // The same sidebar the terminal window uses, so the two are recognisably parts of one
        // machine and the power button is where a player has already learned to look for it.
        Sprites.SIDEBAR_2.draw(graphics, left + PADDING, top + PADDING);

        final int x = left + PADDING + Sprites.SIDEBAR_2.width + 8;
        graphics.drawString(font, title, x, top + PADDING, TITLE_COLOR, false);

        final boolean running = isRunning();
        graphics.drawString(font, Component.translatable(running
                    ? "gui.oc2r.lua_computer.running"
                    : "gui.oc2r.lua_computer.stopped")
                .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.GRAY),
            x, top + PADDING + 12, TEXT_COLOR, false);

        int y = top + PADDING + 30;
        graphics.drawString(font, Component.translatable("gui.oc2r.lua_computer.components"),
            left + PADDING, y, TITLE_COLOR, false);
        y += LINE_HEIGHT + 2;

        for (final Component line : components()) {
            graphics.drawString(font, line, left + PADDING + 4, y, TEXT_COLOR, false);
            y += LINE_HEIGHT;
        }

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The run state, as the block state carries it: the machine itself lives on the server, and
     * the lit flag is the part of it that is already synchronized to us.
     */
    private boolean isRunning() {
        final BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LuaComputerBlock && state.getValue(LuaComputerBlock.LIT);
    }

    private int attachedScreens() {
        int count = 0;
        for (final Direction direction : Direction.values()) {
            if (level.getBlockEntity(pos.relative(direction)) instanceof LuaScreenBlockEntity) {
                count++;
            }
        }
        return count;
    }

    private List<Component> components() {
        final List<Component> lines = new ArrayList<>();
        for (final String part : BUILT_IN) {
            lines.add(Component.translatable("gui.oc2r.lua_computer.component." + part));
        }

        final int screens = attachedScreens();
        if (screens == 0) {
            lines.add(Component.translatable("gui.oc2r.lua_computer.no_screen_attached")
                .withStyle(ChatFormatting.RED));
            lines.add(Component.translatable("gui.oc2r.lua_computer.no_screen_hint")
                .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("gui.oc2r.lua_computer.component.screen", screens));
            lines.add(Component.translatable("gui.oc2r.lua_computer.component.keyboard", screens));
        }

        return lines;
    }
}
