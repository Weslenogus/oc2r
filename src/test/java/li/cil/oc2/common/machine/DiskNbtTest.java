/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.Constants;
import li.cil.oc2.common.item.LuaComputerItemTag;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a disk costs once it is a tag.
 * <p>
 * A computer's disk travels in the dropped item's NBT, so that mining one keeps what was installed
 * on it. A full one is far larger than the 2MB {@code FriendlyByteBuf.readNbt} will accept, which
 * would disconnect whoever picked it up - so the item does not send it, and that is the contract
 * being pinned here. The disk is also written into the chunk on every save, where compression is
 * what keeps that affordable.
 */
public class DiskNbtTest {
    /**
     * From {@code FriendlyByteBuf.readNbt}, which reads with an accounter capped here.
     */
    private static final int NETWORK_TAG_LIMIT = 2 * 1024 * 1024;

    /**
     * The shipped default.
     */
    private static final int DEFAULT_DISK_SIZE = 32 * Constants.MEGABYTE;

    @Test
    void theItemDoesNotSendTheDiskToClients() throws Exception {
        // Four megabytes of incompressible data: a disk holding a downloaded archive, or an image,
        // or anything else that has already been compressed once. That is the honest worst case,
        // and it is what decides whether the tag can be allowed onto the wire at all.
        final RamFileSystem fs = new RamFileSystem(DEFAULT_DISK_SIZE);
        fillWithNoise(fs, 4 * Constants.MEGABYTE);

        // Shaped like a mined computer: the block entity's save data under BlockEntityTag.
        final CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("disk", FileSystemSerialization.serialize(fs));
        blockEntity.putString("diskAddress", "e1f2");
        final CompoundTag stack = new CompoundTag();
        stack.put("BlockEntityTag", blockEntity);

        assertTrue(tagSize(stack) > NETWORK_TAG_LIMIT,
            "the premise of this test is gone: even a disk of noise now fits through the network");

        final CompoundTag shared = LuaComputerItemTag.withoutBulkData(stack);
        assertNotNull(shared);
        assertTrue(tagSize(shared) < NETWORK_TAG_LIMIT,
            "mining a full computer would disconnect whoever picked it up");
        assertEquals("e1f2", shared.getCompound("BlockEntityTag").getString("diskAddress"),
            "the trim took more than the bulk with it");

        // And the stack it was asked about still has its disk: what getShareTag is handed is the
        // live tag, so trimming in place would delete the player's files rather than the packet's.
        assertTrue(stack.getCompound("BlockEntityTag").contains("disk"),
            "trimming the share tag ate the disk it was describing");
    }

    @Test
    void compressionIsWhatMakesADiskAffordableToSave() throws Exception {
        // The disk is written into the chunk on every save, so what a full one packs to is what a
        // computer costs the world file. Source compresses well; this is the case that matters,
        // because it is what an installed operating system is made of.
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

    /**
     * Fills a file system with data that will not compress, which is the case the network limit has
     * to be judged against.
     */
    private static void fillWithNoise(final RamFileSystem fs, final int bytes) throws Exception {
        final java.util.Random random = new java.util.Random(7);
        final byte[] chunk = new byte[64 * 1024];
        int written = 0;
        for (int i = 0; written + chunk.length <= bytes; i++) {
            random.nextBytes(chunk);
            write(fs, "/downloads/blob" + i + ".bin", chunk);
            written += chunk.length;
        }
    }

    private static void fill(final RamFileSystem fs, final int bytes) throws Exception {
        // A different file each time. Writing one chunk over and over would measure how well
        // deflate spots a repeated block, which is not what a disk full of a real operating system
        // looks like, and would make the whole thing pack about ten times better than it should.
        int written = 0;
        for (int i = 0; written + 8 * 1024 <= bytes; i++) {
            final byte[] chunk = source(8 * 1024, i * 977);
            write(fs, "/lib/pkg" + (i / 32) + "/file" + i + ".lua", chunk);
            written += chunk.length;
        }
    }

    /**
     * Text with the shape and redundancy of real Lua. Measured against the actual MineOS libraries,
     * which compress about 4.7 to 1; anything much more compressible than that would make this test
     * pass for the wrong reason.
     */
    private static byte[] source(final int size, final int seed) {
        final StringBuilder builder = new StringBuilder(size + 256);
        int i = seed;
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
