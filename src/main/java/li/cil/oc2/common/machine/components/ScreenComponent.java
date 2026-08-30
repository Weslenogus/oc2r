/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.common.machine.screen.ColorDepth;
import li.cil.oc2.common.machine.screen.TextBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen, which is where the character buffer actually lives.
 * <p>
 * Graphics cards do not own a buffer; they bind to a screen and edit its one. That is what makes
 * {@code gpu.bind} meaningful, and it is why two cards pointed at the same screen see each other's
 * output.
 * <h2>Threading</h2>
 * The buffer is written from the machine thread, by direct {@code gpu} calls, and read from the
 * server thread when building the delta packets clients are sent. {@link TextBuffer} itself is not
 * thread safe, so every access goes through {@link #getLock()}. Locks are held for the length of
 * one {@code gpu} call, which is coarse enough to stay correct and fine grained enough that a
 * screen redraw does not serialize against the render loop for any meaningful time.
 */
public final class ScreenComponent extends AbstractLuaComponent {
    /**
     * Tier 3 dimensions, the resolution MineOS is designed around.
     */
    public static final int TIER_THREE_WIDTH = 160;
    public static final int TIER_THREE_HEIGHT = 50;

    private final TextBuffer buffer;
    private final Object lock = new Object();
    private final List<KeyboardComponent> keyboards = new ArrayList<>();

    private boolean isOn = true;
    private boolean isPrecise;
    private boolean isTouchModeInverted;

    ///////////////////////////////////////////////////////////////////

    public ScreenComponent(final String address) {
        this(address, TIER_THREE_WIDTH, TIER_THREE_HEIGHT, ColorDepth.EIGHT_BIT);
    }

    public ScreenComponent(final String address, final int maxWidth, final int maxHeight, final ColorDepth maxDepth) {
        super("screen", address);
        this.buffer = new TextBuffer(maxWidth, maxHeight, maxDepth);
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The character buffer. Callers must hold {@link #getLock()} for the whole of any read or
     * write sequence they want to see a consistent frame from.
     */
    public TextBuffer getBuffer() {
        return buffer;
    }

    /**
     * The monitor guarding {@link #getBuffer()}.
     */
    public Object getLock() {
        return lock;
    }

    public boolean isOn() {
        return isOn;
    }

    /**
     * Attaches a keyboard, which is what makes it show up in {@code screen.getKeyboards} and lets
     * an operating system decide where to send input.
     */
    public void addKeyboard(final KeyboardComponent keyboard) {
        if (!keyboards.contains(keyboard)) {
            keyboards.add(keyboard);
        }
    }

    public void removeKeyboard(final KeyboardComponent keyboard) {
        keyboards.remove(keyboard);
    }

    public List<KeyboardComponent> getKeyboards() {
        return keyboards;
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 64, doc = "function():boolean -- Whether the screen is currently on.")
    public Object[] isOn(final Context context, final Arguments args) {
        return new Object[]{isOn};
    }

    @Callback(doc = "function():boolean -- Turns the screen on. Returns true if it was off.")
    public Object[] turnOn(final Context context, final Arguments args) {
        final boolean changed = !isOn;
        isOn = true;
        if (changed) {
            synchronized (lock) {
                buffer.markAllDirty();
            }
        }
        return new Object[]{changed};
    }

    @Callback(doc = "function():boolean -- Turns the screen off. Returns true if it was on.")
    public Object[] turnOff(final Context context, final Arguments args) {
        final boolean changed = isOn;
        isOn = false;
        return new Object[]{changed};
    }

    @Callback(direct = true, limit = 64, doc = "function():number, number -- The physical size of the screen in blocks.")
    public Object[] getAspectRatio(final Context context, final Arguments args) {
        // Single block screens are the common case; multi block screens report their real extent
        // through the block entity that owns this component.
        return new Object[]{1, 1};
    }

    @Callback(direct = true, limit = 16, doc = "function():table -- The addresses of the keyboards attached to this screen.")
    public Object[] getKeyboards(final Context context, final Arguments args) {
        final List<String> addresses = new ArrayList<>(keyboards.size());
        for (final LuaComponent keyboard : keyboards) {
            addresses.add(keyboard.getComponentAddress());
        }
        return new Object[]{addresses};
    }

    @Callback(direct = true, limit = 64, doc = "function():boolean -- Whether touch events report sub-character precision.")
    public Object[] isPrecise(final Context context, final Arguments args) {
        return new Object[]{isPrecise};
    }

    @Callback(doc = "function(enabled:boolean):boolean -- Sets whether touch events report sub-character precision. Returns the old value.")
    public Object[] setPrecise(final Context context, final Arguments args) {
        final boolean previous = isPrecise;
        isPrecise = args.checkBoolean(0);
        return new Object[]{previous};
    }

    @Callback(direct = true, limit = 64, doc = "function():boolean -- Whether touch mode is inverted, requiring sneaking to interact.")
    public Object[] isTouchModeInverted(final Context context, final Arguments args) {
        return new Object[]{isTouchModeInverted};
    }

    @Callback(doc = "function(value:boolean):boolean -- Sets whether touch mode is inverted. Returns the old value.")
    public Object[] setTouchModeInverted(final Context context, final Arguments args) {
        final boolean previous = isTouchModeInverted;
        isTouchModeInverted = args.checkBoolean(0);
        return new Object[]{previous};
    }
}
