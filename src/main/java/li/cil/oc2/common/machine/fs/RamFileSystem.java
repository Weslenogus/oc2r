/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link VirtualFileSystem}, used for the temporary filesystem every machine gets.
 * <p>
 * {@code /tmp} exists for the duration of a boot and is expected to vanish afterwards, so backing
 * it with real files would mean writing to disk data that is guaranteed to be thrown away, and
 * cleaning it up on every crash and world reload. Holding it in memory makes the lifetime match
 * what it is for.
 * <p>
 * The capacity limit is not decoration. Without it a program looping on
 * {@code write(handle, string.rep("x", 1024))} would consume the server's heap, and this is the
 * one filesystem where nothing else bounds the damage.
 */
public final class RamFileSystem extends AbstractVirtualFileSystem {
    private static final byte[] NO_DATA = new byte[0];

    private static final class Node {
        final boolean isDirectory;
        @Nullable final Map<String, Node> children;
        byte[] data = NO_DATA;
        long lastModified = System.currentTimeMillis();

        Node(final boolean isDirectory) {
            this.isDirectory = isDirectory;
            this.children = isDirectory ? new LinkedHashMap<>() : null;
        }

        Map<String, Node> children() {
            if (children == null) {
                throw new IllegalStateException("not a directory");
            }
            return children;
        }
    }

    private final Node root = new Node(true);
    private final long capacity;
    private long used;

    ///////////////////////////////////////////////////////////////////

    public RamFileSystem(final long capacity) {
        this.capacity = capacity;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long getSpaceTotal() {
        return capacity;
    }

    @Override
    public synchronized long getSpaceUsed() {
        return used;
    }

    @Override
    public synchronized boolean exists(final String path) {
        return find(path) != null;
    }

    @Override
    public synchronized long size(final String path) {
        final Node node = find(path);
        return node == null || node.isDirectory ? 0 : node.data.length;
    }

    @Override
    public synchronized boolean isDirectory(final String path) {
        final Node node = find(path);
        return node != null && node.isDirectory;
    }

    @Override
    public synchronized long lastModified(final String path) {
        final Node node = find(path);
        return node == null ? 0 : node.lastModified;
    }

    @Override
    @Nullable
    public synchronized String[] list(final String path) {
        final Node node = find(path);
        if (node == null || !node.isDirectory) {
            return null;
        }

        final List<String> result = new ArrayList<>();
        node.children().forEach((name, child) -> result.add(child.isDirectory ? name + "/" : name));
        return result.toArray(new String[0]);
    }

    @Override
    public synchronized boolean makeDirectory(final String path) {
        final String normalized = FilePath.normalize(path);
        if (normalized.isEmpty() || find(normalized) != null) {
            return false;
        }
        return createDirectories(normalized) != null;
    }

    @Override
    public synchronized boolean remove(final String path) {
        final String normalized = FilePath.normalize(path);
        if (normalized.isEmpty()) {
            return false;
        }

        final Node parent = find(FilePath.parent(normalized));
        if (parent == null || !parent.isDirectory) {
            return false;
        }

        final Node removed = parent.children().remove(FilePath.name(normalized));
        if (removed == null) {
            return false;
        }

        used -= sizeOf(removed);
        return true;
    }

    @Override
    public synchronized boolean rename(final String from, final String to) {
        final String source = FilePath.normalize(from);
        final String target = FilePath.normalize(to);
        if (source.isEmpty() || target.isEmpty()) {
            return false;
        }

        final Node sourceParent = find(FilePath.parent(source));
        if (sourceParent == null || !sourceParent.isDirectory) {
            return false;
        }
        final Node node = sourceParent.children().get(FilePath.name(source));
        if (node == null) {
            return false;
        }

        final Node targetParent = createDirectories(FilePath.parent(target));
        if (targetParent == null) {
            return false;
        }

        final Node replaced = targetParent.children().put(FilePath.name(target), node);
        if (replaced != null) {
            used -= sizeOf(replaced);
        }
        sourceParent.children().remove(FilePath.name(source));
        return true;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected Handle openHandle(final String normalizedPath, final Mode mode) throws IOException {
        if (normalizedPath.isEmpty()) {
            throw new IOException("is a directory");
        }

        if (mode == Mode.READ) {
            final Node node = find(normalizedPath);
            if (node == null) {
                throw new IOException("no such file or directory");
            }
            if (node.isDirectory) {
                throw new IOException("is a directory");
            }
            return new MemoryHandle(node, false);
        }

        final Node parent = createDirectories(FilePath.parent(normalizedPath));
        if (parent == null) {
            throw new IOException("no such file or directory");
        }

        final String name = FilePath.name(normalizedPath);
        Node node = parent.children().get(name);
        if (node != null && node.isDirectory) {
            throw new IOException("is a directory");
        }
        if (node == null) {
            node = new Node(false);
            parent.children().put(name, node);
        }

        if (mode == Mode.WRITE) {
            used -= node.data.length;
            node.data = NO_DATA;
        }

        return new MemoryHandle(node, true);
    }

    ///////////////////////////////////////////////////////////////////

    @Nullable
    private Node find(final String path) {
        Node current = root;
        for (final String segment : FilePath.segments(path)) {
            if (!current.isDirectory) {
                return null;
            }
            current = current.children().get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Walks to a path, creating directories along the way.
     *
     * @return the directory, or {@code null} if a file is in the way.
     */
    @Nullable
    private Node createDirectories(final String path) {
        Node current = root;
        for (final String segment : FilePath.segments(path)) {
            if (!current.isDirectory) {
                return null;
            }
            Node next = current.children().get(segment);
            if (next == null) {
                next = new Node(true);
                current.children().put(segment, next);
            }
            current = next;
        }
        return current.isDirectory ? current : null;
    }

    private static long sizeOf(final Node node) {
        if (!node.isDirectory) {
            return node.data.length;
        }
        long total = 0;
        for (final Node child : node.children().values()) {
            total += sizeOf(child);
        }
        return total;
    }

    ///////////////////////////////////////////////////////////////////

    private final class MemoryHandle implements Handle {
        private final Node node;
        private final boolean writable;
        private int position;

        MemoryHandle(final Node node, final boolean writable) {
            this.node = node;
            this.writable = writable;
            // Append mode is the caller opening at the end; write mode already truncated.
            this.position = node.data.length;
            if (!writable) {
                this.position = 0;
            }
        }

        @Override
        public int read(final byte[] buffer) {
            if (position >= node.data.length) {
                return -1;
            }
            final int count = Math.min(buffer.length, node.data.length - position);
            System.arraycopy(node.data, position, buffer, 0, count);
            position += count;
            return count;
        }

        @Override
        public void write(final byte[] value) throws IOException {
            if (!writable) {
                throw new IOException("file is not open for writing");
            }

            final int end = position + value.length;
            final int growth = Math.max(0, end - node.data.length);
            synchronized (RamFileSystem.this) {
                if (used + growth > capacity) {
                    throw new IOException("not enough space");
                }
                used += growth;
            }

            if (end > node.data.length) {
                final byte[] grown = new byte[end];
                System.arraycopy(node.data, 0, grown, 0, node.data.length);
                node.data = grown;
            }

            System.arraycopy(value, 0, node.data, position, value.length);
            position = end;
            node.lastModified = System.currentTimeMillis();
        }

        @Override
        public long seek(final long offset, final String whence) {
            final long target = applyWhence(position, node.data.length, whence, offset);
            position = (int) Math.min(Integer.MAX_VALUE, target);
            return position;
        }

        @Override
        public void close() {
        }
    }
}
