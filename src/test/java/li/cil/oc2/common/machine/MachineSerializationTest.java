/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.api.machine.Signal;
import li.cil.oc2.common.machine.components.DriveComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MachineSerializationTest {
    @Test
    void roundTripsAMachinesQueuedSignals() {
        final TestMachineHost host = new TestMachineHost();
        final LuaMachine machine = new LuaMachine(host, "address-under-test");
        machine.start();

        final Map<String, Object> table = new LinkedHashMap<>();
        table.put("nested", 1.5);
        table.put("flag", true);

        machine.signal("touch", "screen-address", 12.0, 7.0, 0.0, "Steve");
        machine.signal("modem_message", "modem", "sender", 3.0, table);
        machine.signal("binary", (Object) "raw".getBytes(StandardCharsets.UTF_8));
        machine.addUser("Steve");

        final CompoundTag tag = MachineSerialization.serialize(machine);

        final LuaMachine restored = new LuaMachine(new TestMachineHost(), "address-under-test");
        assertTrue(MachineSerialization.deserialize(tag, restored),
            "a machine that was running should ask to be started again");

        final List<Signal> signals = restored.getPendingSignals();
        assertEquals(3, signals.size());

        assertEquals("touch", signals.get(0).name());
        assertArrayEquals(new Object[]{"screen-address", 12.0, 7.0, 0.0, "Steve"}, signals.get(0).args());

        // Numbers all come back as doubles, because Lua has a single number type and pretending
        // otherwise would make a restored signal differ from a fresh one.
        final Object[] modemArgs = signals.get(1).args();
        assertEquals(3.0, modemArgs[2]);
        assertTrue(modemArgs[3] instanceof Map);
        assertEquals(1.5, ((Map<?, ?>) modemArgs[3]).get("nested"));
        assertEquals(true, ((Map<?, ?>) modemArgs[3]).get("flag"));

        // Byte strings must not be decoded on the way through, or every binary payload a program
        // queued would come back mangled.
        assertTrue(signals.get(2).args()[0] instanceof byte[]);
        assertArrayEquals("raw".getBytes(StandardCharsets.UTF_8), (byte[]) signals.get(2).args()[0]);

        assertTrue(restored.getUsers().contains("Steve"));
    }

    @Test
    void recordsWhetherTheMachineWasRunning() {
        final LuaMachine stopped = new LuaMachine(new TestMachineHost(), UUID.randomUUID().toString());
        assertFalse(MachineSerialization.deserialize(
            MachineSerialization.serialize(stopped), new LuaMachine(new TestMachineHost())));
    }

    @Test
    void roundTripsAnEeprom() {
        final EepromComponent eeprom = new EepromComponent("eeprom-address");
        eeprom.setCode("return 'bios'");
        eeprom.setData("boot-device-address".getBytes(StandardCharsets.UTF_8));
        eeprom.setLabel("BIOS");
        eeprom.setReadOnly(true);

        final CompoundTag tag = MachineSerialization.serialize(eeprom);
        assertTrue(MachineSerialization.hasAddress(tag));
        assertEquals("eeprom-address", MachineSerialization.readAddress(tag));

        final EepromComponent restored = new EepromComponent(MachineSerialization.readAddress(tag));
        MachineSerialization.deserialize(tag, restored);

        assertEquals("return 'bios'", new String(restored.getCode(), StandardCharsets.UTF_8));
        assertEquals("boot-device-address", new String(restored.getData(), StandardCharsets.UTF_8));
        assertEquals("BIOS", restored.getLabel());
        assertTrue(restored.isReadOnly());
    }

    @Test
    void mintsAnAddressOnlyWhenTheTagHasNone() {
        final CompoundTag empty = new CompoundTag();
        assertFalse(MachineSerialization.hasAddress(empty));

        final String first = MachineSerialization.readAddress(empty);
        assertNotNull(UUID.fromString(first), "a minted address must be a UUID");
        // A fresh one each time, since there was nothing to be stable about.
        assertFalse(first.equals(MachineSerialization.readAddress(empty)));
    }

    @Test
    void roundTripsADrive() {
        final byte[] data = new byte[DriveComponent.SECTOR_SIZE * 2];
        data[0] = 42;
        data[data.length - 1] = -7;

        final DriveComponent drive = new DriveComponent("drive-address", data, 1, "disk");
        final CompoundTag tag = MachineSerialization.serialize(drive);

        final DriveComponent restored = new DriveComponent(
            "drive-address", new byte[data.length], 1, "");
        MachineSerialization.deserialize(tag, restored);

        assertEquals("disk", restored.getLabel());
        assertArrayEquals(data, restored.getData());
    }

    @Test
    void roundTripsAScreenIncludingItsPalette() {
        final ScreenComponent screen = new ScreenComponent("screen-address");
        synchronized (screen.getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            buffer.setPaletteColor(4, 0xABCDEF);
            buffer.setForeground(4, true);
            buffer.set(2, 3, "saved text", false);
        }

        final CompoundTag tag = MachineSerialization.serialize(screen);

        final ScreenComponent restored = new ScreenComponent("screen-address");
        MachineSerialization.deserialize(tag, restored);

        final TextBuffer buffer = restored.getBuffer();
        final StringBuilder row = new StringBuilder();
        for (int x = 2; x < 12; x++) {
            row.append(buffer.getRawChar(x, 3));
        }
        assertEquals("saved text", row.toString());
        assertEquals(0xABCDEF, buffer.getPaletteColor(4));
        assertEquals(4, buffer.getForegroundPaletteIndexAt(2, 3));
    }
}
