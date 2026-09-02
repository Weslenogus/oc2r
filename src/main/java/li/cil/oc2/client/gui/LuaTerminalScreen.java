/* SPDX-License-Identifier: MIT */

package li.cil.oc2.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import li.cil.oc2.client.gui.widget.ToggleImageButton;
import li.cil.oc2.client.renderer.CanvasPainter;
import li.cil.oc2.client.renderer.LuaScreenPainter;
import li.cil.oc2.common.Constants;
import li.cil.oc2.common.blockentity.LuaScreenSync;
import li.cil.oc2.common.blockentity.LuaScreenView;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.machine.input.KeyboardMap;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.network.Network;
import li.cil.oc2.common.network.message.LuaScreenInputMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The window a Lua machine is used through.
 * <p>
 * It follows the conventions of the mod's other terminals rather than inventing its own, because a
 * player who has used the RISC-V computer already knows them: a power button and an input capture
 * button down the left, the same sprites, and the same rule about who gets your keystrokes.
 * <p>
 * That rule is the important one. Input is captured only while the pointer is over the screen and
 * the capture button is on, so Escape closes the window the rest of the time. Capturing everything
 * unconditionally, which is what this used to do, leaves a window that cannot be closed by the one
 * key everybody tries.
 * <p>
 * The grid itself is drawn to fill the window rather than inside a small frame. A tier 3 screen is
 * 160 by 50 characters; at the size the RISC-V terminal's frame would give it, it is unreadable.
 */
@OnlyIn(Dist.CLIENT)
public final class LuaTerminalScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int BUTTON_SIZE = 12;
    private static final int SIDEBAR_WIDTH = Sprites.SIDEBAR_3.width;
    private static final int SIDEBAR_GAP = 4;

    /**
     * The frame around the grid, in the steel the mod's other terminals are drawn in.
     */
    private static final int FRAME_COLOR = 0xFF3A4247;
    private static final int FRAME_SHADOW = 0xFF14181B;

    private final LuaScreenView view;

    private boolean captureInput = Config.captureInputDefaultState;

    /**
     * Whether the pointer is over the grid, which is half of whether input is captured. Updated
     * while rendering, because that is where the mouse position is handed to us.
     */
    private boolean isMouseOverGrid;

    // Where the grid ended up on screen, so a click can be turned back into a cell.
    private float gridLeft;
    private float gridTop;
    private float gridWidth;
    private float gridHeight;
    private float gridScale = 1;

    private int lastDragButton = -1;

    /**
     * The sidebar's position, worked out with the grid each frame so the controls stay attached to
     * it however the window is resized.
     */
    private int sidebarX = MARGIN;
    private int sidebarY = MARGIN;

    private ToggleImageButton powerButton;
    private ToggleImageButton inputButton;

    ///////////////////////////////////////////////////////////////////

    public LuaTerminalScreen(final LuaScreenView view) {
        super(Component.translatable("gui.oc2r.lua_terminal"));
        this.view = view;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void init() {
        super.init();

        // Ask for a full copy on open: the block renderer may never have drawn this screen, in
        // which case the client's buffer is still blank.
        LuaScreenSync.requestFullSync(view);

        powerButton = addRenderableWidget(new ToggleImageButton(
            sidebarX + 4, sidebarY + 4, BUTTON_SIZE, BUTTON_SIZE,
            Sprites.POWER_BUTTON_BASE, Sprites.POWER_BUTTON_PRESSED, Sprites.POWER_BUTTON_ACTIVE
        ) {
            @Override
            protected void updateWidgetNarration(final NarrationElementOutput output) {
            }

            @Override
            public void onPress() {
                super.onPress();
                Network.sendToServer(new li.cil.oc2.common.network.message.LuaScreenPowerMessage(
                    view, !view.isMachineRunning()));
            }

            @Override
            public boolean isToggled() {
                return view.isMachineRunning();
            }
        });
        powerButton.withTooltip(
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_CAPTION),
            Component.translatable(Constants.COMPUTER_SCREEN_POWER_DESCRIPTION));

        inputButton = addRenderableWidget(new ToggleImageButton(
            sidebarX + 4, sidebarY + 4 + 14, BUTTON_SIZE, BUTTON_SIZE,
            Sprites.INPUT_BUTTON_BASE, Sprites.INPUT_BUTTON_PRESSED, Sprites.INPUT_BUTTON_ACTIVE
        ) {
            @Override
            protected void updateWidgetNarration(final NarrationElementOutput output) {
            }

            @Override
            public void onPress() {
                super.onPress();
                captureInput = !captureInput;
            }

            @Override
            public boolean isToggled() {
                return captureInput;
            }
        });
        inputButton.withTooltip(
            Component.translatable(Constants.TERMINAL_CAPTURE_INPUT_CAPTION),
            Component.translatable(Constants.TERMINAL_CAPTURE_INPUT_DESCRIPTION));
    }

    @Override
    public boolean isPauseScreen() {
        // The machine keeps running whether or not anyone is looking at it, and pausing a single
        // player world while a program works would be a surprising thing for a screen to do.
        return false;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        renderBackground(graphics);

        // The lock covers reading and drawing the buffer only. Holding it across the widget
        // rendering below would block the machine thread on unrelated work.
        synchronized (view.getScreen().getLock()) {
            final boolean isCanvas = view.getScreen().getMode() == ScreenMode.CANVAS;
            final CanvasBuffer canvas = isCanvas ? view.getScreen().getOrCreateCanvas() : null;
            final TextBuffer buffer = view.getBuffer();

            // Both modes are laid out the same way: work out the picture's size in its own units,
            // fit it to the window, and draw. Only the last step differs.
            final float unitsWide = canvas != null ? canvas.getWidth() : LuaScreenPainter.widthOf(buffer);
            final float unitsHigh = canvas != null ? canvas.getHeight() : LuaScreenPainter.heightOf(buffer);
            if (unitsWide <= 0 || unitsHigh <= 0) {
                gridScale = 0;
                super.render(graphics, mouseX, mouseY, partialTicks);
                return;
            }

            final float availableWidth = width - SIDEBAR_WIDTH - SIDEBAR_GAP - MARGIN * 2;
            final float availableHeight = height - MARGIN * 2;
            gridScale = Math.min(availableWidth / unitsWide, availableHeight / unitsHigh);
            gridWidth = unitsWide * gridScale;
            gridHeight = unitsHigh * gridScale;
            gridLeft = MARGIN + SIDEBAR_WIDTH + SIDEBAR_GAP + (availableWidth - gridWidth) / 2;
            gridTop = MARGIN + (availableHeight - gridHeight) / 2;

            isMouseOverGrid = mouseX >= gridLeft && mouseX < gridLeft + gridWidth
                && mouseY >= gridTop && mouseY < gridTop + gridHeight;

            // The frame the rest of the mod's terminals have: a steel border, a dark inner edge,
            // and the screen itself black, so the grid reads as a display rather than as text
            // floating over the world.
            drawFrame(graphics);

            // And the controls, on the mod's own sidebar panel, hard against the left of the
            // screen - the same place the RISC-V terminal keeps them.
            sidebarX = (int) gridLeft - SIDEBAR_GAP - SIDEBAR_WIDTH - 3;
            sidebarY = (int) gridTop - 3;
            Sprites.SIDEBAR_3.draw(graphics, sidebarX, sidebarY);
            powerButton.setX(sidebarX + 4);
            powerButton.setY(sidebarY + 4);
            inputButton.setX(sidebarX + 4);
            inputButton.setY(sidebarY + 4 + 14);

            final PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(gridLeft, gridTop, 0);
            pose.scale(gridScale, gridScale, 1);
            if (canvas != null) {
                // The painter draws into a unit square, so give it the whole area to fill.
                pose.scale(unitsWide, unitsHigh, 1);
                CanvasPainter.draw(view, canvas, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT);
            } else {
                LuaScreenPainter.draw(buffer, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT);
            }
            pose.popPose();
            graphics.flush();
        }

        renderHint(graphics);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * The border and the black screen behind the grid.
     */
    private void drawFrame(final GuiGraphics graphics) {
        final int left = (int) gridLeft;
        final int top = (int) gridTop;
        final int right = (int) (gridLeft + gridWidth);
        final int bottom = (int) (gridTop + gridHeight);

        graphics.fill(left - 3, top - 3, right + 3, bottom + 3, FRAME_COLOR);
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, FRAME_SHADOW);
        graphics.fill(left, top, right, bottom, 0xFF000000);
    }

    /**
     * Says what to do next, when there is a reason to.
     * <p>
     * Three states a player can be stuck in, all of which look like a screen that does nothing: the
     * machine is off, the keyboard is going to Minecraft, or the pointer is not over the screen.
     */
    private void renderHint(final GuiGraphics graphics) {
        final Component hint;
        if (!view.isMachineRunning()) {
            hint = Component.translatable("gui.oc2r.lua_terminal.hint.power");
        } else if (!captureInput) {
            hint = Component.translatable("gui.oc2r.lua_terminal.hint.capture");
        } else if (!isMouseOverGrid) {
            hint = Component.translatable("gui.oc2r.lua_terminal.hint.hover");
        } else {
            return;
        }

        graphics.drawCenteredString(font, hint.copy().withStyle(ChatFormatting.GRAY),
            (int) (gridLeft + gridWidth / 2), (int) (gridTop + gridHeight) + 6, 0xFFFFFF);
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Whether keystrokes belong to the machine rather than to Minecraft.
     * <p>
     * Both halves matter. The button is the deliberate choice, and the pointer being over the
     * screen is what leaves a way out: move the mouse aside and Escape closes the window, exactly
     * as it does on the mod's other terminals.
     */
    private boolean shouldCaptureInput() {
        return captureInput && isMouseOverGrid;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (!shouldCaptureInput()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // Paste is handled here rather than being forwarded as keystrokes, because a program
        // reading a line wants the text, not a burst of synthetic key events.
        if (Screen.isPaste(keyCode)) {
            final String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                Network.sendToServer(LuaScreenInputMessage.clipboard(view, clipboard));
            }
            return true;
        }

        // Minecraft splits a keystroke into a key event and, for printable keys, a character
        // event. OpenComputers delivers both in one signal, so the key event carries the code and
        // charTyped carries the character; OpenOS looks at whichever of the two it needs.
        Network.sendToServer(LuaScreenInputMessage.key(view, true, 0,
            KeyboardMap.toLegacyKeyCode(keyCode)));
        return true;
    }

    @Override
    public boolean keyReleased(final int keyCode, final int scanCode, final int modifiers) {
        if (!shouldCaptureInput()) {
            return super.keyReleased(keyCode, scanCode, modifiers);
        }
        Network.sendToServer(LuaScreenInputMessage.key(view, false, 0,
            KeyboardMap.toLegacyKeyCode(keyCode)));
        return true;
    }

    @Override
    public boolean charTyped(final char value, final int modifiers) {
        if (!shouldCaptureInput()) {
            return super.charTyped(value, modifiers);
        }
        if (KeyboardMap.isPrintable(value)) {
            Network.sendToServer(LuaScreenInputMessage.key(view, true, value, 0));
        }
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return false;
        }

        lastDragButton = button;
        Network.sendToServer(LuaScreenInputMessage.mouse(
            view, LuaScreenInputMessage.Type.TOUCH, cell[0], cell[1], button));
        return true;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double deltaX, final double deltaY) {
        if (super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        if (lastDragButton != button) {
            return false;
        }

        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return false;
        }

        Network.sendToServer(LuaScreenInputMessage.mouse(
            view, LuaScreenInputMessage.Type.DRAG, cell[0], cell[1], button));
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (lastDragButton == button) {
            lastDragButton = -1;
            final int[] cell = toCell(mouseX, mouseY);
            if (cell != null) {
                Network.sendToServer(LuaScreenInputMessage.mouse(
                    view, LuaScreenInputMessage.Type.DROP, cell[0], cell[1], button));
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double delta) {
        final int[] cell = toCell(mouseX, mouseY);
        if (cell == null) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        Network.sendToServer(LuaScreenInputMessage.mouse(view, LuaScreenInputMessage.Type.SCROLL,
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
        if (gridScale <= 0) {
            return null;
        }

        synchronized (view.getScreen().getLock()) {
            if (view.getScreen().getMode() == ScreenMode.CANVAS) {
                // In canvas mode a click is a pixel, not a cell. A program drawing its own widgets
                // has no character grid to hit test against, so handing it cell coordinates would
                // make everything it draws unclickable.
                final CanvasBuffer canvas = view.getScreen().getOrCreateCanvas();
                final int px = (int) ((mouseX - gridLeft) / gridScale);
                final int py = (int) ((mouseY - gridTop) / gridScale);
                if (px < 0 || py < 0 || px >= canvas.getWidth() || py >= canvas.getHeight()) {
                    return null;
                }
                return new int[]{px, py};
            }

            final int x = (int) ((mouseX - gridLeft) / (LuaScreenPainter.CELL_WIDTH * gridScale));
            final int y = (int) ((mouseY - gridTop) / (LuaScreenPainter.CELL_HEIGHT * gridScale));

            final TextBuffer buffer = view.getBuffer();
            if (x < 0 || y < 0 || x >= buffer.getViewportWidth() || y >= buffer.getViewportHeight()) {
                return null;
            }
            return new int[]{x, y};
        }
    }
}
