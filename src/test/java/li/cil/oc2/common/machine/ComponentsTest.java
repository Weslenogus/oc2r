/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.common.machine.bus.CallbackMethod;
import li.cil.oc2.common.machine.bus.Callbacks;
import li.cil.oc2.common.machine.components.DriveComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.input.KeyboardMap;
import li.cil.oc2.common.machine.lua.UnicodeSupport;
import li.cil.oc2.common.machine.net.InternetPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComponentsTest {
    @Test
    void unicodeWorksInCodePointsNotBytes() {
        // "héllo" is six bytes but five characters, which is the whole reason unicode exists
        // alongside string.
        assertEquals(5, UnicodeSupport.length("héllo"));
        assertEquals("hél", UnicodeSupport.sub("héllo", 1, 3));
        assertEquals("llo", UnicodeSupport.sub("héllo", -3, -1));
        assertEquals("", UnicodeSupport.sub("héllo", 4, 2));
        assertEquals("olléh", UnicodeSupport.reverse("héllo"));
    }

    @Test
    void unicodeMeasuresDisplayWidthForTheTerminalGrid() {
        assertEquals(2, UnicodeSupport.charWidth('中'));
        assertEquals(1, UnicodeSupport.charWidth('a'));
        assertTrue(UnicodeSupport.isWide("中文"));
        assertFalse(UnicodeSupport.isWide("ab"));
        assertEquals(4, UnicodeSupport.displayWidth("中文"));
        assertEquals(6, UnicodeSupport.displayWidth("中ab文"));
    }

    @Test
    void wtruncDropsAWideGlyphRatherThanHalfOfIt() {
        // Truncating between the halves of a double width character would leave the terminal
        // drawing something that occupies one column and claims two.
        assertEquals("a", UnicodeSupport.truncateToWidth("a中b", 3));
        assertEquals("a中", UnicodeSupport.truncateToWidth("a中b", 4));
        assertEquals("", UnicodeSupport.truncateToWidth("abc", 1));
    }

    @Test
    void keyboardMapTranslatesGlfwToTheCodesProgramsExpect() {
        // OpenOS's keyboard.keys table is written in LWJGL 2 scan codes, so these are the values
        // a program compares against. Getting them wrong leaves arrow keys silently dead.
        assertEquals(0x1C, KeyboardMap.toLegacyKeyCode(257), "enter");
        assertEquals(0x0E, KeyboardMap.toLegacyKeyCode(259), "backspace");
        assertEquals(0x01, KeyboardMap.toLegacyKeyCode(256), "escape");
        assertEquals(0xC8, KeyboardMap.toLegacyKeyCode(265), "up");
        assertEquals(0xD0, KeyboardMap.toLegacyKeyCode(264), "down");
        assertEquals(0xCB, KeyboardMap.toLegacyKeyCode(263), "left");
        assertEquals(0xCD, KeyboardMap.toLegacyKeyCode(262), "right");
        assertEquals(0x1D, KeyboardMap.toLegacyKeyCode(341), "left control");
        assertEquals(0x2A, KeyboardMap.toLegacyKeyCode(340), "left shift");
        assertEquals(0x39, KeyboardMap.toLegacyKeyCode(32), "space");
    }

    @Test
    void keyboardMapFollowsThePhysicalLayoutForLettersAndDigits() {
        assertEquals(0x1E, KeyboardMap.toLegacyKeyCode(65), "a");
        assertEquals(0x10, KeyboardMap.toLegacyKeyCode(81), "q");
        assertEquals(0x2C, KeyboardMap.toLegacyKeyCode(90), "z");
        assertEquals(0x02, KeyboardMap.toLegacyKeyCode(49), "1");
        assertEquals(0x0B, KeyboardMap.toLegacyKeyCode(48), "0");
        assertEquals(0x3B, KeyboardMap.toLegacyKeyCode(290), "f1");
        assertEquals(0x44, KeyboardMap.toLegacyKeyCode(299), "f10");
        assertEquals(0x57, KeyboardMap.toLegacyKeyCode(300), "f11");
        assertEquals(0x58, KeyboardMap.toLegacyKeyCode(301), "f12");
        assertEquals(0, KeyboardMap.toLegacyKeyCode(999), "an unknown key maps to nothing");
    }

    @Test
    void keyboardMapKnowsWhichKeysCarryACharacter() {
        assertTrue(KeyboardMap.isPrintable('a'));
        assertTrue(KeyboardMap.isPrintable('中'));
        assertFalse(KeyboardMap.isPrintable('\n'));
        assertFalse(KeyboardMap.isPrintable(0));
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void eepromChecksumChangesWithTheCode() throws Throwable {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
        eeprom.setCode("print('a')");
        final String first = (String) call(eeprom, "getChecksum")[0];

        eeprom.setCode("print('b')");
        final String second = (String) call(eeprom, "getChecksum")[0];

        assertEquals(8, first.length(), "a checksum is eight hex digits");
        assertFalse(first.equals(second));
    }

    @Test
    void eepromRefusesCodeThatWouldNotFit() throws Throwable {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString(), 16, 8);
        final Object[] result = call(eeprom, "set", "x".repeat(64));
        assertNull(result[0]);
        assertEquals("not enough space", result[1]);
    }

    @Test
    void makeReadonlyRequiresTheCurrentChecksum() throws Throwable {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
        eeprom.setCode("return 1");

        assertNull(call(eeprom, "makeReadonly", "deadbeef")[0], "a wrong checksum must not lock it");
        assertFalse(eeprom.isReadOnly());

        final String checksum = (String) call(eeprom, "getChecksum")[0];
        assertEquals(true, call(eeprom, "makeReadonly", checksum)[0]);
        assertTrue(eeprom.isReadOnly());

        assertNull(call(eeprom, "set", "return 2")[0], "a locked EEPROM must refuse new code");
        // The data area stays writable: that is where the BIOS records its boot device, and a
        // locked BIOS still has to be able to remember which disk it came up on. A callback that
        // succeeds with nothing to say returns no values at all, hence the empty result.
        assertEquals(0, call(eeprom, "setData", "some-address").length);
        assertBytesEqual("some-address", eeprom.getData());
    }

    @Test
    void driveReadsAndWritesSectorsOneBased() throws Throwable {
        final DriveComponent drive = new DriveComponent(
            UUID.randomUUID().toString(), new byte[DriveComponent.SECTOR_SIZE * 4], 1, "disk");

        call(drive, "writeSector", 1, "hello");
        final byte[] sector = (byte[]) call(drive, "readSector", 1)[0];
        assertEquals(DriveComponent.SECTOR_SIZE, sector.length);
        assertEquals('h', sector[0]);
        // A short write pads rather than leaving the previous contents exposed.
        assertEquals(0, sector[5]);

        // Sector two must be untouched, which is what proves the one based conversion.
        final byte[] second = (byte[]) call(drive, "readSector", 2)[0];
        assertEquals(0, second[0]);

        assertEquals((int) 'h', call(drive, "readByte", 1)[0]);
        assertThrows(IllegalArgumentException.class, () -> call(drive, "readByte", 0));
        assertThrows(IllegalArgumentException.class, () -> call(drive, "readSector", 5));
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void internetPolicyBlocksTheServerOwnNetwork() {
        final InternetPolicy policy = InternetPolicy.allowPublic(true, true, java.util.List.of());
        // Otherwise a player could use the server as a proxy into places they cannot reach.
        assertNotNull(policy.checkAllowed("127.0.0.1", 80));
        assertNotNull(policy.checkAllowed("localhost", 80));
        assertNotNull(policy.checkAllowed("192.168.1.1", 80));
        assertNotNull(policy.checkAllowed("10.0.0.1", 8080));
        assertNotNull(policy.checkAllowed("", 80));
    }

    @Test
    void internetPolicyHonoursAHostAllowList() {
        // Literal addresses throughout, so the test asserts the policy rather than whatever DNS
        // happens to answer on the machine running it.
        final InternetPolicy policy = InternetPolicy.allowPublic(true, false,
            java.util.List.of("8.8.8.8", "example.com"));
        assertNull(policy.checkAllowed("8.8.8.8", 443));
        assertNotNull(policy.checkAllowed("example.com.evil.test", 443));
        assertNotNull(policy.checkAllowed("other.test", 443));
        assertFalse(policy.isTcpEnabled());
    }

    @Test
    void internetPolicyAllowsAPublicAddressWhenNothingNarrowsIt() {
        final InternetPolicy policy = InternetPolicy.allowPublic(true, true, java.util.List.of());
        assertNull(policy.checkAllowed("8.8.8.8", 53));
        assertTrue(policy.isHttpEnabled());
        assertTrue(policy.isTcpEnabled());
    }

    @Test
    void denyAllRefusesEverything() {
        assertFalse(InternetPolicy.DENY_ALL.isHttpEnabled());
        assertFalse(InternetPolicy.DENY_ALL.isTcpEnabled());
        assertNotNull(InternetPolicy.DENY_ALL.checkAllowed("example.com", 443));
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void callbackScanCollectsAnnotatedMethodsWithTheirMetadata() {
        final Map<String, CallbackMethod> methods = Callbacks.collect(new Annotated());
        assertEquals(2, methods.size());

        final CallbackMethod direct = methods.get("fast");
        assertNotNull(direct);
        assertTrue(direct.isDirect());
        assertEquals(7, direct.getLimit());
        assertEquals("does a fast thing", direct.getDoc());

        final CallbackMethod renamed = methods.get("renamed");
        assertNotNull(renamed);
        assertFalse(renamed.isDirect());
        // Without an explicit doc string, the name is echoed back rather than nothing.
        assertEquals("renamed(...)", renamed.getDoc());
    }

    @Test
    void callbackScanRejectsAMethodItCouldNeverCall() {
        assertThrows(IllegalArgumentException.class, () -> Callbacks.collect(BadSignature.class));
        assertThrows(IllegalArgumentException.class, () -> Callbacks.collect(DuplicateName.class));
    }

    ///////////////////////////////////////////////////////////////////

    private static Object[] call(final Object target, final String method, final Object... args)
        throws Throwable {
        final CallbackMethod callback = Callbacks.collect(target).get(method);
        assertNotNull(callback, "no such callback: " + method);
        return callback.invoke(target, null, new FixedArguments(args));
    }

    private static void assertBytesEqual(final String expected, final byte[] actual) {
        assertEquals(expected, new String(actual, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static final class Annotated {
        @Callback(direct = true, limit = 7, doc = "does a fast thing")
        public Object[] fast(final Context context, final Arguments args) {
            return null;
        }

        @Callback(name = "renamed")
        public Object[] slow(final Context context, final Arguments args) {
            return null;
        }
    }

    public static final class BadSignature {
        @Callback
        public Object[] wrong(final String notAContext) {
            return null;
        }
    }

    public static final class DuplicateName {
        @Callback(name = "same")
        public Object[] a(final Context context, final Arguments args) {
            return null;
        }

        @Callback(name = "same")
        public Object[] b(final Context context, final Arguments args) {
            return null;
        }
    }

    /**
     * An {@link Arguments} over plain Java values, so component callbacks can be exercised without
     * standing up a Lua state.
     */
    private record FixedArguments(Object[] values) implements Arguments {
        @Override
        public int count() {
            return values.length;
        }

        @Override
        public Object get(final int index) {
            return index >= 0 && index < values.length ? values[index] : null;
        }

        @Override
        public Object[] toArray() {
            return values.clone();
        }

        @Override
        public boolean isDefined(final int index) {
            return get(index) != null;
        }

        @Override
        public boolean isBoolean(final int index) {
            return get(index) instanceof Boolean;
        }

        @Override
        public boolean isNumber(final int index) {
            return get(index) instanceof Number;
        }

        @Override
        public boolean isString(final int index) {
            return get(index) instanceof String || get(index) instanceof byte[];
        }

        @Override
        public boolean isTable(final int index) {
            return get(index) instanceof Map;
        }

        @Override
        public Object checkAny(final int index) {
            final Object value = get(index);
            if (value == null) {
                throw new IllegalArgumentException("bad argument #" + (index + 1));
            }
            return value;
        }

        @Override
        public boolean checkBoolean(final int index) {
            return (Boolean) checkAny(index);
        }

        @Override
        public double checkDouble(final int index) {
            return ((Number) checkAny(index)).doubleValue();
        }

        @Override
        public int checkInteger(final int index) {
            return ((Number) checkAny(index)).intValue();
        }

        @Override
        public long checkLong(final int index) {
            return ((Number) checkAny(index)).longValue();
        }

        @Override
        public String checkString(final int index) {
            final Object value = checkAny(index);
            return value instanceof final byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : (String) value;
        }

        @Override
        public byte[] checkByteArray(final int index) {
            final Object value = checkAny(index);
            return value instanceof final byte[] bytes
                ? bytes
                : ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public Map<?, ?> checkTable(final int index) {
            return (Map<?, ?>) checkAny(index);
        }

        @Override
        public boolean optBoolean(final int index, final boolean defaultValue) {
            return isDefined(index) ? checkBoolean(index) : defaultValue;
        }

        @Override
        public double optDouble(final int index, final double defaultValue) {
            return isDefined(index) ? checkDouble(index) : defaultValue;
        }

        @Override
        public int optInteger(final int index, final int defaultValue) {
            return isDefined(index) ? checkInteger(index) : defaultValue;
        }

        @Override
        public long optLong(final int index, final long defaultValue) {
            return isDefined(index) ? checkLong(index) : defaultValue;
        }

        @Override
        public String optString(final int index, final String defaultValue) {
            return isDefined(index) ? checkString(index) : defaultValue;
        }

        @Override
        public byte[] optByteArray(final int index, final byte[] defaultValue) {
            return isDefined(index) ? checkByteArray(index) : defaultValue;
        }

        @Override
        public Map<?, ?> optTable(final int index, final Map<?, ?> defaultValue) {
            return isDefined(index) ? checkTable(index) : defaultValue;
        }
    }
}
