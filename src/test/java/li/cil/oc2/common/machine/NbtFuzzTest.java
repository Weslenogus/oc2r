/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.DriveComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.serialization.FileSystemSerialization;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

/**
 * A region file can be hand edited, and a mod that trusts what it reads back out of one will
 * happily be told to do something it should not. Everything that reads a tag is fed nonsense here.
 */
public class NbtFuzzTest {
    private static final int ROUNDS = 20_000;

    @Test
    void deserializersSurviveArbitraryTags() {
        final Random random = new Random(31337);
        for (int i = 0; i < ROUNDS; i++) {
            final CompoundTag tag = randomTag(random, 0);
            // None of these may throw. A tag that makes no sense should leave the component at a
            // usable default, not take the chunk load down with it.
            MachineSerialization.deserialize(tag, new LuaMachine(new TestMachineHost()));
            MachineSerialization.deserialize(tag, new EepromComponent(UUID.randomUUID().toString()));
            MachineSerialization.deserialize(tag, new ScreenComponent(UUID.randomUUID().toString()));
            MachineSerialization.deserialize(tag,
                new DriveComponent(UUID.randomUUID().toString(), new byte[512], 1, ""));
            FileSystemSerialization.deserialize(tag, new RamFileSystem(1 << 16));
            MachineSerialization.hasAddress(tag);
            MachineSerialization.readAddress(tag);
        }
    }

    @Test
    void aHandEditedFileSystemCannotEscapeItsRoot() {
        // The specific thing an attacker would write into a region file by hand.
        final String[] escapes = {
            "../../server.properties", "/etc/passwd", "..", "a/../../x",
            "C:/windows/system32/config/sam", "\\..\\..\\x",
        };
        for (final String path : escapes) {
            final CompoundTag tag = new CompoundTag();
            final ListTag entries = new ListTag();
            final CompoundTag entry = new CompoundTag();
            entry.putString("path", path);
            entry.putByteArray("data", new byte[]{1, 2, 3});
            entries.add(entry);
            tag.put("entries", entries);

            final RamFileSystem fs = new RamFileSystem(1 << 16);
            FileSystemSerialization.deserialize(tag, fs);

            // Either refused outright, or re-rooted to something inside. What must never happen is
            // an entry that still carries a climb, so walk what was actually restored and check.
            for (final String restored : listAll(fs, "")) {
                org.junit.jupiter.api.Assertions.assertFalse(restored.contains(".."),
                    "restored \"" + path + "\" as \"" + restored + "\", which still climbs");
            }
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static java.util.List<String> listAll(final RamFileSystem fs, final String directory) {
        final java.util.List<String> result = new java.util.ArrayList<>();
        final String[] names = fs.list(directory);
        if (names == null) {
            return result;
        }
        for (final String name : names) {
            final boolean isDirectory = name.endsWith("/");
            final String stripped = isDirectory ? name.substring(0, name.length() - 1) : name;
            final String child = directory.isEmpty() ? stripped : directory + "/" + stripped;
            result.add(child);
            if (isDirectory) {
                result.addAll(listAll(fs, child));
            }
        }
        return result;
    }

    private static CompoundTag randomTag(final Random random, final int depth) {
        final CompoundTag tag = new CompoundTag();
        final String[] keys = {
            "address", "buffer", "canvas", "mode", "entries", "path", "data", "code", "label",
            "readOnly", "running", "signals", "users", "uptime", "sectors", "isOn", "name", "args",
        };
        final int fields = random.nextInt(6);
        for (int i = 0; i < fields; i++) {
            final String key = keys[random.nextInt(keys.length)];
            tag.put(key, randomValue(random, depth));
        }
        return tag;
    }

    private static Tag randomValue(final Random random, final int depth) {
        switch (random.nextInt(depth > 3 ? 6 : 8)) {
            case 0: return StringTag.valueOf(randomString(random));
            case 1: {
                final byte[] bytes = new byte[random.nextInt(64)];
                random.nextBytes(bytes);
                return new ByteArrayTag(bytes);
            }
            case 2: return net.minecraft.nbt.IntTag.valueOf(random.nextInt());
            case 3: return net.minecraft.nbt.LongTag.valueOf(random.nextLong());
            case 4: return net.minecraft.nbt.ByteTag.valueOf((byte) random.nextInt());
            case 5: return net.minecraft.nbt.DoubleTag.valueOf(random.nextDouble() * Double.MAX_VALUE);
            case 6: {
                final ListTag list = new ListTag();
                final int n = random.nextInt(4);
                for (int i = 0; i < n; i++) list.add(randomTag(random, depth + 1));
                return list;
            }
            default: return randomTag(random, depth + 1);
        }
    }

    private static String randomString(final Random random) {
        final String[] nasty = {
            "", "../..", "/etc/passwd", "\u0000", "\uD800", "a".repeat(4096),
            "-1", "99999999999999999999", "NaN", "\\..\\..",
        };
        return random.nextInt(3) == 0
            ? nasty[random.nextInt(nasty.length)]
            : Long.toHexString(random.nextLong());
    }
}
