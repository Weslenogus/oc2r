/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.blockentity;

import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.MachineHost;
import li.cil.oc2.common.block.LuaComputerBlock;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.energy.FixedEnergyStorage;
import li.cil.oc2.common.machine.components.CanvasCardComponent;
import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.FilesystemComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.KeyboardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.fs.RomFileSystem;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.MachineErrorScreen;
import li.cil.oc2.common.machine.screen.ScreenMode;
import li.cil.oc2.common.machine.serialization.FileSystemSerialization;
import li.cil.oc2.common.machine.serialization.MachineSerialization;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import java.util.Map;
import java.util.Set;
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
public final class LuaComputerBlockEntity extends ModBlockEntity
    implements TickableBlockEntity, MachineHost, LuaScreenView {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String BIOS_SCRIPT = "/assets/oc2r/lua/bios.lua";

    /**
     * Where the built in ROM lives. Every computer gets a copy of this as a read only filesystem,
     * and the shell on it is what a machine boots when its disk is still empty.
     */
    private static final String ROM_RESOURCE_ROOT = "/assets/oc2r/lua/rom";

    /**
     * The ROM's contents, read once and shared. Handles are not shared: each computer wraps this
     * image in its own file system, so two machines reading the ROM cannot close each other's
     * files.
     */
    private static final RomFileSystem.Image ROM_IMAGE = loadRom();

    /**
     * Public because the item form has to know which parts of a saved computer are the bulky ones,
     * so it can keep them off the network.
     */
    public static final String MACHINE_TAG_NAME = "machine";
    public static final String DISK_TAG_NAME = "disk";

    private static final String EEPROM_TAG_NAME = "eeprom";
    private static final String SCREEN_TAG_NAME = "screen";
    private static final String KEYBOARD_TAG_NAME = "keyboard";
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

    /**
     * The Tier 4 card. Present alongside the text card rather than instead of it, because a screen
     * shows one buffer at a time but a program may want both: a terminal to print to and a canvas
     * to draw on, switched between by drawing.
     */
    private final CanvasCardComponent canvas = new CanvasCardComponent(UUID.randomUUID().toString());

    /**
     * The computer's own display, the way the RISC-V computer has one.
     * <p>
     * Without it a placed computer shows nothing until a second block is put against it, and
     * nothing on the block says so - which reads as the machine being broken rather than as a
     * computer with no monitor. A {@link LuaScreenBlockEntity} is still an external display for
     * anyone who wants a bigger one, or one somewhere else.
     */
    private final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
    private final KeyboardComponent keyboard = new KeyboardComponent(UUID.randomUUID().toString());

    private final FilesystemComponent disk;
    private final FilesystemComponent tmpfs;

    /**
     * The built in shell, as a filesystem the BIOS can boot. Read only, and tried only after every
     * writable disk, so installing an operating system replaces it without anyone having to remove
     * anything.
     */
    private final FilesystemComponent rom = new FilesystemComponent(UUID.randomUUID().toString(),
        new RomFileSystem(ROM_IMAGE), "rom");
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

        keyboard.setScreen(screen);

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
    // LuaScreenView

    @Override
    public ScreenComponent getScreen() {
        return screen;
    }

    @Override
    public String getKeyboardAddress() {
        return keyboard.getComponentAddress();
    }

    @Override
    public BlockEntity getBlockEntity() {
        return this;
    }

    @Override
    public void signalMachines(final String name, final Object... args) {
        machine.signal(name, args);
    }

    @Override
    public boolean isMachineRunning() {
        // The client has no machine to ask, and reading the block state answers for both sides: it
        // carries the lit flag and is synchronized already.
        if (level != null && level.isClientSide()) {
            final BlockState state = getBlockState();
            return state.hasProperty(LuaComputerBlock.LIT) && state.getValue(LuaComputerBlock.LIT);
        }
        return machine.isRunning();
    }

    @Override
    public void setMachineRunning(final boolean value) {
        if (value) {
            start();
        } else {
            stop();
        }
    }

    public void sendFullSync(final ServerPlayer player) {
        LuaScreenSync.sendFullSync(this, player);
    }

    public void applyDeltaClient(final ScreenMode mode, final byte[] payload) {
        LuaScreenSync.applyDelta(this, mode, payload);
    }

    ///////////////////////////////////////////////////////////////////
    // MachineHost

    @Override
    public Collection<LuaComponent> getComponents() {
        components.clear();
        components.add(self);
        components.add(eeprom);
        components.add(gpu);
        components.add(canvas);
        components.add(screen);
        components.add(keyboard);
        components.add(disk);
        components.add(tmpfs);
        components.add(rom);

        if (level != null) {
            for (final Direction direction : Direction.values()) {
                if (level.getBlockEntity(getBlockPos().relative(direction))
                    instanceof final LuaScreenBlockEntity external) {
                    components.add(external.getScreen());
                    components.add(external.getKeyboard());
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
        // Its own setting, not the RISC-V computer's, and zero by default. This block has no
        // inventory screen and no energy bar, so a machine that stops the tick after it starts
        // because an invisible buffer is empty is indistinguishable from a machine that does not
        // work at all - which is exactly how it read.
        return Config.luaComputerEnergyPerTick;
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

        if (level == null || level.isClientSide()) {
            return;
        }

        // Put it where the player is looking. The log is the wrong place for "you have not
        // installed an operating system": the only symptom a player sees otherwise is a screen
        // that stays black, which reads as the mod being broken rather than as a machine with
        // nothing to boot.
        final String reason = message == null || message.isBlank() ? "unknown error" : message;

        // Its own screen first: that is the one a player is looking at if they have not placed
        // anything else.
        MachineErrorScreen.render(screen, "Machine stopped", reason,
            "Sneak and right click the computer to start it again.");

        for (final Direction direction : Direction.values()) {
            if (level.getBlockEntity(getBlockPos().relative(direction))
                instanceof final LuaScreenBlockEntity external) {
                MachineErrorScreen.render(external.getScreen(), "Machine stopped", reason,
                    "Sneak and right click the computer to start it again.");
            }
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void serverTick() {
        if (shouldRunAfterLoad) {
            shouldRunAfterLoad = false;
            machine.start();
        }
        machine.tick();

        // After the machine has had its turn, so anything it drew this tick goes out with it
        // rather than waiting for the next one.
        LuaScreenSync.tick(this);
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
        // Only the addresses. They have to survive a reload, because an operating system
        // remembers which screen and keyboard it bound to and would otherwise be talking to
        // components that no longer exist.
        //
        // Not the contents: the machine does not persist its Lua state, so it reboots and redraws,
        // and a whole screen buffer is tens of kilobytes written into the chunk on every save. It
        // would also leave the buffer marked dirty, which is a full resend to every client watching
        // the block each time the world saves.
        tag.putString(SCREEN_TAG_NAME, screen.getComponentAddress());
        tag.putString(KEYBOARD_TAG_NAME, keyboard.getComponentAddress());
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
        final String screenAddress = tag.getString(SCREEN_TAG_NAME);
        if (!screenAddress.isEmpty()) {
            screen.setComponentAddress(screenAddress);
        }
        final String keyboardAddress = tag.getString(KEYBOARD_TAG_NAME);
        if (!keyboardAddress.isEmpty()) {
            keyboard.setComponentAddress(keyboardAddress);
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

    private static RomFileSystem.Image loadRom() {
        try {
            return RomFileSystem.load(ROM_RESOURCE_ROOT);
        } catch (final IOException e) {
            // An empty ROM is still a working machine, just one with nothing to fall back on, so
            // this is logged rather than thrown: a mod that refuses to load because a resource is
            // missing is worse than one whose computers need a disk.
            LOGGER.error("Could not read the built in ROM.", e);
            return new RomFileSystem.Image(Map.of(), Set.of(), 0);
        }
    }

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
