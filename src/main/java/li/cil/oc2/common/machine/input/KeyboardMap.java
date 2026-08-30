/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.input;

import java.util.HashMap;
import java.util.Map;

/**
 * Translation from the GLFW key codes Minecraft 1.20.1 reports into the codes an OpenComputers 1
 * program expects in a {@code key_down} signal.
 * <p>
 * This is not cosmetic. OpenOS's {@code keyboard.keys} table, and everything built on it, is
 * written in terms of LWJGL 2 key codes, which are the old DirectInput scan codes: enter is
 * {@code 0x1C}, up is {@code 0xC8}. Minecraft moved to GLFW in 1.13, where enter is {@code 257}.
 * Passing GLFW codes straight through would leave every program's key handling silently broken,
 * with arrow keys and modifiers doing nothing while ordinary letters still worked, which is a
 * genuinely confusing way to fail.
 * <p>
 * Character input is separate: the {@code char} field of the signal carries the code point the
 * player actually typed, so a keyboard layout that produces {@code é} sends that, while the key
 * code still describes the physical key.
 */
public final class KeyboardMap {
    // GLFW key codes, spelled out rather than pulled from org.lwjgl.glfw.GLFW so this class stays
    // usable, and testable, without a client on the class path.
    private static final int GLFW_SPACE = 32;
    private static final int GLFW_APOSTROPHE = 39;
    private static final int GLFW_COMMA = 44;
    private static final int GLFW_MINUS = 45;
    private static final int GLFW_PERIOD = 46;
    private static final int GLFW_SLASH = 47;
    private static final int GLFW_0 = 48;
    private static final int GLFW_SEMICOLON = 59;
    private static final int GLFW_EQUAL = 61;
    private static final int GLFW_A = 65;
    private static final int GLFW_LEFT_BRACKET = 91;
    private static final int GLFW_BACKSLASH = 92;
    private static final int GLFW_RIGHT_BRACKET = 93;
    private static final int GLFW_GRAVE_ACCENT = 96;
    private static final int GLFW_ESCAPE = 256;
    private static final int GLFW_ENTER = 257;
    private static final int GLFW_TAB = 258;
    private static final int GLFW_BACKSPACE = 259;
    private static final int GLFW_INSERT = 260;
    private static final int GLFW_DELETE = 261;
    private static final int GLFW_RIGHT = 262;
    private static final int GLFW_LEFT = 263;
    private static final int GLFW_DOWN = 264;
    private static final int GLFW_UP = 265;
    private static final int GLFW_PAGE_UP = 266;
    private static final int GLFW_PAGE_DOWN = 267;
    private static final int GLFW_HOME = 268;
    private static final int GLFW_END = 269;
    private static final int GLFW_CAPS_LOCK = 280;
    private static final int GLFW_SCROLL_LOCK = 281;
    private static final int GLFW_NUM_LOCK = 282;
    private static final int GLFW_PAUSE = 284;
    private static final int GLFW_F1 = 290;
    private static final int GLFW_KP_0 = 320;
    private static final int GLFW_KP_DECIMAL = 330;
    private static final int GLFW_KP_DIVIDE = 331;
    private static final int GLFW_KP_MULTIPLY = 332;
    private static final int GLFW_KP_SUBTRACT = 333;
    private static final int GLFW_KP_ADD = 334;
    private static final int GLFW_KP_ENTER = 335;
    private static final int GLFW_KP_EQUAL = 336;
    private static final int GLFW_LEFT_SHIFT = 340;
    private static final int GLFW_LEFT_CONTROL = 341;
    private static final int GLFW_LEFT_ALT = 342;
    private static final int GLFW_RIGHT_SHIFT = 344;
    private static final int GLFW_RIGHT_CONTROL = 345;
    private static final int GLFW_RIGHT_ALT = 346;
    private static final int GLFW_MENU = 348;

    /**
     * LWJGL 2 codes, in the order the top letter row sits on a keyboard rather than alphabetically,
     * because the scan codes follow the physical layout.
     */
    private static final int[] LETTER_CODES = {
        0x1E, // a
        0x30, // b
        0x2E, // c
        0x20, // d
        0x12, // e
        0x21, // f
        0x22, // g
        0x23, // h
        0x17, // i
        0x24, // j
        0x25, // k
        0x26, // l
        0x32, // m
        0x31, // n
        0x18, // o
        0x19, // p
        0x10, // q
        0x13, // r
        0x1F, // s
        0x14, // t
        0x16, // u
        0x2F, // v
        0x11, // w
        0x2D, // x
        0x15, // y
        0x2C, // z
    };

    /**
     * Digits, in the order 1 through 9 then 0, which is how they sit on the number row and how the
     * scan codes are assigned.
     */
    private static final int[] DIGIT_CODES = {
        0x0B, // 0
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, // 1 through 9
    };

    private static final int[] KEYPAD_CODES = {
        0x52, // 0
        0x4F, 0x50, 0x51, // 1 through 3
        0x4B, 0x4C, 0x4D, // 4 through 6
        0x47, 0x48, 0x49, // 7 through 9
    };

    private static final Map<Integer, Integer> SPECIAL_KEYS = new HashMap<>();

    static {
        SPECIAL_KEYS.put(GLFW_SPACE, 0x39);
        SPECIAL_KEYS.put(GLFW_APOSTROPHE, 0x28);
        SPECIAL_KEYS.put(GLFW_COMMA, 0x33);
        SPECIAL_KEYS.put(GLFW_MINUS, 0x0C);
        SPECIAL_KEYS.put(GLFW_PERIOD, 0x34);
        SPECIAL_KEYS.put(GLFW_SLASH, 0x35);
        SPECIAL_KEYS.put(GLFW_SEMICOLON, 0x27);
        SPECIAL_KEYS.put(GLFW_EQUAL, 0x0D);
        SPECIAL_KEYS.put(GLFW_LEFT_BRACKET, 0x1A);
        SPECIAL_KEYS.put(GLFW_BACKSLASH, 0x2B);
        SPECIAL_KEYS.put(GLFW_RIGHT_BRACKET, 0x1B);
        SPECIAL_KEYS.put(GLFW_GRAVE_ACCENT, 0x29);

        SPECIAL_KEYS.put(GLFW_ESCAPE, 0x01);
        SPECIAL_KEYS.put(GLFW_ENTER, 0x1C);
        SPECIAL_KEYS.put(GLFW_TAB, 0x0F);
        SPECIAL_KEYS.put(GLFW_BACKSPACE, 0x0E);
        SPECIAL_KEYS.put(GLFW_INSERT, 0xD2);
        SPECIAL_KEYS.put(GLFW_DELETE, 0xD3);

        SPECIAL_KEYS.put(GLFW_RIGHT, 0xCD);
        SPECIAL_KEYS.put(GLFW_LEFT, 0xCB);
        SPECIAL_KEYS.put(GLFW_DOWN, 0xD0);
        SPECIAL_KEYS.put(GLFW_UP, 0xC8);
        SPECIAL_KEYS.put(GLFW_PAGE_UP, 0xC9);
        SPECIAL_KEYS.put(GLFW_PAGE_DOWN, 0xD1);
        SPECIAL_KEYS.put(GLFW_HOME, 0xC7);
        SPECIAL_KEYS.put(GLFW_END, 0xCF);

        SPECIAL_KEYS.put(GLFW_CAPS_LOCK, 0x3A);
        SPECIAL_KEYS.put(GLFW_SCROLL_LOCK, 0x46);
        SPECIAL_KEYS.put(GLFW_NUM_LOCK, 0x45);
        SPECIAL_KEYS.put(GLFW_PAUSE, 0xC5);

        SPECIAL_KEYS.put(GLFW_KP_DECIMAL, 0x53);
        SPECIAL_KEYS.put(GLFW_KP_DIVIDE, 0xB5);
        SPECIAL_KEYS.put(GLFW_KP_MULTIPLY, 0x37);
        SPECIAL_KEYS.put(GLFW_KP_SUBTRACT, 0x4A);
        SPECIAL_KEYS.put(GLFW_KP_ADD, 0x4E);
        SPECIAL_KEYS.put(GLFW_KP_ENTER, 0x9C);
        SPECIAL_KEYS.put(GLFW_KP_EQUAL, 0x8D);

        SPECIAL_KEYS.put(GLFW_LEFT_SHIFT, 0x2A);
        SPECIAL_KEYS.put(GLFW_LEFT_CONTROL, 0x1D);
        SPECIAL_KEYS.put(GLFW_LEFT_ALT, 0x38);
        SPECIAL_KEYS.put(GLFW_RIGHT_SHIFT, 0x36);
        SPECIAL_KEYS.put(GLFW_RIGHT_CONTROL, 0x9D);
        SPECIAL_KEYS.put(GLFW_RIGHT_ALT, 0xB8);
        SPECIAL_KEYS.put(GLFW_MENU, 0xDD);
    }

    private KeyboardMap() {
    }

    /**
     * Translates a GLFW key code.
     *
     * @param glfwKey the key code Minecraft reported.
     * @return the OpenComputers 1 key code, or {@code 0} for a key with no equivalent.
     */
    public static int toLegacyKeyCode(final int glfwKey) {
        if (glfwKey >= GLFW_A && glfwKey < GLFW_A + LETTER_CODES.length) {
            return LETTER_CODES[glfwKey - GLFW_A];
        }
        if (glfwKey >= GLFW_0 && glfwKey < GLFW_0 + DIGIT_CODES.length) {
            return DIGIT_CODES[glfwKey - GLFW_0];
        }
        if (glfwKey >= GLFW_KP_0 && glfwKey < GLFW_KP_0 + KEYPAD_CODES.length) {
            return KEYPAD_CODES[glfwKey - GLFW_KP_0];
        }
        if (glfwKey >= GLFW_F1 && glfwKey <= GLFW_F1 + 9) {
            // F1 through F10 are contiguous at 0x3B.
            return 0x3B + (glfwKey - GLFW_F1);
        }
        if (glfwKey == GLFW_F1 + 10) {
            // F11 and F12 were added later and sit apart from the rest.
            return 0x57;
        }
        if (glfwKey == GLFW_F1 + 11) {
            return 0x58;
        }

        return SPECIAL_KEYS.getOrDefault(glfwKey, 0);
    }

    /**
     * Whether a key code is one a program would expect to see a character for as well. Used to
     * decide whether a {@code key_down} signal carries a code point.
     */
    public static boolean isPrintable(final int codePoint) {
        return codePoint > 0 && !Character.isISOControl(codePoint);
    }
}
