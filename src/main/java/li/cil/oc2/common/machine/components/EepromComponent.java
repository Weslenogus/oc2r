/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * The {@code eeprom} component: the boot ROM the kernel loads before anything else exists.
 * <p>
 * Two independent areas. The code area holds the BIOS source, which {@code machine.lua} compiles
 * and runs; the data area is a small scratch space the BIOS uses to remember which filesystem it
 * booted from, so a machine comes back up on the same disk after a restart.
 * <p>
 * Both are byte arrays rather than strings. The code area is text in practice, but the data area
 * holds whatever the BIOS puts there, and round tripping arbitrary bytes through a string would
 * corrupt it.
 */
public final class EepromComponent extends AbstractLuaComponent {
    /**
     * Capacity of the code area, matching the OpenComputers 1 default. Comfortably more than the
     * stock BIOS needs and small enough to stay a ROM rather than a disk.
     */
    public static final int DEFAULT_SIZE = 4096;

    /**
     * Capacity of the data area, matching the OpenComputers 1 default. Sized for a boot address,
     * which is a 36 character UUID.
     */
    public static final int DEFAULT_DATA_SIZE = 256;

    private final int size;
    private final int dataSize;

    private byte[] code = new byte[0];
    private byte[] data = new byte[0];
    private String label = "EEPROM";
    private boolean isReadOnly;

    ///////////////////////////////////////////////////////////////////

    public EepromComponent(final String address) {
        this(address, DEFAULT_SIZE, DEFAULT_DATA_SIZE);
    }

    public EepromComponent(final String address, final int size, final int dataSize) {
        super("eeprom", address);
        this.size = size;
        this.dataSize = dataSize;
    }

    ///////////////////////////////////////////////////////////////////

    public byte[] getCode() {
        return code.clone();
    }

    public void setCode(final byte[] value) {
        code = truncate(value, size);
    }

    public void setCode(final String value) {
        setCode(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] getData() {
        return data.clone();
    }

    public void setData(final byte[] value) {
        data = truncate(value, dataSize);
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String value) {
        label = value;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public void setReadOnly(final boolean value) {
        isReadOnly = value;
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 8, doc = "function():string -- The code stored on this EEPROM.")
    public Object[] get(final Context context, final Arguments args) {
        return new Object[]{code.clone()};
    }

    @Callback(doc = "function(data:string) -- Overwrites the code stored on this EEPROM.")
    public Object[] set(final Context context, final Arguments args) {
        if (isReadOnly) {
            return new Object[]{null, "storage is readonly"};
        }
        final byte[] value = args.optByteArray(0, new byte[0]);
        if (value.length > size) {
            return new Object[]{null, "not enough space"};
        }
        code = value.clone();
        return null;
    }

    @Callback(direct = true, limit = 8, doc = "function():string -- The label of this EEPROM.")
    public Object[] getLabel(final Context context, final Arguments args) {
        return new Object[]{label};
    }

    @Callback(doc = "function(data:string):string -- Sets the label of this EEPROM. Returns the new label.")
    public Object[] setLabel(final Context context, final Arguments args) {
        if (isReadOnly) {
            return new Object[]{null, "storage is readonly"};
        }
        final String value = args.optString(0, "");
        // Truncated rather than rejected, matching how disk labels behave: a long name is a
        // cosmetic problem, not a reason to fail the call.
        label = value == null ? "" : value.substring(0, Math.min(16, value.length()));
        return new Object[]{label};
    }

    @Callback(direct = true, limit = 8, doc = "function():number -- The storage capacity of this EEPROM.")
    public Object[] getSize(final Context context, final Arguments args) {
        return new Object[]{size};
    }

    @Callback(direct = true, limit = 8, doc = "function():number -- The size of the data area of this EEPROM.")
    public Object[] getDataSize(final Context context, final Arguments args) {
        return new Object[]{dataSize};
    }

    @Callback(direct = true, limit = 8, doc = "function():string -- The data stored on this EEPROM.")
    public Object[] getData(final Context context, final Arguments args) {
        return new Object[]{data.clone()};
    }

    @Callback(doc = "function(data:string) -- Overwrites the data stored on this EEPROM.")
    public Object[] setData(final Context context, final Arguments args) {
        final byte[] value = args.optByteArray(0, new byte[0]);
        if (value.length > dataSize) {
            return new Object[]{null, "not enough space"};
        }
        // The data area stays writable on a read only EEPROM: that is where the boot address
        // lives, and a locked BIOS still has to be able to remember which disk it booted from.
        data = value.clone();
        return null;
    }

    @Callback(direct = true, limit = 8, doc = "function():string -- The checksum of the code stored on this EEPROM.")
    public Object[] getChecksum(final Context context, final Arguments args) {
        return new Object[]{checksum()};
    }

    @Callback(doc = "function(checksum:string):boolean -- Makes this EEPROM read only if the checksum matches.")
    public Object[] makeReadonly(final Context context, final Arguments args) {
        // Requiring the checksum makes this an acknowledgement rather than an accident: a program
        // has to have read the code it is about to lock in.
        if (!checksum().equals(args.checkString(0))) {
            return new Object[]{null, "incorrect checksum"};
        }
        isReadOnly = true;
        return new Object[]{true};
    }

    ///////////////////////////////////////////////////////////////////

    private String checksum() {
        final CRC32 crc = new CRC32();
        crc.update(code, 0, code.length);
        return String.format("%08x", crc.getValue());
    }

    private static byte[] truncate(final byte[] value, final int limit) {
        if (value.length <= limit) {
            return value.clone();
        }
        final byte[] result = new byte[limit];
        System.arraycopy(value, 0, result, 0, limit);
        return result;
    }
}
