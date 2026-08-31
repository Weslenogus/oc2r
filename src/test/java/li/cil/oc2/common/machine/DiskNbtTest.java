/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.Constants;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.fs.VirtualFileSystem;
import li.cil.oc2.common.machine.serialization.FileSystemSerialization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a disk costs once it is a tag.
 * <p>
 * A computer's disk travels in the dropped item's NBT, so that mining one keeps what was installed
 * on it, and an item is sent to clients. {@code FriendlyByteBuf.readNbt} refuses anything over 2MB,
 * throwing rather than truncating, so a disk past that point cannot be mined without disconnecting
 * whoever picks it up. That is the ceiling the configured disk size has to respect, and this is
 * what holds the two in step.
 */
public class DiskNbtTest {
    /**
     * From {@code FriendlyByteBuf.readNbt}, which reads with an accounter capped here.
     */
    private static final int NETWORK_TAG_LIMIT = 2 * 1024 * 1024;

    /**
     * The shipped default. If this is raised, the assertion below is the thing that notices.
     */
    private static final int DEFAULT_DISK_SIZE = 8 * Constants.MEGABYTE;

    @Test
    void aFullDiskOfSourceStillFitsThroughTheNetwork() throws Exception {
        final RamFileSystem fs = new RamFileSystem(DEFAULT_DISK_SIZE);
        fill(fs, DEFAULT_DISK_SIZE);

        final int size = tagSize(FileSystemSerialization.serialize(fs));
        assertTrue(size < NETWORK_TAG_LIMIT,
            "a full disk packs to " + size + " bytes, over the " + NETWORK_TAG_LIMIT
                + " byte limit on a tag sent to a client, so mining one would disconnect a player");
    }

    @Test
    void compressionIsWhatMakesThatFit() throws Exception {
        // Without it the tag is the content plus a little, and the default would be four times over
        // the limit. Worth stating, because the disk size and the compression are only safe
        // together.
        final RamFileSystem fs = new RamFileSystem(DEFAULT_DISK_SIZE);
        fill(fs, DEFAULT_DISK_SIZE);
        assertTrue(tagSize(FileSystemSerialization.serialize(fs)) < DEFAULT_DISK_SIZE / 2,
            "contents do not look compressed");
    }

    @Test
    void aCompressedDiskStillRoundTrips() throws Exception {
        final RamFileSystem source = new RamFileSystem(1 << 20);
        final byte[] binary = new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80};
        write(source, "/init.lua", "print('hello')".getBytes(StandardCharsets.UTF_8));
        write(source, "/bin/nested/deep.bin", binary);
        source.makeDirectory("/empty");

        final RamFileSystem restored = new RamFileSystem(1 << 20);
        FileSystemSerialization.deserialize(FileSystemSerialization.serialize(source), restored);

        assertArrayEquals("print('hello')".getBytes(StandardCharsets.UTF_8),
            read(restored, "/init.lua"));
        assertArrayEquals(binary, read(restored, "/bin/nested/deep.bin"));
        assertTrue(restored.isDirectory("/empty"));
    }

    ///////////////////////////////////////////////////////////////////

    private static void fill(final RamFileSystem fs, final int bytes) throws Exception {
        final byte[] chunk = source(8 * 1024);
        int written = 0;
        for (int i = 0; written + chunk.length <= bytes; i++) {
            write(fs, "/lib/pkg" + (i / 32) + "/file" + i + ".lua", chunk);
            written += chunk.length;
        }
    }

    /**
     * Text with the shape and redundancy of real Lua. Measured against the actual MineOS libraries,
     * which compress about 4.7 to 1; anything much more compressible than that would make this test
     * pass for the wrong reason.
     */
    private static byte[] source(final int size) {
        final StringBuilder builder = new StringBuilder(size + 256);
        int i = 0;
        while (builder.length() < size) {
            builder.append("local function handler").append(i)
                .append("(window, workspace, value, index, callback)\n")
                .append("  local x, y = window.x + ").append(i % 37)
                .append(", window.y + ").append(i % 19).append("\n")
                .append("  if value ~= nil and type(value) == \"table\" then\n")
                .append("    workspace:draw(x, y, value.width or 80, value.height or 25, 0x")
                .append(Integer.toHexString(0x100000 + i % 0xEFFFFF)).append(")\n")
                .append("  elseif callback then callback(index, \"item").append(i).append("\") end\n")
                .append("  return x, y\nend\n\n");
            i++;
        }
        return builder.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    private static int tagSize(final CompoundTag tag) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(out));
        return out.size();
    }

    private static void write(final RamFileSystem fs, final String path, final byte[] data)
        throws java.io.IOException {
        final int handle = fs.open(path, VirtualFileSystem.Mode.WRITE);
        fs.write(handle, data);
        fs.close(handle);
    }

    private static byte[] read(final RamFileSystem fs, final String path) throws java.io.IOException {
        final int handle = fs.open(path, VirtualFileSystem.Mode.READ);
        final byte[] data = fs.read(handle, Long.MAX_VALUE);
        fs.close(handle);
        return data;
    }
}
