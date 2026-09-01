/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A read only {@link VirtualFileSystem} whose contents come from the mod's own resources.
 * <p>
 * This is what a machine boots from when nothing else on it is bootable. A computer that has just
 * been placed has an empty disk, and without something like this the stock BIOS finds no
 * {@code /init.lua} anywhere, stops with "no bootable medium found", and leaves the player looking
 * at a black screen with nothing to type at.
 * <p>
 * The contents are loaded once and shared: every computer in the world has the same ROM, and a
 * copy per block entity would be a copy of the whole shell per computer. Handles are not shared,
 * though, which is why the image and the file system are separate types — two machines reading the
 * ROM at the same time must not be able to close each other's files.
 */
public final class RomFileSystem extends AbstractVirtualFileSystem {
    /**
     * Names the files that make up a ROM, one path per line.
     * <p>
     * A manifest rather than a directory listing because these live inside a jar at runtime, where
     * walking a directory is not something the class loader offers.
     */
    private static final String MANIFEST_NAME = "manifest.txt";

    /**
     * The immutable contents of a ROM.
     */
    public record Image(Map<String, byte[]> files, Set<String> directories, long size) {
    }

    ///////////////////////////////////////////////////////////////////

    private final Image image;

    ///////////////////////////////////////////////////////////////////

    public RomFileSystem(final Image image) {
        this.image = image;
    }

    /**
     * Reads a ROM out of the class path.
     *
     * @param resourceRoot the directory holding the manifest, e.g. {@code /assets/oc2r/lua/rom}.
     * @throws IOException if the manifest or any file it names is missing.
     */
    public static Image load(final String resourceRoot) throws IOException {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Set<String> directories = new LinkedHashSet<>();
        long size = 0;

        for (final String line : read(resourceRoot + "/" + MANIFEST_NAME).split("\n")) {
            final String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }

            final String path = FilePath.normalize(name);
            final byte[] data = readBytes(resourceRoot + "/" + path);
            files.put(path, data);
            size += data.length;

            for (String parent = FilePath.parent(path); !parent.isEmpty(); parent = FilePath.parent(parent)) {
                directories.add(parent);
            }
        }

        return new Image(Collections.unmodifiableMap(files),
            Collections.unmodifiableSet(directories), size);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long getSpaceTotal() {
        return image.size();
    }

    @Override
    public long getSpaceUsed() {
        // A ROM is full by definition: nothing can be written to it, so reporting free space would
        // have an installer conclude there is somewhere to put its files.
        return image.size();
    }

    @Override
    public boolean exists(final String path) {
        final String normalized = FilePath.normalize(path);
        return normalized.isEmpty()
            || image.files().containsKey(normalized)
            || image.directories().contains(normalized);
    }

    @Override
    public long size(final String path) {
        final byte[] data = image.files().get(FilePath.normalize(path));
        return data == null ? 0 : data.length;
    }

    @Override
    public boolean isDirectory(final String path) {
        final String normalized = FilePath.normalize(path);
        return normalized.isEmpty() || image.directories().contains(normalized);
    }

    @Override
    public long lastModified(final String path) {
        // Nothing in here ever changes, and a timestamp that moved with the jar's build time would
        // only make caches think it had.
        return 0;
    }

    @Override
    @Nullable
    public String[] list(final String path) {
        final String normalized = FilePath.normalize(path);
        if (!isDirectory(normalized)) {
            return null;
        }

        final String prefix = normalized.isEmpty() ? "" : normalized + "/";
        final Set<String> entries = new LinkedHashSet<>();
        for (final String directory : image.directories()) {
            addChild(entries, prefix, directory, true);
        }
        for (final String file : image.files().keySet()) {
            addChild(entries, prefix, file, false);
        }

        return entries.toArray(new String[0]);
    }

    @Override
    public boolean makeDirectory(final String path) {
        return false;
    }

    @Override
    public boolean remove(final String path) {
        return false;
    }

    @Override
    public boolean rename(final String from, final String to) {
        return false;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected Handle openHandle(final String normalizedPath, final Mode mode) throws IOException {
        // Writes are already refused by the base class, which checks isReadOnly before it gets
        // here; this only has to serve reads.
        final byte[] data = image.files().get(normalizedPath);
        if (data == null) {
            throw new IOException(isDirectory(normalizedPath)
                ? "is a directory"
                : "no such file or directory");
        }
        return new RomHandle(data);
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Adds {@code candidate} to {@code entries} if it lies directly inside {@code prefix}.
     */
    private static void addChild(final Set<String> entries, final String prefix,
                                 final String candidate, final boolean isDirectory) {
        if (!candidate.startsWith(prefix) || candidate.length() == prefix.length()) {
            return;
        }
        final String rest = candidate.substring(prefix.length());
        final int slash = rest.indexOf('/');
        if (slash >= 0) {
            // A grandchild; the directory between it and here is listed in its own right.
            return;
        }
        entries.add(isDirectory ? rest + "/" : rest);
    }

    private static String read(final String resource) throws IOException {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(final String resource) throws IOException {
        try (final InputStream stream = RomFileSystem.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing resource [" + resource + "]");
            }
            return stream.readAllBytes();
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static final class RomHandle implements Handle {
        private final byte[] data;
        private int position;

        RomHandle(final byte[] data) {
            this.data = data;
        }

        @Override
        public int read(final byte[] buffer) {
            if (position >= data.length) {
                return -1;
            }
            final int count = Math.min(buffer.length, data.length - position);
            System.arraycopy(data, position, buffer, 0, count);
            position += count;
            return count;
        }

        @Override
        public void write(final byte[] value) throws IOException {
            throw new IOException("filesystem is read only");
        }

        @Override
        public long seek(final long offset, final String whence) {
            position = (int) Math.min(Integer.MAX_VALUE, applyWhence(position, data.length, whence, offset));
            return position;
        }

        @Override
        public void close() {
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Every path in the image, for tests and for tooling that wants to know what shipped.
     */
    public List<String> getPaths() {
        return new ArrayList<>(image.files().keySet());
    }
}
