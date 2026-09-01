/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import li.cil.oc2.client.renderer.CanvasPainter;
import li.cil.oc2.client.renderer.LuaScreenPainter;
import li.cil.oc2.common.block.LuaComputerBlock;
import li.cil.oc2.common.blockentity.LuaScreenBlockEntity;
import li.cil.oc2.common.machine.input.KeyboardMap;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenInputMessage;
import li.cil.oc2.common.network.message.LuaScreenRequestMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A full window view of a Lua screen, and the way a player actually uses one.
 * <p>
 * A Tier 3 screen is 160 by 50 characters. Rendered on the side of a block that is unreadable from
 * anywhere you could stand, and MineOS is a desktop that expects a pointer, so the block face is a
 * preview and this is the interface.
 * <p>
 * Input capture follows the same idea as the mod's other terminals: while capture is on the machine
 * receives every key, including escape, which programs use; while it is off the window behaves like
 * any other and escape closes it. Without the toggle there would be no way to leave a screen whose
 * program wants escape for itself.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaTerminalScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 16;

    private final LuaScreenBlockEntity screen;

    private boolean captureInput = true;

    // Where the grid ended up on screen, so a click can be turned back into a cell.
    private float gridLeft;
    private float gridTop;
    private float gridScale = 1;

    private int lastDragButton = -1;

    ///////////////////////////////////////////////////////////////////

    public LuaTerminalScreen(final LuaScreenBlockEntity screen) {
        super(Component.translatable("gui.oc2r.lua_terminal"));
        this.screen = screen;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        super.init();

        // Ask for a full copy on open: the block renderer may never have drawn this screen, in
        // which case the client's buffer is still blank.
        Network.sendToServer(new LuaScreenRequestMessage(screen));

        addRenderableWidget(Button.builder(captureCaption(), button -> {
            captureInput = !captureInput;
            button.setMessage(captureCaption());
        }).bounds(width - BUTTON_WIDTH - MARGIN, MARGIN / 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public boolean isPauseScreen() {
        // The machine keeps running whether or not anyone is looking at it, and pausing a single
        // player world while a program works would be a surprising thing for a screen to do.
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !captureInput;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        renderBackground(graphics);

        // The lock covers reading and drawing the buffer only. Holding it across the widget
        // rendering below would block the machine thread on unrelated work.
        synchronized (screen.getScreen().getLock()) {
            final boolean isCanvas = screen.getScreen().getMode() == ScreenMode.CANVAS;
            final CanvasBuffer canvas = isCanvas ? screen.getScreen().getOrCreateCanvas() : null;
            final TextBuffer buffer = screen.getBuffer();

            // Both modes are laid out the same way: work out the picture's size in its own units,
            // fit it to the window, and draw. Only the last step differs.
            final float gridWidth = canvas != null ? canvas.getWidth() : LuaScreenPainter.widthOf(buffer);
            final float gridHeight = canvas != null ? canvas.getHeight() : LuaScreenPainter.heightOf(buffer);
            if (gridWidth <= 0 || gridHeight <= 0) {
                gridScale = 0;
                super.render(graphics, mouseX, mouseY, partialTicks);
                return;
            }

            final float available = height - MARGIN * 2 - BUTTON_HEIGHT;
            gridScale = Math.min((width - MARGIN * 2) / gridWidth, available / gridHeight);
            gridLeft = (width - gridWidth * gridScale) / 2;
            gridTop = MARGIN + BUTTON_HEIGHT + (available - gridHeight * gridScale) / 2;

            // A black mat behind the grid, so a screen narrower than the window still reads as a
            // display rather than as text floating over the world.
            graphics.fill((int) gridLeft - 2, (int) gridTop - 2,
                (int) (gridLeft + gridWidth * gridScale) + 2,
                (int) (gridTop + gridHeight * gridScale) + 2, 0xFF000000);

            final PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(gridLeft, gridTop, 0);
            pose.scale(gridScale, gridScale, 1);
            if (canvas != null) {
                // The painter draws into a unit square, so give it the whole area to fill.
                pose.scale(gridWidth, gridHeight, 1);
                CanvasPainter.draw(screen, canvas, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT);
            } else {
                LuaScreenPainter.draw(buffer, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT);
            }
            pose.popPose();
            graphics.flush();
        }

        if (!captureInput) {
            graphics.drawString(font,
                Component.translatable("gui.oc2r.lua_terminal.input_released")
                    .withStyle(ChatFormatting.GRAY),
                MARGIN, MARGIN / 2 + (BUTTON_HEIGHT - 8) / 2, 0xFFFFFF);
        }

        renderIdleHint(graphics);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * Says why the screen is empty, when it is.
     * <p>
     * A blank screen has three causes and they look identical: no computer next to it, a computer
     * that is switched off, and a program that has not drawn anything. Only the last of those is
     * the screen working as intended, and it is the least likely one to be looking at a player who
     * has just placed the blocks.
     * <p>
     * Drawn only over a blank buffer, so a program's output is never covered.
     */
    private void renderIdleHint(final GuiGraphics graphics) {
        final Level level = screen.getLevel();
        if (level == null) {
            return;
        }

        synchronized (screen.getScreen().getLock()) {
            if (screen.getScreen().getMode() != ScreenMode.TEXT || !screen.getBuffer().isBlank()) {
                return;
            }
        }

        boolean hasComputer = false;
        for (final Direction direction : Direction.values()) {
            final BlockState state = level.getBlockState(screen.getBlockPos().relative(direction));
            if (state.getBlock() instanceof LuaComputerBlock) {
                hasComputer = true;
                if (state.getValue(LuaComputerBlock.LIT)) {
                    // Running, and simply has not drawn yet. Nothing to explain.
                    return;
                }
            }
        }

        graphics.drawCenteredString(font,
            Component.translatable(hasComputer
                    ? "gui.oc2r.lua_terminal.hint.power"
                    : "gui.oc2r.lua_terminal.hint.no_computer")
                .withStyle(ChatFormatting.GRAY),
            width / 2, height / 2 - 4, 0xFFFFFF);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (!captureInput) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // Paste is handled here rather than being forwarded as keystrokes, because a program
        // reading a line wants the text, not a burst of synthetic key events.
        if (Screen.isPaste(keyCode)) {
            final String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                Network.sendToServer(LuaScreenInputMessage.clipboard(screen, clipboard));
            }
            return true;
        }

        // Minecraft splits a keystroke into a key event and, for printable keys, a character
        // event. OpenComputers delivers both in one signal, so the key event carries the code and
        // charTyped carries the character; OpenOS looks at whichever of the two it needs.
        Network.sendToServer(LuaScreenInputMessage.key(screen, true, 0,
            KeyboardMap.toLegacyKeyCode(keyCode)));
        return true;
    }

    @Override
    public boolean keyReleased(final int keyCode, final int scanCode, final int modifiers) {
        if (!captureInput) {
            return super.keyReleased(keyCode, scanCode, modifiers);
        }
        Network.sendToServer(LuaScreenInputMessage.key(screen, false, 0,
            KeyboardMap.toLegacyKeyCode(keyCode)));
        return true;
    }

    @Override
    public boolean charTyped(final char value, final int modifiers) {
        if (!captureInput) {
            return super.charTyped(value, modifiers);
        }
        if (KeyboardMap.isPrintable(value)) {
            Network.sendToServer(LuaScreenInputMessage.key(screen, true, value, 0));
        }
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (!captureInput) {
            return false;
        }

        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return false;
        }

        lastDragButton = button;
        Network.sendToServer(LuaScreenInputMessage.mouse(
            screen, LuaScreenInputMessage.Type.TOUCH, cell[0], cell[1], button));
        return true;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double deltaX, final double deltaY) {
        if (super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        if (!captureInput || lastDragButton != button) {
            return false;
        }

        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return false;
        }

        Network.sendToServer(LuaScreenInputMessage.mouse(
            screen, LuaScreenInputMessage.Type.DRAG, cell[0], cell[1], button));
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (captureInput && lastDragButton == button) {
            lastDragButton = -1;
            final int[] cell = toCell(mouseX, mouseY);
            if (cell != null) {
                Network.sendToServer(LuaScreenInputMessage.mouse(
                    screen, LuaScreenInputMessage.Type.DROP, cell[0], cell[1], button));
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double delta) {
        if (!captureInput) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return false;
        }

        Network.sendToServer(LuaScreenInputMessage.mouse(screen, LuaScreenInputMessage.Type.SCROLL,
            cell[0], cell[1], (int) Math.signum(delta)));
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Turns a window coordinate into a zero based cell, or {@code null} if the point is outside the
     * grid. The server clamps these again: what the client thinks it clicked is a request, not a
     * fact.
     */
    private int[] toCell(final double mouseX, final double mouseY) {
        synchronized (screen.getScreen().getLock()) {
            if (screen.getScreen().getMode() == ScreenMode.CANVAS) {
                // In canvas mode a click is a pixel, not a cell. A program drawing its own widgets
                // has no character grid to hit test against, so handing it cell coordinates would
                // make everything it draws unclickable.
                final CanvasBuffer canvas = screen.getScreen().getOrCreateCanvas();
                final int px = (int) ((mouseX - gridLeft) / gridScale);
                final int py = (int) ((mouseY - gridTop) / gridScale);
                if (px < 0 || py < 0 || px >= canvas.getWidth() || py >= canvas.getHeight()) {
                    return null;
                }
                return new int[]{px, py};
            }
        }

        final int x = (int) ((mouseX - gridLeft) / (LuaScreenPainter.CELL_WIDTH * gridScale));
        final int y = (int) ((mouseY - gridTop) / (LuaScreenPainter.CELL_HEIGHT * gridScale));

        synchronized (screen.getScreen().getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            if (x < 0 || y < 0 || x >= buffer.getViewportWidth() || y >= buffer.getViewportHeight()) {
                return null;
            }
        }

        return new int[]{x, y};
    }

    private Component captureCaption() {
        return Component.translatable(captureInput
            ? "gui.oc2r.lua_terminal.release_input"
            : "gui.oc2r.lua_terminal.capture_input");
    }
}
