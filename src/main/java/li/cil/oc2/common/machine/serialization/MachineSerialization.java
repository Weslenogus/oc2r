/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.serialization;

import li.cil.oc2.api.machine.Signal;
import li.cil.oc2.common.machine.components.DriveComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.TextBuffer;
import li.cil.oc2.common.machine.screen.TextBufferDelta;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the parts of the Lua runtime that have to survive a save.
 * <p>
 * The Lua state itself is not among them, and cannot be: LuaJ has no way to serialize a suspended
 * coroutine's stack. A machine therefore reboots when its chunk reloads, which is the same thing
 * OpenComputers 1 does when running on its LuaJ backend. What does have to persist is everything an
 * operating system would notice going missing: component addresses, because an installed OS records
 * them; the EEPROM's data area, which is where the BIOS wrote the disk it booted from; and the
 * screen contents, so a display does not go blank until its machine finishes coming back up.
 */
public final class MachineSerialization {
    private static final String ADDRESS_TAG_NAME = "address";
    private static final String UPTIME_TAG_NAME = "uptime";
    private static final String USERS_TAG_NAME = "users";
    private static final String SIGNALS_TAG_NAME = "signals";
    private static final String RUNNING_TAG_NAME = "running";

    private static final String NAME_TAG_NAME = "name";
    private static final String ARGS_TAG_NAME = "args";
    private static final String TYPE_TAG_NAME = "type";
    private static final String VALUE_TAG_NAME = "value";
    private static final String KEYS_TAG_NAME = "keys";
    private static final String VALUES_TAG_NAME = "values";

    private static final String CODE_TAG_NAME = "code";
    private static final String DATA_TAG_NAME = "data";
    private static final String LABEL_TAG_NAME = "label";
    private static final String READ_ONLY_TAG_NAME = "readOnly";

    private static final String BUFFER_TAG_NAME = "buffer";
    private static final String IS_ON_TAG_NAME = "isOn";

    // Discriminators for the signal argument union. Written as bytes rather than inferred from the
    // tag type, because a Lua string and a Lua byte string are both byte arrays here and only the
    // sender knows which one it meant.
    private static final byte TYPE_NIL = 0;
    private static final byte TYPE_BOOLEAN = 1;
    private static final byte TYPE_NUMBER = 2;
    private static final byte TYPE_STRING = 3;
    private static final byte TYPE_BYTES = 4;
    private static final byte TYPE_TABLE = 5;

    /**
     * How deep a table in a signal may nest. Signals come from mod code rather than from Lua, but a
     * malformed one must not be able to overflow the stack while a world is saving.
     */
    private static final int MAX_DEPTH = 8;

    private MachineSerialization() {
    }

    ///////////////////////////////////////////////////////////////////

    public static CompoundTag serialize(final LuaMachine machine) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(ADDRESS_TAG_NAME, machine.getAddress());
        tag.putLong(UPTIME_TAG_NAME, machine.getUptimeTicks());
        tag.putBoolean(RUNNING_TAG_NAME, machine.isRunning());

        final ListTag users = new ListTag();
        machine.getUsers().forEach(user -> users.add(net.minecraft.nbt.StringTag.valueOf(user)));
        tag.put(USERS_TAG_NAME, users);

        final ListTag signals = new ListTag();
        for (final Signal signal : machine.getPendingSignals()) {
            signals.add(serialize(signal));
        }
        tag.put(SIGNALS_TAG_NAME, signals);

        return tag;
    }

    /**
     * Restores a machine's bookkeeping.
     *
     * @return {@code true} if the machine was running when it was saved and should be started
     * again. Deliberately returned rather than acted on: whether a computer comes back up is the
     * host's decision, since it also knows whether it has the energy for it.
     */
    public static boolean deserialize(final CompoundTag tag, final LuaMachine machine) {
        machine.setUptimeTicks(tag.getLong(UPTIME_TAG_NAME));

        final ListTag users = tag.getList(USERS_TAG_NAME, NBTTagIds.TAG_STRING);
        for (int i = 0; i < users.size(); i++) {
            machine.addUser(users.getString(i));
        }

        final List<Signal> signals = new ArrayList<>();
        final ListTag signalTags = tag.getList(SIGNALS_TAG_NAME, NBTTagIds.TAG_COMPOUND);
        for (int i = 0; i < signalTags.size(); i++) {
            final Signal signal = deserializeSignal(signalTags.getCompound(i));
            if (signal != null) {
                signals.add(signal);
            }
        }
        machine.setPendingSignals(signals);

        return tag.getBoolean(RUNNING_TAG_NAME);
    }

    ///////////////////////////////////////////////////////////////////

    public static CompoundTag serialize(final Signal signal) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(NAME_TAG_NAME, signal.name());

        final ListTag args = new ListTag();
        for (final Object arg : signal.args()) {
            args.add(serializeValue(arg, 0));
        }
        tag.put(ARGS_TAG_NAME, args);

        return tag;
    }

    /**
     * @return the signal, or {@code null} if the tag could not be read back, in which case dropping
     * it is better than refusing to load the world.
     */
    @javax.annotation.Nullable
    public static Signal deserializeSignal(final CompoundTag tag) {
        final String name = tag.getString(NAME_TAG_NAME);
        if (name.isEmpty()) {
            return null;
        }

        final ListTag argTags = tag.getList(ARGS_TAG_NAME, NBTTagIds.TAG_COMPOUND);
        final Object[] args = new Object[argTags.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = deserializeValue(argTags.getCompound(i), 0);
        }

        try {
            return new Signal(name, args);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static CompoundTag serializeValue(final Object value, final int depth) {
        final CompoundTag tag = new CompoundTag();

        if (value == null || depth > MAX_DEPTH) {
            tag.putByte(TYPE_TAG_NAME, TYPE_NIL);
            return tag;
        }
        if (value instanceof final Boolean b) {
            tag.putByte(TYPE_TAG_NAME, TYPE_BOOLEAN);
            tag.putBoolean(VALUE_TAG_NAME, b);
            return tag;
        }
        if (value instanceof final Number n) {
            tag.putByte(TYPE_TAG_NAME, TYPE_NUMBER);
            tag.putDouble(VALUE_TAG_NAME, n.doubleValue());
            return tag;
        }
        if (value instanceof final String s) {
            tag.putByte(TYPE_TAG_NAME, TYPE_STRING);
            tag.putString(VALUE_TAG_NAME, s);
            return tag;
        }
        if (value instanceof final byte[] bytes) {
            tag.putByte(TYPE_TAG_NAME, TYPE_BYTES);
            tag.putByteArray(VALUE_TAG_NAME, bytes);
            return tag;
        }
        if (value instanceof final Map<?, ?> map) {
            // Keys and values as parallel lists rather than a compound, because a Lua table's keys
            // are not necessarily strings and a compound could not hold a numeric one.
            tag.putByte(TYPE_TAG_NAME, TYPE_TABLE);
            final ListTag keys = new ListTag();
            final ListTag values = new ListTag();
            map.forEach((key, item) -> {
                keys.add(serializeValue(key, depth + 1));
                values.add(serializeValue(item, depth + 1));
            });
            tag.put(KEYS_TAG_NAME, keys);
            tag.put(VALUES_TAG_NAME, values);
            return tag;
        }

        tag.putByte(TYPE_TAG_NAME, TYPE_NIL);
        return tag;
    }

    @javax.annotation.Nullable
    private static Object deserializeValue(final CompoundTag tag, final int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        return switch (tag.getByte(TYPE_TAG_NAME)) {
            case TYPE_BOOLEAN -> tag.getBoolean(VALUE_TAG_NAME);
            case TYPE_NUMBER -> tag.getDouble(VALUE_TAG_NAME);
            case TYPE_STRING -> tag.getString(VALUE_TAG_NAME);
            case TYPE_BYTES -> tag.getByteArray(VALUE_TAG_NAME);
            case TYPE_TABLE -> {
                final ListTag keys = tag.getList(KEYS_TAG_NAME, NBTTagIds.TAG_COMPOUND);
                final ListTag values = tag.getList(VALUES_TAG_NAME, NBTTagIds.TAG_COMPOUND);
                final Map<Object, Object> result = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
                    final Object key = deserializeValue(keys.getCompound(i), depth + 1);
                    if (key != null) {
                        result.put(key, deserializeValue(values.getCompound(i), depth + 1));
                    }
                }
                yield result;
            }
            default -> null;
        };
    }

    ///////////////////////////////////////////////////////////////////

    public static CompoundTag serialize(final EepromComponent eeprom) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(ADDRESS_TAG_NAME, eeprom.getComponentAddress());
        tag.putByteArray(CODE_TAG_NAME, eeprom.getCode());
        tag.putByteArray(DATA_TAG_NAME, eeprom.getData());
        tag.putString(LABEL_TAG_NAME, eeprom.getLabel());
        tag.putBoolean(READ_ONLY_TAG_NAME, eeprom.isReadOnly());
        return tag;
    }

    public static void deserialize(final CompoundTag tag, final EepromComponent eeprom) {
        eeprom.setComponentAddress(readAddress(tag));
        eeprom.setCode(tag.getByteArray(CODE_TAG_NAME));
        eeprom.setData(tag.getByteArray(DATA_TAG_NAME));
        if (tag.contains(LABEL_TAG_NAME, NBTTagIds.TAG_STRING)) {
            eeprom.setLabel(tag.getString(LABEL_TAG_NAME));
        }
        eeprom.setReadOnly(tag.getBoolean(READ_ONLY_TAG_NAME));
    }

    public static CompoundTag serialize(final DriveComponent drive) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(ADDRESS_TAG_NAME, drive.getComponentAddress());
        tag.putString(LABEL_TAG_NAME, drive.getLabel());
        tag.putByteArray(DATA_TAG_NAME, drive.getData());
        return tag;
    }

    public static void deserialize(final CompoundTag tag, final DriveComponent drive) {
        drive.setComponentAddress(readAddress(tag));
        if (tag.contains(LABEL_TAG_NAME, NBTTagIds.TAG_STRING)) {
            drive.setLabel(tag.getString(LABEL_TAG_NAME));
        }
        final byte[] stored = tag.getByteArray(DATA_TAG_NAME);
        final byte[] data = drive.getData();
        // Copy rather than swap: the drive's array is the storage its component hands out, and its
        // capacity is decided by the item, not by whatever happened to be saved.
        System.arraycopy(stored, 0, data, 0, Math.min(stored.length, data.length));
    }

    ///////////////////////////////////////////////////////////////////

    public static CompoundTag serialize(final ScreenComponent screen) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(ADDRESS_TAG_NAME, screen.getComponentAddress());
        tag.putBoolean(IS_ON_TAG_NAME, screen.isOn());

        synchronized (screen.getLock()) {
            final TextBuffer buffer = screen.getBuffer();
            // Reuse the delta encoding: forcing a full redraw makes it describe the whole buffer,
            // resolution, depth and palette included, in the same compact form used on the wire.
            // One encoding to get right rather than two.
            buffer.markAllDirty();
            tag.putByteArray(BUFFER_TAG_NAME, TextBufferDelta.encode(buffer));
        }

        return tag;
    }

    public static void deserialize(final CompoundTag tag, final ScreenComponent screen) {
        screen.setComponentAddress(readAddress(tag));
        if (tag.contains(IS_ON_TAG_NAME, NBTTagIds.TAG_BYTE)) {
            screen.setOn(tag.getBoolean(IS_ON_TAG_NAME));
        }
        if (!tag.contains(BUFFER_TAG_NAME, NBTTagIds.TAG_BYTE_ARRAY)) {
            return;
        }
        synchronized (screen.getLock()) {
            TextBufferDelta.apply(tag.getByteArray(BUFFER_TAG_NAME), screen.getBuffer());
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Reads a component address out of a tag, falling back to a fresh one when the tag holds none.
     * <p>
     * Addresses have to be stable, because an installed operating system writes them down: OpenOS
     * records the filesystem it booted from, and MineOS remembers which screen it was using. A
     * component that comes back with a new address every load looks to the machine like the old one
     * was pulled out and a different one plugged in.
     */
    public static String readAddress(final CompoundTag tag) {
        final String address = tag.getString(ADDRESS_TAG_NAME);
        return address.isEmpty() ? java.util.UUID.randomUUID().toString() : address;
    }

    /**
     * Whether a tag looks like it was written by one of the {@code serialize} methods here, used to
     * tell a fresh item from one that has been used before.
     */
    public static boolean hasAddress(final CompoundTag tag) {
        return tag.contains(ADDRESS_TAG_NAME, Tag.TAG_STRING);
    }
}
