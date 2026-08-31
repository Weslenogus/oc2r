/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Picks the Lua implementation a machine runs on.
 * <p>
 * There are two, and the difference matters to programs. {@link NativeLuaArchitecture} is real Lua
 * 5.3 through JNI and is what an operating system such as MineOS needs, because a pure Java Lua
 * cannot compile several of its libraries. {@link LuaJArchitecture} is Lua 5.2 in Java, needs
 * nothing from the platform, and is what a machine falls back to where the natives will not load.
 * <p>
 * The choice is made once and cached, because probing it means building and tearing down a Lua
 * state, and it cannot change while the game is running.
 */
public final class LuaArchitectures {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Forces a backend, for working out whether a problem is the machine's or the platform's.
     * Accepts {@code native} or {@code luaj}; anything else is ignored.
     */
    public static final String OVERRIDE_PROPERTY = "oc2r.lua.architecture";

    @Nullable private static volatile Function<LuaMachine, LuaArchitecture> preferred;

    private LuaArchitectures() {
    }

    /**
     * The factory a machine should be built with, native where it works and Java where it does not.
     */
    public static Function<LuaMachine, LuaArchitecture> preferred() {
        Function<LuaMachine, LuaArchitecture> result = preferred;
        if (result == null) {
            synchronized (LuaArchitectures.class) {
                result = preferred;
                if (result == null) {
                    result = select();
                    preferred = result;
                }
            }
        }
        return result;
    }

    private static Function<LuaMachine, LuaArchitecture> select() {
        final String override = System.getProperty(OVERRIDE_PROPERTY, "").trim();
        if ("luaj".equalsIgnoreCase(override)) {
            LOGGER.info("Lua machines will run on {} ({} is set).",
                LuaJArchitecture.ARCHITECTURE_NAME, OVERRIDE_PROPERTY);
            return LuaJArchitecture::new;
        }

        try {
            if (NativeLuaArchitecture.isAvailable()) {
                LOGGER.info("Lua machines will run on native {}.",
                    NativeLuaArchitecture.ARCHITECTURE_NAME);
                return NativeLuaArchitecture::new;
            }
        } catch (final Throwable e) {
            // Also catches the binding's classes being missing outright, which is what would
            // happen if the jar were repackaged without them.
            LOGGER.warn("Failed probing for native Lua.", e);
        }

        if ("native".equalsIgnoreCase(override)) {
            LOGGER.error("{} asks for native Lua, but it is not available on this platform.",
                OVERRIDE_PROPERTY);
        }

        LOGGER.warn("Native Lua is unavailable; Lua machines will run on {}, which cannot load "
            + "every operating system.", LuaJArchitecture.ARCHITECTURE_NAME);
        return LuaJArchitecture::new;
    }
}
