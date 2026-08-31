/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.serialization;

import li.cil.oc2.common.machine.fs.FilePath;
import li.cil.oc2.common.machine.fs.VirtualFileSystem;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Saves and restores the contents of a {@link VirtualFileSystem} as a flat list of paths.
 * <p>
 * A flat list rather than a nested structure, because it makes the format independent of how any
 * particular filesystem stores its tree, and because a disk is a few dozen files rather than
 * something that needs an index.
 * <p>
 * This is for filesystems that live in a block entity's tag, which means in memory ones. A
 * filesystem backed by real files on disk persists itself and has no business being copied into a
 * region file.
 */
public final class FileSystemSerialization {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String PACKED_TAG_NAME = "packed";
    private static final String ENTRIES_TAG_NAME = "entries";
    private static final String PATH_TAG_NAME = "path";
    private static final String DATA_TAG_NAME = "data";
    private static final String DIRECTORY_TAG_NAME = "directory";

    /**
     * How much of a filesystem will be written into a tag. Region files are not the place for an
     * unbounded blob, and a disk this large is a sign something has gone wrong rather than a
     * legitimate use.
     */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /**
     * What Minecraft will read back off the network in one tag, from
     * {@code FriendlyByteBuf.readNbt}.
     * <p>
     * It matters here because a computer's disk travels in the dropped item's NBT, and an item is
     * sent to clients. A disk that packs to more than this cannot survive being mined: the client
     * receiving it throws rather than truncating. Compression is what keeps an ordinary operating
     * system install, which is nearly all text, well inside it.
     */
    private static final int NETWORK_TAG_LIMIT = 2 * 1024 * 1024;

    private FileSystemSerialization() {
    }

    public static CompoundTag serialize(final VirtualFileSystem fileSystem) {
        final CompoundTag tag = new CompoundTag();
        final ListTag entries = new ListTag();

        long total = 0;
        final Deque<String> pending = new ArrayDeque<>();
        pending.add("");

        while (!pending.isEmpty()) {
            final String directory = pending.removeFirst();
            final String[] names = fileSystem.list(directory);
            if (names == null) {
                continue;
            }

            for (final String name : names) {
                final boolean isDirectory = name.endsWith("/");
                final String child = directory.isEmpty()
                    ? stripSlash(name)
                    : directory + "/" + stripSlash(name);

                if (isDirectory) {
                    final CompoundTag entry = new CompoundTag();
                    entry.putString(PATH_TAG_NAME, child);
                    entry.putBoolean(DIRECTORY_TAG_NAME, true);
                    entries.add(entry);
                    pending.addLast(child);
                    continue;
                }

                final long size = fileSystem.size(child);
                if (total + size > MAX_TOTAL_BYTES) {
                    LOGGER.warn("Refusing to save file system contents beyond {} bytes; [{}] and " +
                        "anything after it were dropped.", MAX_TOTAL_BYTES, child);
                    return pack(tag, entries);
                }
                total += size;

                final byte[] data = readFully(fileSystem, child, size);
                if (data == null) {
                    continue;
                }

                final CompoundTag entry = new CompoundTag();
                entry.putString(PATH_TAG_NAME, child);
                entry.putByteArray(DATA_TAG_NAME, data);
                entries.add(entry);
            }
        }

        return pack(tag, entries);
    }

    /**
     * Compresses the entry list into a single byte array.
     * <p>
     * A disk is mostly source code, which is four to five times smaller compressed, and it has to
     * pass through both a region file and, when the computer is mined, an item's NBT on its way to
     * a client. Storing the tree as tags rather than bytes would spend the whole budget on
     * structure that gzip removes for nothing.
     */
    private static CompoundTag pack(final CompoundTag tag, final ListTag entries) {
        final CompoundTag inner = new CompoundTag();
        inner.put(ENTRIES_TAG_NAME, entries);

        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(inner, out);
            final byte[] packed = out.toByteArray();

            if (packed.length > NETWORK_TAG_LIMIT) {
                LOGGER.warn("A file system packs to {} bytes, over the {} byte limit on a tag sent "
                        + "to a client. It is safe where it is, but a computer holding it cannot be "
                        + "mined without disconnecting whoever picks it up. Lower maxDiskSize, or "
                        + "keep less on the disk.",
                    packed.length, NETWORK_TAG_LIMIT);
            }

            tag.putByteArray(PACKED_TAG_NAME, packed);
            return tag;
        } catch (final IOException e) {
            // Nothing sensible to do but fall back to the uncompressed form, which still reads.
            LOGGER.warn("Could not compress file system contents: {}", e.getMessage());
            tag.put(ENTRIES_TAG_NAME, entries);
            return tag;
        }
    }

    public static void deserialize(final CompoundTag tag, final VirtualFileSystem fileSystem) {
        final ListTag entries = readEntries(tag);
        for (int i = 0; i < entries.size(); i++) {
            final CompoundTag entry = entries.getCompound(i);
            final String path = entry.getString(PATH_TAG_NAME);
            if (path.isEmpty()) {
                continue;
            }

            try {
                // Paths are re-normalized on the way in: a tag can be hand edited, and a saved
                // path is no more trustworthy than one a machine passed in.
                final String normalized = FilePath.normalize(path);
                if (entry.getBoolean(DIRECTORY_TAG_NAME)) {
                    fileSystem.makeDirectory(normalized);
                } else {
                    write(fileSystem, normalized, entry.getByteArray(DATA_TAG_NAME));
                }
            } catch (final IOException | IllegalArgumentException e) {
                LOGGER.warn("Skipping unreadable file system entry [{}]: {}", path, e.getMessage());
            }
        }
    }

    /**
     * Reads the entry list, compressed or not. The plain list is what disks written before
     * compression carry, and reading both is what lets one be loaded and saved back compressed.
     */
    private static ListTag readEntries(final CompoundTag tag) {
        if (!tag.contains(PACKED_TAG_NAME, NBTTagIds.TAG_BYTE_ARRAY)) {
            return tag.getList(ENTRIES_TAG_NAME, NBTTagIds.TAG_COMPOUND);
        }
        try {
            final CompoundTag inner = NbtIo.readCompressed(
                new ByteArrayInputStream(tag.getByteArray(PACKED_TAG_NAME)));
            return inner.getList(ENTRIES_TAG_NAME, NBTTagIds.TAG_COMPOUND);
        } catch (final IOException e) {
            LOGGER.warn("Could not read compressed file system contents: {}", e.getMessage());
            return new ListTag();
        }
    }

    private static byte[] readFully(final VirtualFileSystem fileSystem, final String path, final long size) {
        try {
            final int handle = fileSystem.open(path, VirtualFileSystem.Mode.READ);
            try {
                final byte[] result = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
                int offset = 0;
                while (offset < result.length) {
                    final byte[] chunk = fileSystem.read(handle, result.length - offset);
                    if (chunk == null || chunk.length == 0) {
                        break;
                    }
                    System.arraycopy(chunk, 0, result, offset, Math.min(chunk.length, result.length - offset));
                    offset += chunk.length;
                }
                return result;
            } finally {
                fileSystem.close(handle);
            }
        } catch (final IOException e) {
            LOGGER.warn("Could not read [{}] while saving: {}", path, e.getMessage());
            return null;
        }
    }

    private static void write(final VirtualFileSystem fileSystem, final String path, final byte[] data)
        throws IOException {
        final int handle = fileSystem.open(path, VirtualFileSystem.Mode.WRITE);
        try {
            fileSystem.write(handle, data);
        } finally {
            fileSystem.close(handle);
        }
    }

    private static String stripSlash(final String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }
}
