/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import javax.annotation.Nullable;

/**
 * The {@code keyboard} component.
 * <p>
 * It has no callbacks, and that is not an omission: in OpenComputers 1 a keyboard exists so that
 * {@code screen.getKeyboards} can name it and so that {@code key_down} and {@code key_up} signals
 * have an address to be attributed to. Input itself flows in as signals from the player
 * interacting with the block, never as a call from Lua.
 */
public final class KeyboardComponent extends AbstractLuaComponent {
    @Nullable private ScreenComponent screen;

    public KeyboardComponent(final String address) {
        super("keyboard", address);
    }

    /**
     * The screen this keyboard is attached to, which decides where its signals are aimed.
     */
    @Nullable
    public ScreenComponent getScreen() {
        return screen;
    }

    public void setScreen(@Nullable final ScreenComponent value) {
        if (screen != null) {
            screen.removeKeyboard(this);
        }
        screen = value;
        if (value != null) {
            value.addKeyboard(this);
        }
    }
}
