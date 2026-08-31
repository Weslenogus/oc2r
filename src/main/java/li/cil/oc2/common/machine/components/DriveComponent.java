/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;

/**
 * The unmanaged {@code drive} component: a flat array of bytes with no filesystem on top.
 * <p>
 * Where {@link FilesystemComponent} hands a machine files, this hands it sectors and lets the
 * operating system decide what a file is. That is what makes custom filesystems possible, and it
 * is the mode a partition editor or a disk imaging tool needs.
 * <p>
 * Sector numbers are one based and offsets into {@code readByte} are one based too, matching
 * OpenComputers 1. Getting that wrong would not fail loudly, it would corrupt the first byte of
 * every sector, so the conversion happens in exactly one place here.
 */
public final class DriveComponent extends AbstractLuaComponent {
    /**
     * Bytes per sector. Fixed at 512, as in OpenComputers 1, because on-disk formats written by
     * existing software assume it.
     */
    public static final int SECTOR_SIZE = 512;

    private final byte[] data;
    private final int platterCount;
    private String label;

    ///////////////////////////////////////////////////////////////////

    public DriveComponent(final String address, final byte[] data, final int platterCount, final String label) {
        super("drive", address);
        this.data = data;
        this.platterCount = Math.max(1, platterCount);
        this.label = label;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The backing storage. Handed out directly so the host can persist it without copying a disk
     * image on every save.
     */
    public byte[] getData() {
        return data;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String value) {
        label = value;
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 16, doc = "function():string -- The label of the drive.")
    public Object[] getLabel(final Context context, final Arguments args) {
        return new Object[]{label};
    }

    @Callback(doc = "function(value:string):string -- Sets the label of the drive. Returns the new label.")
    public Object[] setLabel(final Context context, final Arguments args) {
        final String value = args.optString(0, "");
        label = value == null ? "" : value.substring(0, Math.min(16, value.length()));
        return new Object[]{label};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The number of platters in the drive.")
    public Object[] getPlatterCount(final Context context, final Arguments args) {
        return new Object[]{platterCount};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The total capacity of the drive, in bytes.")
    public Object[] getCapacity(final Context context, final Arguments args) {
        return new Object[]{data.length};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The size of a single sector, in bytes.")
    public Object[] getSectorSize(final Context context, final Arguments args) {
        return new Object[]{SECTOR_SIZE};
    }

    @Callback(direct = true, limit = 128, doc = "function(offset:number):number -- Reads a single byte. Offsets are one based.")
    public Object[] readByte(final Context context, final Arguments args) {
        final int offset = checkOffset(args.checkInteger(0));
        // Signed, as in Lua: a byte read back and written straight out again must round trip.
        return new Object[]{(int) data[offset]};
    }

    @Callback(direct = true, limit = 128, doc = "function(offset:number, value:number) -- Writes a single byte. Offsets are one based.")
    public Object[] writeByte(final Context context, final Arguments args) {
        final int offset = checkOffset(args.checkInteger(0));
        data[offset] = (byte) args.checkInteger(1);
        return null;
    }

    @Callback(direct = true, limit = 32, doc = "function(sector:number):string -- Reads a whole sector. Sectors are one based.")
    public Object[] readSector(final Context context, final Arguments args) {
        final int start = checkSector(args.checkInteger(0));
        final byte[] result = new byte[SECTOR_SIZE];
        System.arraycopy(data, start, result, 0, SECTOR_SIZE);
        return new Object[]{result};
    }

    @Callback(direct = true, limit = 32, doc = "function(sector:number, value:string) -- Writes a whole sector. Sectors are one based.")
    public Object[] writeSector(final Context context, final Arguments args) {
        final int start = checkSector(args.checkInteger(0));
        final byte[] value = args.checkByteArray(1);

        // A short write pads with zeroes rather than leaving the tail of the old sector behind.
        // Anything else would let a program read back data it never wrote.
        final int count = Math.min(value.length, SECTOR_SIZE);
        System.arraycopy(value, 0, data, start, count);
        java.util.Arrays.fill(data, start + count, start + SECTOR_SIZE, (byte) 0);
        return null;
    }

    ///////////////////////////////////////////////////////////////////

    private int checkOffset(final int oneBasedOffset) {
        final int offset = oneBasedOffset - 1;
        if (offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException("index out of bounds");
        }
        return offset;
    }

    private int checkSector(final int oneBasedSector) {
        final int sector = oneBasedSector - 1;
        final int start = sector * SECTOR_SIZE;
        if (sector < 0 || start + SECTOR_SIZE > data.length) {
            throw new IllegalArgumentException("index out of bounds");
        }
        return start;
    }
}
