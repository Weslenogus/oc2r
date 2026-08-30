/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handle bookkeeping shared by every {@link VirtualFileSystem}.
 * <p>
 * Handles are small integers because that is what crosses into Lua. They are also the reason a
 * filesystem needs a lifecycle: a machine that crashes or is powered off mid-write leaves its
 * handles behind, so {@link #closeAll()} is what stops a world from slowly accumulating open file
 * descriptors.
 */
public abstract class AbstractVirtualFileSystem implements VirtualFileSystem {
    /**
     * How many files one machine may hold open. Matches the OpenComputers 1 default and keeps a
     * runaway program from exhausting the host's descriptors.
     */
    public static final int MAX_HANDLES = 16;

    /**
     * An open file. Implementations are only ever touched while holding the file system's monitor.
     */
    protected interface Handle {
        /**
         * @return the number of bytes read, or {@code -1} at end of file.
         */
        int read(byte[] buffer) throws IOException;

        void write(byte[] value) throws IOException;

        long seek(long offset, String whence) throws IOException;

        void close() throws IOException;
    }

    private final Map<Integer, Handle> handles = new HashMap<>();
    private int nextHandle = 1;

    ///////////////////////////////////////////////////////////////////

    /**
     * Opens the underlying file. Called with the file system's monitor held and a path that has
     * already been normalized.
     */
    protected abstract Handle openHandle(String normalizedPath, Mode mode) throws IOException;

    ///////////////////////////////////////////////////////////////////

    @Override
    public final synchronized int open(final String path, final Mode mode) throws IOException {
        if (isReadOnly() && mode != Mode.READ) {
            throw new IOException("filesystem is read only");
        }
        if (handles.size() >= MAX_HANDLES) {
            throw new IOException("too many open handles");
        }

        final Handle handle = openHandle(FilePath.normalize(path), mode);
        final int id = nextHandle++;
        handles.put(id, handle);
        return id;
    }

    @Override
    public final synchronized boolean isOpen(final int handle) {
        return handles.containsKey(handle);
    }

    @Override
    public final synchronized void close(final int handle) throws IOException {
        final Handle value = handles.remove(handle);
        if (value == null) {
            throw new IOException("bad file descriptor");
        }
        value.close();
    }

    @Override
    @Nullable
    public final synchronized byte[] read(final int handle, final long count) throws IOException {
        final Handle value = require(handle);

        // Programs pass math.huge here and loop until they get nil back, so the cap is what
        // actually decides the transfer size rather than the request.
        final int limit = (int) Math.max(0, Math.min(count, MAX_READ_SIZE));
        if (limit == 0) {
            return new byte[0];
        }

        final byte[] buffer = new byte[limit];
        final int read = value.read(buffer);
        if (read < 0) {
            return null;
        }
        if (read == buffer.length) {
            return buffer;
        }

        final byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    @Override
    public final synchronized boolean write(final int handle, final byte[] data) throws IOException {
        require(handle).write(data);
        return true;
    }

    @Override
    public final synchronized long seek(final int handle, final String whence, final long offset) throws IOException {
        return require(handle).seek(offset, whence);
    }

    @Override
    public final synchronized void closeAll() {
        final List<Handle> open = new ArrayList<>(handles.values());
        handles.clear();
        for (final Handle handle : open) {
            try {
                handle.close();
            } catch (final IOException ignored) {
                // Nothing useful to do while tearing down, and one stuck handle must not stop the
                // rest from being released.
            }
        }
    }

    private Handle require(final int handle) throws IOException {
        final Handle value = handles.get(handle);
        if (value == null) {
            throw new IOException("bad file descriptor");
        }
        return value;
    }

    protected static long applyWhence(final long current, final long size, final String whence, final long offset) {
        final long position = switch (whence) {
            case "set" -> offset;
            case "cur" -> current + offset;
            case "end" -> size + offset;
            default -> throw new IllegalArgumentException("invalid mode");
        };
        if (position < 0) {
            throw new IllegalArgumentException("invalid offset");
        }
        return position;
    }
}
