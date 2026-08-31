/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.MachineHost;
import li.cil.oc2.common.block.LuaComputerBlock;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.energy.FixedEnergyStorage;
import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.FilesystemComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.serialization.FileSystemSerialization;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A computer running the OpenComputers 1 compatible Lua runtime.
 * <p>
 * Deliberately not a variant of {@link ComputerBlockEntity}: that one boots Linux on a RISC-V core
 * and has an inventory of cards to match. This one is a fixed configuration, which is what makes it
 * placeable and usable without a container: a Tier 3 graphics card, an EEPROM carrying the stock
 * BIOS, a megabyte of disk and a temporary filesystem. Screens and their keyboards come from the
 * blocks around it.
 * <p>
 * The disk lives in this block entity's tag rather than in the world save directory. That bounds it
 * to something a region file can reasonably hold, and it means breaking the block takes the disk
 * with it, which is the behaviour a player expects from a computer they just mined.
 */
public final class LuaComputerBlockEntity extends ModBlockEntity implements TickableBlockEntity, MachineHost {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String BIOS_SCRIPT = "/assets/oc2r/lua/bios.lua";

    private static final String MACHINE_TAG_NAME = "machine";
    private static final String EEPROM_TAG_NAME = "eeprom";
    private static final String DISK_TAG_NAME = "disk";
    private static final String DISK_ADDRESS_TAG_NAME = "diskAddress";
    private static final String ENERGY_TAG_NAME = "energy";

    /**
     * Share of the disk's capacity the temporary filesystem gets. An operating system unpacks
     * downloads and holds working files there, so it has to scale with the disk rather than sit at
     * some fixed figure that was generous when disks were a megabyte.
     */
    private static final int TMPFS_FRACTION = 8;

    /**
     * Floor under the above, so a server that has configured a tiny disk still leaves room for the
     * scratch space every operating system assumes exists.
     */
    private static final int MIN_TMPFS_CAPACITY = 256 * 1024;

    ///////////////////////////////////////////////////////////////////

    private final FixedEnergyStorage energy = new FixedEnergyStorage(Config.computerEnergyStorage);

    private final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
    private final GraphicsCardComponent gpu = new GraphicsCardComponent(UUID.randomUUID().toString());
    private final FilesystemComponent disk;
    private final FilesystemComponent tmpfs;
    private final ComputerComponent self;
    private final LuaMachine machine;

    /**
     * Reused across scans so component identity stays stable, which is what lets the bus tell an
     * unchanged neighbour from a new one.
     */
    private final List<LuaComponent> components = new ArrayList<>();

    private boolean shouldRunAfterLoad;

    ///////////////////////////////////////////////////////////////////

    public LuaComputerBlockEntity(final BlockPos pos, final BlockState state) {
        super(BlockEntities.LUA_COMPUTER.get(), pos, state);

        // Both grow as they are used rather than up front, so these are ceilings and not a cost.
        final int diskCapacity = Config.luaMaxDiskSize;
        disk = new FilesystemComponent(UUID.randomUUID().toString(),
            new RamFileSystem(diskCapacity), "disk");
        tmpfs = new FilesystemComponent(UUID.randomUUID().toString(),
            new RamFileSystem(Math.max(MIN_TMPFS_CAPACITY, diskCapacity / TMPFS_FRACTION)), "tmpfs");

        machine = new LuaMachine(this);
        self = new ComputerComponent(machine.getAddress());

        eeprom.setCode(loadDefaultBios());
        eeprom.setLabel("BIOS");
    }

    ///////////////////////////////////////////////////////////////////

    public LuaMachine getMachine() {
        return machine;
    }

    public boolean isRunning() {
        return machine.isRunning();
    }

    public void start() {
        machine.start();
    }

    public void stop() {
        machine.stop();
    }

    ///////////////////////////////////////////////////////////////////
    // MachineHost

    @Override
    public Collection<LuaComponent> getComponents() {
        components.clear();
        components.add(self);
        components.add(eeprom);
        components.add(gpu);
        components.add(disk);
        components.add(tmpfs);

        if (level != null) {
            for (final Direction direction : Direction.values()) {
                if (level.getBlockEntity(getBlockPos().relative(direction))
                    instanceof final LuaScreenBlockEntity screen) {
                    components.add(screen.getScreen());
                    components.add(screen.getKeyboard());
                }
            }
        }

        return components;
    }

    @Override
    public int getMemorySize() {
        // OpenComputers 1 tops out at two Tier 3.5 sticks, about 3.5MB, and MineOS asks for that
        // maximum. Measured here, MineOS's libraries take 1.4MB just to compile, before any of
        // them has run, so anything near that leaves a computer which boots and then runs out of
        // memory doing anything. There is no container to upgrade, so the configuration has to be
        // the one that works; the server owner decides how much further to go.
        return Config.luaRam();
    }

    @Override
    public int getCpuTimeoutMillis() {
        return Config.luaCpuTimeoutMs;
    }

    @Override
    public int getCpuSliceMillis() {
        return Config.luaCpuSliceMs;
    }

    @Override
    public int getDirectCallsPerTickFactor() {
        return Config.luaDirectCallsPerTickFactor;
    }

    @Override
    public double getEnergyStored() {
        return energy.getEnergyStored();
    }

    @Override
    public double getEnergyCapacity() {
        return energy.getMaxEnergyStored();
    }

    @Override
    public double getEnergyPerTick() {
        return Config.computerEnergyPerTick;
    }

    @Override
    public boolean tryConsumeEnergy(final double amount) {
        final int needed = (int) Math.ceil(amount);
        if (needed <= 0) {
            return true;
        }
        // Simulate first: a partial draw would leave the machine half paid for, and it has to be
        // able to decline the tick cleanly.
        if (energy.extractEnergy(needed, true) < needed) {
            return false;
        }
        energy.extractEnergy(needed, false);
        return true;
    }

    @Override
    public String getTmpAddress() {
        return tmpfs.getComponentAddress();
    }

    @Override
    public void beep(final int frequency, final double duration) {
        if (level == null || level.isClientSide()) {
            return;
        }
        level.playSound(null, getBlockPos(),
            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(),
            net.minecraft.sounds.SoundSource.BLOCKS,
            0.5f,
            // Note blocks span two octaves; map the requested frequency onto that range rather
            // than pretending arbitrary pitches are available.
            (float) Math.max(0.5, Math.min(2.0, frequency / 1000.0)));
    }

    @Override
    public void onMachineRunStateChanged(final boolean isRunning) {
        // isValid() matters here: the machine is also stopped while the chunk is being unloaded,
        // and setting a block from inside that would be writing to a level that is on its way out.
        if (level == null || level.isClientSide() || !isValid()) {
            return;
        }
        level.setBlockAndUpdate(getBlockPos(),
            getBlockState().setValue(LuaComputerBlock.LIT, isRunning));
        setChanged();
    }

    @Override
    public void onMachineCrashed(@Nullable final String message) {
        LOGGER.debug("Lua computer at {} stopped: {}", getBlockPos(), message);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void serverTick() {
        if (shouldRunAfterLoad) {
            shouldRunAfterLoad = false;
            machine.start();
        }
        machine.tick();
    }

    @Override
    protected void loadServer() {
        super.loadServer();
        setNeedsLevelUnloadEvent();
    }

    @Override
    protected void unloadServer(final boolean isRemove) {
        super.unloadServer(isRemove);
        // Stopping releases the Lua state and lets its coroutine threads unwind. Leaving them
        // parked for a block that no longer exists is how a server slowly fills up with threads.
        machine.stop();
    }

    @Override
    protected void collectCapabilities(final CapabilityCollector collector, @Nullable final Direction direction) {
        collector.offer(ForgeCapabilities.ENERGY, energy);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected void saveAdditional(final CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(MACHINE_TAG_NAME, MachineSerialization.serialize(machine));
        tag.put(EEPROM_TAG_NAME, MachineSerialization.serialize(eeprom));
        tag.put(DISK_TAG_NAME, FileSystemSerialization.serialize(disk.getFileSystem()));
        tag.putString(DISK_ADDRESS_TAG_NAME, disk.getComponentAddress());
        tag.put(ENERGY_TAG_NAME, energy.serializeNBT());
    }

    @Override
    public void load(final CompoundTag tag) {
        super.load(tag);

        if (tag.contains(EEPROM_TAG_NAME, NBTTagIds.TAG_COMPOUND)) {
            MachineSerialization.deserialize(tag.getCompound(EEPROM_TAG_NAME), eeprom);
        }
        if (tag.contains(DISK_TAG_NAME, NBTTagIds.TAG_COMPOUND)) {
            FileSystemSerialization.deserialize(tag.getCompound(DISK_TAG_NAME), disk.getFileSystem());
        }

        final String diskAddress = tag.getString(DISK_ADDRESS_TAG_NAME);
        if (!diskAddress.isEmpty()) {
            // The BIOS writes this address into the EEPROM when it finds something to boot, so
            // losing it would mean the machine no longer recognizes its own disk.
            disk.setComponentAddress(diskAddress);
        }

        if (tag.contains(ENERGY_TAG_NAME)) {
            energy.deserializeNBT(tag.get(ENERGY_TAG_NAME));
        }
        if (tag.contains(MACHINE_TAG_NAME, NBTTagIds.TAG_COMPOUND)) {
            // Deferred to the first tick: starting here would run before the level is in a state
            // where neighbouring screens can be found.
            shouldRunAfterLoad = MachineSerialization.deserialize(tag.getCompound(MACHINE_TAG_NAME), machine);
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static String loadDefaultBios() {
        try (final InputStream stream = LuaComputerBlockEntity.class.getResourceAsStream(BIOS_SCRIPT)) {
            if (stream == null) {
                LOGGER.error("Missing default BIOS at [{}].", BIOS_SCRIPT);
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.error("Could not read the default BIOS.", e);
            return "";
        }
    }

    ///////////////////////////////////////////////////////////////////

    public IEnergyStorage getEnergy() {
        return energy;
    }
}
