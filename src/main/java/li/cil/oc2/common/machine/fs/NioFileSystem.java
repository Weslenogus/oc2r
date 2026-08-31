/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link VirtualFileSystem} over a real directory tree.
 * <p>
 * This backs hard drives and floppies, whose contents live under the world save so they survive a
 * restart. Because the root is a {@link Path} rather than a {@link java.io.File}, the same class
 * also mounts a zip archive read only through
 * {@link java.nio.file.FileSystems#newFileSystem(Path, ClassLoader)}, which is how an operating
 * system image ships inside the mod jar and is handed to a machine as a floppy.
 * <p>
 * Every path is normalized and re-resolved against the root, and the result is checked to still be
 * under it. That second check is what catches the cases normalization alone would not, such as a
 * symlink pointing outside the tree.
 */
public final class NioFileSystem extends AbstractVirtualFileSystem {
    private final Path root;
    private final boolean isReadOnly;
    private final long capacity;

    /**
     * Cached total size of the tree. Walking it on every write would turn a write loop, which is
     * exactly how an operating system installer copies files, into quadratic work.
     */
    private long spaceUsed = -1;

    ///////////////////////////////////////////////////////////////////

    public NioFileSystem(final Path root, final boolean isReadOnly, final long capacity) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.isReadOnly = isReadOnly;
        this.capacity = capacity;

        if (!isReadOnly) {
            Files.createDirectories(this.root);
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean isReadOnly() {
        return isReadOnly;
    }

    @Override
    public long getSpaceTotal() {
        return capacity;
    }

    @Override
    public synchronized long getSpaceUsed() {
        if (spaceUsed < 0) {
            spaceUsed = computeSpaceUsed();
        }
        return spaceUsed;
    }

    @Override
    public synchronized boolean exists(final String path) {
        final Path resolved = resolveOrNull(path);
        return resolved != null && Files.exists(resolved);
    }

    @Override
    public synchronized long size(final String path) {
        final Path resolved = resolveOrNull(path);
        try {
            return resolved != null && Files.isRegularFile(resolved) ? Files.size(resolved) : 0;
        } catch (final IOException e) {
            return 0;
        }
    }

    @Override
    public synchronized boolean isDirectory(final String path) {
        final Path resolved = resolveOrNull(path);
        return resolved != null && Files.isDirectory(resolved);
    }

    @Override
    public synchronized long lastModified(final String path) {
        final Path resolved = resolveOrNull(path);
        if (resolved == null) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(resolved).toMillis();
        } catch (final IOException e) {
            return 0;
        }
    }

    @Override
    @Nullable
    public synchronized String[] list(final String path) {
        final Path resolved = resolveOrNull(path);
        if (resolved == null || !Files.isDirectory(resolved)) {
            return null;
        }

        try (final Stream<Path> entries = Files.list(resolved)) {
            final List<String> result = new ArrayList<>();
            entries.forEach(entry -> {
                // The trailing slash is how a program tells a directory from a file without a
                // second round trip, and OpenOS's ls relies on it.
                final String name = entry.getFileName().toString();
                result.add(Files.isDirectory(entry) ? name + "/" : name);
            });
            return result.toArray(new String[0]);
        } catch (final IOException e) {
            return null;
        }
    }

    @Override
    public synchronized boolean makeDirectory(final String path) throws IOException {
        requireWritable();
        final Path resolved = resolve(path);
        if (Files.exists(resolved)) {
            return false;
        }
        Files.createDirectories(resolved);
        invalidateSpaceUsed();
        return true;
    }

    @Override
    public synchronized boolean remove(final String path) throws IOException {
        requireWritable();
        final Path resolved = resolve(path);
        if (!Files.exists(resolved)) {
            return false;
        }

        if (Files.isDirectory(resolved)) {
            // Deepest first, because a directory has to be empty before it can go.
            try (final Stream<Path> tree = Files.walk(resolved)) {
                final List<Path> entries = tree.sorted(Comparator.reverseOrder()).toList();
                for (final Path entry : entries) {
                    Files.deleteIfExists(entry);
                }
            }
        } else {
            Files.delete(resolved);
        }

        invalidateSpaceUsed();
        return true;
    }

    @Override
    public synchronized boolean rename(final String from, final String to) throws IOException {
        requireWritable();
        final Path source = resolve(from);
        final Path target = resolve(to);
        if (!Files.exists(source)) {
            return false;
        }

        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        invalidateSpaceUsed();
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected Handle openHandle(final String normalizedPath, final Mode mode) throws IOException {
        final Path resolved = resolve(normalizedPath);

        if (mode == Mode.READ) {
            if (!Files.isRegularFile(resolved)) {
                throw new NoSuchFileException(normalizedPath);
            }
            return new ChannelHandle(Files.newByteChannel(resolved, StandardOpenOption.READ), false);
        }

        final Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        final SeekableByteChannel channel = mode == Mode.APPEND
            ? Files.newByteChannel(resolved, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            StandardOpenOption.APPEND)
            : Files.newByteChannel(resolved, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        invalidateSpaceUsed();
        return new ChannelHandle(channel, true);
    }

    ///////////////////////////////////////////////////////////////////

    private void requireWritable() throws IOException {
        if (isReadOnly) {
            throw new IOException("filesystem is read only");
        }
    }

    private Path resolve(final String path) {
        final String normalized = FilePath.normalize(path);
        final Path resolved = root.resolve(normalized).normalize();
        // Normalizing the string handles "..", this catches everything else that could still end
        // up outside, a symlink in the tree being the obvious one.
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the file system root");
        }
        return resolved;
    }

    /**
     * Resolves for the read only queries, which answer "no" for a path that could never be valid
     * rather than raising. Asking whether nonsense exists is a question with an obvious answer.
     */
    @Nullable
    private Path resolveOrNull(final String path) {
        try {
            return resolve(path);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private void invalidateSpaceUsed() {
        spaceUsed = -1;
    }

    private long computeSpaceUsed() {
        final long[] total = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            return 0;
        }
        return total[0];
    }

    ///////////////////////////////////////////////////////////////////

    private final class ChannelHandle implements Handle {
        private final SeekableByteChannel channel;
        private final boolean writable;

        ChannelHandle(final SeekableByteChannel channel, final boolean writable) {
            this.channel = channel;
            this.writable = writable;
        }

        @Override
        public int read(final byte[] buffer) throws IOException {
            return channel.read(ByteBuffer.wrap(buffer));
        }

        @Override
        public void write(final byte[] value) throws IOException {
            if (!writable) {
                throw new IOException("file is not open for writing");
            }
            if (getSpaceUsed() + value.length > capacity) {
                throw new IOException("not enough space");
            }
            channel.write(ByteBuffer.wrap(value));
            invalidateSpaceUsed();
        }

        @Override
        public long seek(final long offset, final String whence) throws IOException {
            final long position = applyWhence(channel.position(), channel.size(), whence, offset);
            channel.position(position);
            return position;
        }

        @Override
        public void close() throws IOException {
            channel.close();
            invalidateSpaceUsed();
        }
    }
}
