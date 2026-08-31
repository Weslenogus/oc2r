/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.Machine;
import li.cil.oc2.common.machine.fs.VirtualFileSystem;

import java.io.IOException;

/**
 * The managed {@code filesystem} component: hard drives, floppies and the temporary filesystem.
 * <p>
 * "Managed" means the machine sees files and directories rather than sectors, and the mod handles
 * the layout. That is the mode OpenOS and MineOS install onto; the block level view lives on
 * {@link DriveComponent} instead.
 * <p>
 * Failures split two ways, and the split matters to callers. Something the program could
 * reasonably have expected, a missing file or a full disk, comes back as {@code nil, reason},
 * because that is what {@code fs.open} returning nil means to the code above. A programming error,
 * such as a bad handle or a nonsense seek mode, is raised, so it surfaces where it happened
 * instead of being mistaken for an empty read.
 */
public final class FilesystemComponent extends AbstractLuaComponent {
    private final VirtualFileSystem fileSystem;
    private String label;

    ///////////////////////////////////////////////////////////////////

    public FilesystemComponent(final String address, final VirtualFileSystem fileSystem, final String label) {
        super("filesystem", address);
        this.fileSystem = fileSystem;
        this.label = label;
    }

    ///////////////////////////////////////////////////////////////////

    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String value) {
        label = value;
    }

    @Override
    public void onDisconnect(final Machine machine) {
        // A machine that is torn down while holding handles would otherwise leak them, and on a
        // real directory that means leaked file descriptors for as long as the server runs.
        fileSystem.closeAll();
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 16, doc = "function():number -- The total capacity of the filesystem, in bytes.")
    public Object[] spaceTotal(final Context context, final Arguments args) {
        final long total = fileSystem.getSpaceTotal();
        // An unbounded filesystem reports infinity, which is what programs compare against.
        return new Object[]{total == Long.MAX_VALUE ? Double.POSITIVE_INFINITY : (double) total};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The used capacity of the filesystem, in bytes.")
    public Object[] spaceUsed(final Context context, final Arguments args) {
        return new Object[]{(double) fileSystem.getSpaceUsed()};
    }

    @Callback(direct = true, limit = 16, doc = "function():boolean -- Whether the filesystem is read only.")
    public Object[] isReadOnly(final Context context, final Arguments args) {
        return new Object[]{fileSystem.isReadOnly()};
    }

    @Callback(direct = true, limit = 16, doc = "function():string -- The label of the filesystem.")
    public Object[] getLabel(final Context context, final Arguments args) {
        return new Object[]{label};
    }

    @Callback(doc = "function(value:string):string -- Sets the label of the filesystem. Returns the new label.")
    public Object[] setLabel(final Context context, final Arguments args) {
        if (fileSystem.isReadOnly()) {
            return new Object[]{null, "label is read only"};
        }
        final String value = args.optString(0, "");
        label = value == null ? "" : value.substring(0, Math.min(16, value.length()));
        return new Object[]{label};
    }

    @Callback(direct = true, limit = 64, doc = "function(path:string):boolean -- Whether the path exists.")
    public Object[] exists(final Context context, final Arguments args) {
        return new Object[]{fileSystem.exists(args.checkString(0))};
    }

    @Callback(direct = true, limit = 64, doc = "function(path:string):number -- The size of the file at the path, or 0.")
    public Object[] size(final Context context, final Arguments args) {
        return new Object[]{(double) fileSystem.size(args.checkString(0))};
    }

    @Callback(direct = true, limit = 64, doc = "function(path:string):boolean -- Whether the path is a directory.")
    public Object[] isDirectory(final Context context, final Arguments args) {
        return new Object[]{fileSystem.isDirectory(args.checkString(0))};
    }

    @Callback(direct = true, limit = 64, doc = "function(path:string):number -- The modification time of the path, in milliseconds since the epoch.")
    public Object[] lastModified(final Context context, final Arguments args) {
        return new Object[]{(double) fileSystem.lastModified(args.checkString(0))};
    }

    @Callback(direct = true, limit = 32, doc = "function(path:string):table -- The entries of a directory. Directories carry a trailing slash.")
    public Object[] list(final Context context, final Arguments args) {
        final String[] entries = fileSystem.list(args.checkString(0));
        if (entries == null) {
            return new Object[]{null, "no such file or directory"};
        }
        return new Object[]{(Object) entries};
    }

    @Callback(doc = "function(path:string):boolean -- Creates a directory, including any missing parents.")
    public Object[] makeDirectory(final Context context, final Arguments args) {
        try {
            return new Object[]{fileSystem.makeDirectory(args.checkString(0))};
        } catch (final IOException e) {
            return failure(e);
        }
    }

    @Callback(doc = "function(path:string):boolean -- Removes a file or directory and everything below it.")
    public Object[] remove(final Context context, final Arguments args) {
        try {
            return new Object[]{fileSystem.remove(args.checkString(0))};
        } catch (final IOException e) {
            return failure(e);
        }
    }

    @Callback(doc = "function(from:string, to:string):boolean -- Moves a file or directory.")
    public Object[] rename(final Context context, final Arguments args) {
        try {
            return new Object[]{fileSystem.rename(args.checkString(0), args.checkString(1))};
        } catch (final IOException e) {
            return failure(e);
        }
    }

    @Callback(direct = true, limit = 16, doc = "function(path:string[, mode:string='r']):number -- Opens a file and returns a handle.")
    public Object[] open(final Context context, final Arguments args) {
        final String path = args.checkString(0);
        final String mode = args.optString(1, "r");
        try {
            return new Object[]{fileSystem.open(path, VirtualFileSystem.Mode.parse(mode == null ? "r" : mode))};
        } catch (final IOException e) {
            return failure(e);
        }
    }

    @Callback(direct = true, limit = 32, doc = "function(handle:number) -- Closes an open handle.")
    public Object[] close(final Context context, final Arguments args) throws IOException {
        fileSystem.close(args.checkInteger(0));
        return null;
    }

    @Callback(direct = true, limit = 64, doc = "function(handle:number, count:number):string or nil -- Reads up to count bytes. Returns nil at end of file.")
    public Object[] read(final Context context, final Arguments args) throws IOException {
        final int handle = args.checkInteger(0);
        // Programs pass math.huge here, which is not an integer, so this has to go through the
        // double accessor before being clamped by the file system.
        final double count = args.checkDouble(1);
        final long limit = Double.isInfinite(count) || count > Long.MAX_VALUE
            ? Long.MAX_VALUE
            : (long) count;
        return new Object[]{fileSystem.read(handle, limit)};
    }

    @Callback(direct = true, limit = 64, doc = "function(handle:number, value:string):boolean -- Writes bytes to an open handle.")
    public Object[] write(final Context context, final Arguments args) {
        try {
            return new Object[]{fileSystem.write(args.checkInteger(0), args.checkByteArray(1))};
        } catch (final IOException e) {
            return failure(e);
        }
    }

    @Callback(direct = true, limit = 64, doc = "function(handle:number, whence:string, offset:number):number -- Moves a handle's position. Returns the new position.")
    public Object[] seek(final Context context, final Arguments args) throws IOException {
        return new Object[]{(double) fileSystem.seek(
            args.checkInteger(0), args.checkString(1), args.checkLong(2))};
    }

    ///////////////////////////////////////////////////////////////////

    private static Object[] failure(final IOException e) {
        final String message = e.getMessage();
        return new Object[]{null, message == null || message.isEmpty() ? "i/o error" : message};
    }
}
