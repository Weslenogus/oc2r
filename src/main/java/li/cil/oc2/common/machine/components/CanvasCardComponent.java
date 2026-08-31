/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.common.machine.screen.CanvasBuffer;
import li.cil.oc2.common.machine.screen.ScreenMode;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code canvas} component: a Tier 4 graphics card that draws pixels instead of characters.
 * <p>
 * It works the way the text card does and for the same reasons. The card owns no pixels; it binds
 * to a {@link ScreenComponent} and draws into that screen's canvas, so rebinding leaves the first
 * screen as it was and two cards pointed at one screen see each other's output. Colour is card
 * state rather than a parameter on every call, because a program drawing a shape sets a colour once
 * and then draws, and threading it through forty callbacks would make every one of them longer for
 * no gain.
 * <p>
 * Drawing switches the screen into {@link ScreenMode#CANVAS}. Nothing is cleared by that: the text
 * grid keeps what was on it, so a program can put up a splash screen and hand the terminal back
 * intact. A {@code gpu} call switches it back the same way.
 * <h2>Colour</h2>
 * Colours are 32 bit ARGB and alpha means what it says: a shape drawn at half alpha is composited
 * over what is underneath. A program passing a plain {@code 0xRRGGBB}, which is what every
 * OpenComputers program is used to writing, would otherwise draw nothing at all, so a colour whose
 * alpha byte is zero is read as fully opaque. That costs the ability to draw in exactly zero alpha,
 * which is not a thing anyone asks for, and buys not having to explain to every program why its
 * rectangles are invisible.
 * <h2>Call budgets</h2>
 * Every drawing call is direct, so it runs on the machine thread rather than costing a tick, and
 * carries a per tick allowance for the same reason the text card's do. The allowances here are
 * larger because the calls are what a frame is made of: a program animating something issues
 * thousands where a terminal issues dozens.
 */
public final class CanvasCardComponent extends AbstractLuaComponent {
    /**
     * Largest number of points a polygon may have.
     * <p>
     * The fill is quadratic in the point count per scanline, so this is what stops a program from
     * asking for a shape that takes longer to rasterize than the machine's whole time budget.
     */
    private static final int MAX_POLYGON_POINTS = 256;

    /**
     * Largest image, in pixels, that may be handed over in one {@code blit}.
     */
    private static final int MAX_BLIT_PIXELS = CanvasBuffer.MAX_WIDTH * CanvasBuffer.MAX_HEIGHT;

    @Nullable private ScreenComponent screen;

    /**
     * The colour drawing calls use when they are not given one, ARGB. Opaque white, because a
     * program that sets no colour and draws expects to see something.
     */
    private int color = 0xFFFFFFFF;

    ///////////////////////////////////////////////////////////////////

    public CanvasCardComponent(final String address) {
        super("canvas", address);
    }

    ///////////////////////////////////////////////////////////////////

    @Nullable
    public ScreenComponent getScreen() {
        return screen;
    }

    /**
     * Binds without going through Lua, used to wire a card to the screen it was attached to before
     * the world was reloaded.
     */
    public void setScreen(@Nullable final ScreenComponent value) {
        screen = value;
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 64, doc = "function(address:string):boolean -- Binds this card to a screen.")
    public Object[] bind(final Context context, final Arguments args) {
        final String address = args.checkString(0);
        final ScreenComponent target = context.component(address)
            .filter(ScreenComponent.class::isInstance)
            .map(ScreenComponent.class::cast)
            .orElse(null);
        if (target == null) {
            return new Object[]{null, "no such screen"};
        }

        screen = target;
        return new Object[]{true};
    }

    @Callback(direct = true, limit = 64, doc = "function():string -- The address of the screen this card is bound to.")
    public Object[] getScreen(final Context context, final Arguments args) {
        final ScreenComponent bound = screen;
        return new Object[]{bound == null ? null : bound.getComponentAddress()};
    }

    @Callback(direct = true, limit = 64, doc = "function():number, number -- The largest canvas this card can drive.")
    public Object[] maxResolution(final Context context, final Arguments args) {
        return new Object[]{CanvasBuffer.MAX_WIDTH, CanvasBuffer.MAX_HEIGHT};
    }

    @Callback(direct = true, limit = 256, doc = "function():number, number -- The current canvas size in pixels.")
    public Object[] getResolution(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            final CanvasBuffer canvas = bound.getOrCreateCanvas();
            return new Object[]{canvas.getWidth(), canvas.getHeight()};
        }
    }

    @Callback(direct = false, limit = 8, doc = "function(width:number, height:number):boolean -- Resizes the canvas, keeping what fits.")
    public Object[] setResolution(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int width = args.checkInteger(0);
        final int height = args.checkInteger(1);
        if (width < 1 || height < 1 || width > CanvasBuffer.MAX_WIDTH || height > CanvasBuffer.MAX_HEIGHT) {
            throw new IllegalArgumentException("unsupported resolution");
        }

        final boolean changed;
        synchronized (bound.getLock()) {
            changed = bound.getOrCreateCanvas().setResolution(width, height);
        }

        if (changed) {
            // Indirect, and signalled from the server thread, because a program resizing its canvas
            // wants to lay out against the new size and every other card on this screen has to hear
            // about it too.
            context.signal("canvas_resized", bound.getComponentAddress(), width, height);
        }
        return new Object[]{changed};
    }

    @Callback(direct = true, limit = 256, doc = "function():number -- The current drawing colour, as 0xAARRGGBB.")
    public Object[] getColor(final Context context, final Arguments args) {
        return new Object[]{unsigned(color)};
    }

    @Callback(direct = true, limit = 512, doc = "function(argb:number):number -- Sets the drawing colour. Returns the old one.")
    public Object[] setColor(final Context context, final Arguments args) {
        final int previous = color;
        color = toArgb(args.checkLong(0));
        return new Object[]{unsigned(previous)};
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 64, doc = "function([argb:number]):boolean -- Fills the whole canvas.")
    public Object[] clear(final Context context, final Arguments args) {
        return draw(context, canvas -> canvas.clear(colorArgument(args, 0)));
    }

    @Callback(direct = true, limit = 8192, doc = "function(x:number, y:number[, argb:number]):boolean -- Sets one pixel.")
    public Object[] set(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        return draw(context, canvas -> canvas.setPixel(x, y, colorArgument(args, 2)));
    }

    @Callback(direct = true, limit = 8192, doc = "function(x:number, y:number):number -- The colour of one pixel, as 0xAARRGGBB.")
    public Object[] get(final Context context, final Arguments args) {
        final ScreenComponent bound = requireScreen();
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        synchronized (bound.getLock()) {
            return new Object[]{unsigned(bound.getOrCreateCanvas().getPixel(x, y))};
        }
    }

    @Callback(direct = true, limit = 4096, doc = "function(x1:number, y1:number, x2:number, y2:number[, argb:number]):boolean -- Draws a line.")
    public Object[] line(final Context context, final Arguments args) {
        final int x1 = args.checkInteger(0) - 1;
        final int y1 = args.checkInteger(1) - 1;
        final int x2 = args.checkInteger(2) - 1;
        final int y2 = args.checkInteger(3) - 1;
        return draw(context, canvas -> canvas.drawLine(x1, y1, x2, y2, colorArgument(args, 4)));
    }

    @Callback(direct = true, limit = 4096, doc = "function(x:number, y:number, width:number, height:number[, argb:number]):boolean -- Fills a rectangle.")
    public Object[] fill(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        return draw(context, canvas -> canvas.fillRect(x, y, width, height, colorArgument(args, 4)));
    }

    @Callback(direct = true, limit = 4096, doc = "function(x:number, y:number, width:number, height:number[, argb:number]):boolean -- Draws a rectangle outline.")
    public Object[] rect(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        return draw(context, canvas -> canvas.drawRect(x, y, width, height, colorArgument(args, 4)));
    }

    @Callback(direct = true, limit = 2048, doc = "function(x:number, y:number, width:number, height:number[, argb:number]):boolean -- Fills an ellipse inscribed in the rectangle.")
    public Object[] fillEllipse(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        return draw(context, canvas -> canvas.fillEllipse(x, y, width, height, colorArgument(args, 4)));
    }

    @Callback(direct = true, limit = 2048, doc = "function(x:number, y:number, width:number, height:number[, argb:number]):boolean -- Draws an ellipse outline.")
    public Object[] ellipse(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        return draw(context, canvas -> canvas.drawEllipse(x, y, width, height, colorArgument(args, 4)));
    }

    @Callback(direct = true, limit = 1024, doc = "function(points:table[, argb:number]):boolean -- Draws a closed polygon from a flat list of x, y pairs.")
    public Object[] polygon(final Context context, final Arguments args) {
        final int[] points = points(args.checkTable(0));
        return draw(context, canvas -> canvas.drawPolygon(points, colorArgument(args, 1)));
    }

    @Callback(direct = true, limit = 1024, doc = "function(points:table[, argb:number]):boolean -- Fills a polygon from a flat list of x, y pairs.")
    public Object[] fillPolygon(final Context context, final Arguments args) {
        final int[] points = points(args.checkTable(0));
        return draw(context, canvas -> canvas.fillPolygon(points, colorArgument(args, 1)));
    }

    @Callback(direct = true, limit = 512, doc = "function(x:number, y:number, width:number, height:number, tx:number, ty:number):boolean -- Copies a rectangle of the canvas somewhere else on it.")
    public Object[] copy(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        final int toX = args.checkInteger(4) - 1;
        final int toY = args.checkInteger(5) - 1;
        return draw(context, canvas -> canvas.copy(x, y, width, height, toX, toY));
    }

    @Callback(direct = true, limit = 256, doc = "function(x:number, y:number, width:number, pixels:table[, blend:boolean=true]):boolean -- Draws an image given as a flat list of 0xAARRGGBB values.")
    public Object[] blit(final Context context, final Arguments args) {
        final int x = args.checkInteger(0) - 1;
        final int y = args.checkInteger(1) - 1;
        final int width = args.checkInteger(2);
        if (width < 1) {
            throw new IllegalArgumentException("bad argument #3 (width must be positive)");
        }

        final int[] pixels = pixels(args.checkTable(3), width);
        final boolean blend = args.optBoolean(4, true);
        return draw(context, canvas -> canvas.blit(pixels, width, x, y, blend));
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Runs a drawing operation against the bound screen's canvas, switching the screen into canvas
     * mode as it goes.
     */
    private Object[] draw(final Context context, final java.util.function.Consumer<CanvasBuffer> operation) {
        final ScreenComponent bound = requireScreen();
        synchronized (bound.getLock()) {
            operation.accept(bound.getOrCreateCanvas());
            bound.setMode(ScreenMode.CANVAS);
        }
        return new Object[]{true};
    }

    private ScreenComponent requireScreen() {
        final ScreenComponent bound = screen;
        if (bound == null) {
            throw new IllegalStateException("no screen");
        }
        return bound;
    }

    /**
     * The colour for a call: the one it was given, or the card's if it was not.
     */
    private int colorArgument(final Arguments args, final int index) {
        return args.isDefined(index) ? toArgb(args.checkLong(index)) : color;
    }

    /**
     * Widens a colour so Lua sees it the way it was written.
     * <p>
     * An opaque colour has its top bit set, which as a Java int is negative. Handing that to a
     * program would mean {@code canvas.get(x, y) == 0xFFFF0000} is false, because the program's
     * literal is the positive number four billion and the machine's answer is minus sixty five
     * thousand. Widening to a long keeps the two comparable.
     */
    private static long unsigned(final int argb) {
        return argb & 0xFFFFFFFFL;
    }

    /**
     * Reads a colour, treating a missing alpha as opaque.
     * <p>
     * Every OpenComputers program writes colours as 0xRRGGBB. Taking that literally would make the
     * alpha zero and the shape invisible, so a value that fits in twenty four bits is read as fully
     * opaque. A program that means to draw transparently says so by setting the alpha byte, and the
     * only value it cannot express is a fully transparent colour, which draws nothing.
     */
    private static int toArgb(final long value) {
        final int argb = (int) value;
        return (value & 0xFF000000L) == 0 ? argb | 0xFF000000 : argb;
    }

    /**
     * Reads a flat list of x, y pairs, converting from Lua's one based coordinates.
     */
    private static int[] points(final Map<?, ?> table) {
        final List<Integer> values = readNumbers(table, MAX_POLYGON_POINTS * 2);
        if (values.size() % 2 != 0) {
            throw new IllegalArgumentException("bad argument #1 (points must be x, y pairs)");
        }
        final int[] points = new int[values.size()];
        for (int i = 0; i < points.length; i++) {
            points[i] = values.get(i) - 1;
        }
        return points;
    }

    /**
     * Reads a flat list of colours, checking it makes whole rows of the given width.
     */
    private static int[] pixels(final Map<?, ?> table, final int width) {
        final List<Integer> values = readNumbers(table, MAX_BLIT_PIXELS);
        if (values.isEmpty() || values.size() % width != 0) {
            throw new IllegalArgumentException(
                "bad argument #4 (pixel count must be a whole number of rows of width " + width + ")");
        }
        final int[] pixels = new int[values.size()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = toArgb(values.get(i) & 0xFFFFFFFFL);
        }
        return pixels;
    }

    /**
     * Reads a Lua array of numbers.
     * <p>
     * Read by index rather than by iterating the map, because the order is the whole meaning here
     * and a table's iteration order is not the order its keys were written in.
     */
    private static List<Integer> readNumbers(final Map<?, ?> table, final int limit) {
        final List<Integer> values = new ArrayList<>();
        for (int index = 1; ; index++) {
            final Object value = table.get((double) index);
            if (!(value instanceof final Number number)) {
                break;
            }
            if (values.size() >= limit) {
                throw new IllegalArgumentException("too many values, at most " + limit + " allowed");
            }
            values.add((int) number.longValue());
        }
        return values;
    }
}
