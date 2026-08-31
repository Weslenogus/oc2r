/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.fs;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Storage behind a managed {@code filesystem} component.
 * <p>
 * The shape follows what OpenComputers 1 exposes to Lua, which is a stripped down POSIX: open a
 * path, read or write bytes, seek, close. Paths are strings rather than any host path type, and
 * are normalized by {@link FilePath} before they reach an implementation, so nothing here can be
 * talked into leaving its root.
 * <p>
 * Reads and writes deal in {@code byte[]}, never {@link String}. A disk holds whatever a program
 * put on it, and decoding that as text on the way through would quietly corrupt every binary file
 * on it.
 */
public interface VirtualFileSystem {
    /**
     * How much a single {@code read} may return, however much the caller asks for. Programs
     * routinely pass {@code math.huge} and loop, so this is the real transfer size.
     */
    int MAX_READ_SIZE = 64 * 1024;

    enum Mode {
        READ,
        WRITE,
        APPEND;

        /**
         * Parses the mode string a program passes to {@code filesystem.open}. The {@code b} suffix
         * is accepted and ignored: every file here is binary already.
         */
        public static Mode parse(final String value) {
            return switch (value.replace("b", "").toLowerCase()) {
                case "", "r" -> READ;
                case "w" -> WRITE;
                case "a" -> APPEND;
                default -> throw new IllegalArgumentException("unsupported mode");
            };
        }
    }

    boolean isReadOnly();

    /**
     * Capacity in bytes, or {@link Long#MAX_VALUE} if unbounded.
     */
    long getSpaceTotal();

    long getSpaceUsed();

    boolean exists(String path);

    long size(String path);

    boolean isDirectory(String path);

    /**
     * Modification time in milliseconds since the epoch, or {@code 0} if unknown.
     */
    long lastModified(String path);

    /**
     * The entries of a directory, with a trailing slash on those that are themselves directories.
     *
     * @return the entries, or {@code null} if the path is not a directory.
     */
    @Nullable
    String[] list(String path);

    boolean makeDirectory(String path) throws IOException;

    boolean remove(String path) throws IOException;

    boolean rename(String from, String to) throws IOException;

    /**
     * Opens a file.
     *
     * @return a handle for the other file operations.
     */
    int open(String path, Mode mode) throws IOException;

    boolean isOpen(int handle);

    void close(int handle) throws IOException;

    /**
     * Reads up to {@code count} bytes, capped at {@link #MAX_READ_SIZE}.
     *
     * @return the bytes read, or {@code null} at end of file.
     */
    @Nullable
    byte[] read(int handle, long count) throws IOException;

    boolean write(int handle, byte[] value) throws IOException;

    /**
     * Moves a handle's position.
     *
     * @param whence one of {@code "set"}, {@code "cur"} or {@code "end"}.
     * @return the new position.
     */
    long seek(int handle, String whence, long offset) throws IOException;

    /**
     * Closes every open handle, called when the machine stops so a reboot starts clean.
     */
    void closeAll();
}
