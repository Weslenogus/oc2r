/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.common.machine.screen.ColorDepth;
import li.cil.oc2.common.machine.screen.TextBuffer;

import javax.annotation.Nullable;

/**
 * The {@code gpu} component: everything a program uses to put characters on a screen.
 * <p>
 * A card owns no pixels. It holds a binding to a {@link ScreenComponent} plus the current
 * foreground and background, and edits the screen's buffer in place. Rebinding to another screen
 * therefore leaves the first one exactly as it was, which is what lets a program drive several
 * displays from one card.
 * <h2>Call budgets</h2>
 * Drawing calls are marked direct so they run on the machine thread without costing a tick each,
 * which is the only way a full screen repaint finishes in reasonable time: MineOS redraws its
 * desktop with hundreds of calls, and a tick apiece would be measured in seconds. The per tick
 * limits are what stops that from turning into an unbounded amount of work on someone else's
 * server; once a limit is spent, further calls fall back to the synchronized path and the machine
 * yields.
 */
public final class GraphicsCardComponent extends AbstractLuaComponent {
    private final ColorDepth maxDepth;
    private final int maxWidth;
    private final int maxHeight;

    @Nullable private ScreenComponent screen;

    ///////////////////////////////////////////////////////////////////

    /**
     * Creates a Tier 3 card: 160x50 and 8 bit colour, which is the combination MineOS requires.
     */
    public GraphicsCardComponent(final String address) {
        this(address, ScreenComponent.TIER_THREE_WIDTH, ScreenComponent.TIER_THREE_HEIGHT, ColorDepth.EIGHT_BIT);
    }

    public GraphicsCardComponent(final String address, final int maxWidth, final int maxHeight, final ColorDepth maxDepth) {
        super("gpu", address);
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxDepth = maxDepth;
    }

    ///////////////////////////////////////////////////////////////////

    @Nullable
    public ScreenComponent getScreen() {
        return screen;
    }

    /**
     * Binds without going through Lua, used to wire a card to the screen it was attached to before
     * a reload.
     */
    public void setScreen(@Nullable final ScreenComponent value) {
        screen = value;
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(doc = "function(address:string[, reset:boolean=true]):boolean[, string] -- Binds this card to the screen with the specified address.")
    public Object[] bind(final Context context, final Arguments args) {
        final String address = args.checkString(0);
        final boolean reset = args.optBoolean(1, true);

        final LuaComponent component = context.component(address).orElse(null);
        if (component == null) {
            return new Object[]{null, "no such component"};
        }
        if (!(component instanceof final ScreenComponent target)) {
            return new Object[]{null, "not a screen"};
        }

        screen = target;

        if (reset) {
            // Resetting means taking the screen to the best mode this card can drive and clearing
            // it, so a program that binds and starts drawing gets a known state rather than
            // whatever the previous owner left behind.
            synchronized (target.getLock()) {
                final TextBuffer buffer = target.getBuffer();
                buffer.setDepth(bestDepthFor(buffer));
                buffer.setResolution(
                    Math.min(maxWidth, buffer.getMaxWidth()),
                    Math.min(maxHeight, buffer.getMaxHeight()));
                buffer.setForeground(0xFFFFFF, false);
                buffer.setBackground(0x000000, false);
                buffer.clearAll();
            }
            context.signal("screen_resized", target.getComponentAddress(),
                getResolutionWidth(target), getResolutionHeight(target));
        }

        return new Object[]{true};
    }

    @Callback(direct = true, limit = 64, doc = "function():string -- The address of the screen this card is bound to.")
    public Object[] getScreen(final Context context, final Arguments args) {
        final ScreenComponent bound = screen;
        return new Object[]{bound == null ? null : bound.getComponentAddress()};
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 256, doc = "function():number, boolean -- The current background colour and whether it is a palette index.")
    public Object[] getBackground(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            return new Object[]{buffer.getBackgroundValue(), buffer.isBackgroundFromPalette()};
        }
    }

    @Callback(direct = true, limit = 256, doc = "function(value:number[, palette:boolean]):number[, number] -- Sets the background colour. Returns the old colour and, if it came from the palette, its index.")
    public Object[] setBackground(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int value = args.checkInteger(0);
        final boolean isPaletteIndex = args.optBoolean(1, false);

        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            final int previousColor = buffer.getBackgroundColor();
            final Integer previousIndex = buffer.isBackgroundFromPalette() ? buffer.getBackgroundValue() : null;
            buffer.setBackground(value, isPaletteIndex);
            return new Object[]{previousColor, previousIndex};
        }
    }

    @Callback(direct = true, limit = 256, doc = "function():number, boolean -- The current foreground colour and whether it is a palette index.")
    public Object[] getForeground(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            return new Object[]{buffer.getForegroundValue(), buffer.isForegroundFromPalette()};
        }
    }

    @Callback(direct = true, limit = 256, doc = "function(value:number[, palette:boolean]):number[, number] -- Sets the foreground colour. Returns the old colour and, if it came from the palette, its index.")
    public Object[] setForeground(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int value = args.checkInteger(0);
        final boolean isPaletteIndex = args.optBoolean(1, false);

        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            final int previousColor = buffer.getForegroundColor();
            final Integer previousIndex = buffer.isForegroundFromPalette() ? buffer.getForegroundValue() : null;
            buffer.setForeground(value, isPaletteIndex);
            return new Object[]{previousColor, previousIndex};
        }
    }

    @Callback(direct = true, limit = 64, doc = "function(index:number):number -- The colour of a palette entry.")
    public Object[] getPaletteColor(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int index = args.checkInteger(0);
        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().getPaletteColor(index)};
        }
    }

    @Callback(doc = "function(index:number, value:number):number -- Sets a palette entry. Returns the old colour.")
    public Object[] setPaletteColor(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int index = args.checkInteger(0);
        final int value = args.checkInteger(1);
        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().setPaletteColor(index, value)};
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 64, doc = "function():number -- The maximum colour depth this card supports.")
    public Object[] maxDepth(final Context context, final Arguments args) {
        final ScreenComponent bound = screen;
        if (bound == null) {
            return new Object[]{maxDepth.getBits()};
        }
        synchronized (bound.getLock()) {
            return new Object[]{bestDepthFor(bound.getBuffer()).getBits()};
        }
    }

    @Callback(direct = true, limit = 64, doc = "function():number -- The current colour depth.")
    public Object[] getDepth(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().getDepth().getBits()};
        }
    }

    @Callback(doc = "function(bit:number):string -- Sets the colour depth. Returns the name of the previous depth.")
    public Object[] setDepth(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int bits = args.checkInteger(0);

        final ColorDepth depth;
        try {
            depth = ColorDepth.fromBits(bits);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported depth");
        }

        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            if (!depth.isAtMost(bestDepthFor(buffer))) {
                throw new IllegalArgumentException("unsupported depth");
            }
            // OpenComputers reports the previous depth by name rather than by bit count here;
            // OpenOS prints it verbatim, so the exact spelling matters.
            final String previous = describe(buffer.getDepth());
            buffer.setDepth(depth);
            return new Object[]{previous};
        }
    }

    @Callback(direct = true, limit = 64, doc = "function():number, number -- The maximum resolution this card can drive on the bound screen.")
    public Object[] maxResolution(final Context context, final Arguments args) {
        final ScreenComponent bound = screen;
        if (bound == null) {
            return new Object[]{maxWidth, maxHeight};
        }
        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            return new Object[]{
                Math.min(maxWidth, buffer.getMaxWidth()),
                Math.min(maxHeight, buffer.getMaxHeight()),
            };
        }
    }

    @Callback(direct = true, limit = 256, doc = "function():number, number -- The current resolution.")
    public Object[] getResolution(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            return new Object[]{buffer.getWidth(), buffer.getHeight()};
        }
    }

    @Callback(doc = "function(width:number, height:number):boolean -- Sets the resolution. Returns true if it changed.")
    public Object[] setResolution(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int width = args.checkInteger(0);
        final int height = args.checkInteger(1);

        if (width < 1 || height < 1 || width > maxWidth || height > maxHeight) {
            throw new IllegalArgumentException("unsupported resolution");
        }

        final boolean changed;
        synchronized (bound.getLock()) {
            changed = bound.getBuffer().setResolution(width, height);
        }

        if (changed) {
            // Operating systems redraw on this signal rather than polling the resolution, so
            // skipping it leaves the display showing the old layout at the new size.
            context.signal("screen_resized", bound.getComponentAddress(), width, height);
        }
        return new Object[]{changed};
    }

    @Callback(direct = true, limit = 256, doc = "function():number, number -- The current viewport size.")
    public Object[] getViewport(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            return new Object[]{buffer.getViewportWidth(), buffer.getViewportHeight()};
        }
    }

    @Callback(doc = "function(width:number, height:number):boolean -- Sets the viewport size without changing the resolution. Returns true if it changed.")
    public Object[] setViewport(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int width = args.checkInteger(0);
        final int height = args.checkInteger(1);

        final boolean changed;
        synchronized (bound.getLock()) {
            changed = bound.getBuffer().setViewport(width, height);
        }

        if (changed) {
            context.signal("screen_resized", bound.getComponentAddress(), width, height);
        }
        return new Object[]{changed};
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 512, doc = "function(x:number, y:number):string, number, number, number or nil, number or nil -- The contents of a cell and its colours.")
    public Object[] get(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;

        synchronized (bound.getLock()) {
            final TextBuffer buffer = bound.getBuffer();
            if (x < 0 || y < 0 || x >= buffer.getWidth() || y >= buffer.getHeight()) {
                throw new IllegalArgumentException("index out of bounds");
            }

            final int foregroundIndex = buffer.getForegroundPaletteIndexAt(x, y);
            final int backgroundIndex = buffer.getBackgroundPaletteIndexAt(x, y);
            return new Object[]{
                String.valueOf(buffer.getChar(x, y)),
                buffer.getForegroundAt(x, y),
                buffer.getBackgroundAt(x, y),
                foregroundIndex < 0 ? null : foregroundIndex,
                backgroundIndex < 0 ? null : backgroundIndex,
            };
        }
    }

    @Callback(direct = true, limit = 256, doc = "function(x:number, y:number, value:string[, vertical:boolean]):boolean -- Writes text to the screen.")
    public Object[] set(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final String value = args.checkString(2);
        final boolean vertical = args.optBoolean(3, false);

        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().set(x, y, value, vertical)};
        }
    }

    @Callback(direct = true, limit = 512, doc = "function(x:number, y:number, width:number, height:number, char:string):boolean -- Fills a rectangle with a character.")
    public Object[] fill(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        final String value = args.checkString(4);

        if (value.codePointCount(0, value.length()) != 1) {
            throw new IllegalArgumentException("invalid fill value");
        }

        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().fill(x, y, width, height, (char) value.codePointAt(0))};
        }
    }

    @Callback(direct = true, limit = 256, doc = "function(x:number, y:number, width:number, height:number, tx:number, ty:number):boolean -- Copies a rectangle to another position.")
    public Object[] copy(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        final int deltaX = args.checkInteger(4);
        final int deltaY = args.checkInteger(5);

        synchronized (bound.getLock()) {
            return new Object[]{bound.getBuffer().copy(x, y, width, height, deltaX, deltaY)};
        }
    }

    ///////////////////////////////////////////////////////////////////

    private ScreenComponent requireScreen() {
        final ScreenComponent bound = screen;
        if (bound == null) {
            throw new IllegalStateException("no screen");
        }
        return bound;
    }

    /**
     * The best depth this card can drive on a screen: the lower of what the card and the screen
     * each support.
     */
    private ColorDepth bestDepthFor(final TextBuffer buffer) {
        return maxDepth.isAtMost(buffer.getMaxDepth()) ? maxDepth : buffer.getMaxDepth();
    }

    private static int getResolutionWidth(final ScreenComponent screen) {
        synchronized (screen.getLock()) {
            return screen.getBuffer().getWidth();
        }
    }

    private static int getResolutionHeight(final ScreenComponent screen) {
        synchronized (screen.getLock()) {
            return screen.getBuffer().getHeight();
        }
    }

    private static String describe(final ColorDepth depth) {
        return switch (depth) {
            case ONE_BIT -> "OneBit";
            case FOUR_BIT -> "FourBit";
            case EIGHT_BIT -> "EightBit";
        };
    }
}
