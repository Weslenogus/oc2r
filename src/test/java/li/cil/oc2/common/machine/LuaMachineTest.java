/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.FilesystemComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.KeyboardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.fs.NioFileSystem;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.lua.LuaJArchitecture;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.TextBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end coverage of the OpenComputers 1 compatible Lua runtime.
 * <p>
 * These boot the real {@code bios.lua} through the real sandbox rather than exercising pieces in
 * isolation, because the parts most likely to break are the seams: a system yield bubbling out of
 * a nested coroutine, a direct call being promoted to a synchronized one, a binary file surviving
 * the trip through a Lua string.
 */
public class LuaMachineTest {
    private static String bios() throws IOException {
        try (final InputStream stream = LuaMachineTest.class
            .getResourceAsStream("/assets/oc2r/lua/bios.lua")) {
            assertNotNull(stream, "bios.lua is missing from the mod resources");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static EepromComponent eepromWith(final String code) {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
        eeprom.setCode(code);
        return eeprom;
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void bootsTheStockBiosAndDrivesATierThreeScreen(@TempDir final Path root) throws Exception {
        final Path disk = root.resolve("disk");
        Files.createDirectories(disk);
        Files.writeString(disk.resolve("init.lua"), """
            local gpu = component.proxy(component.list("gpu")())
            local screen = component.list("screen")()
            gpu.bind(screen)

            local w, h = gpu.getResolution()
            assert(w == 160 and h == 50, "resolution is " .. w .. "x" .. h)
            assert(gpu.getDepth() == 8, "depth is " .. gpu.getDepth())

            gpu.setPaletteColor(3, 0x336699)
            gpu.setBackground(0x000000)
            gpu.setForeground(3, true)
            gpu.set(3, 2, "Hello, MineOS!")

            local ch, fg, bg, fgIndex = gpu.get(3, 2)
            assert(ch == "H" and fg == 0x336699 and fgIndex == 3, "cell readback mismatch")

            gpu.copy(1, 2, 160, 1, 0, 4)
            assert(gpu.get(3, 6) == "H", "copy did not reach the destination")

            local fs = component.proxy(computer.getBootAddress())
            local handle = assert(fs.open("/data.bin"))
            local data = fs.read(handle, math.huge)
            fs.close(handle)
            assert(#data == 256, "read " .. #data .. " bytes")
            assert(data:byte(1) == 0 and data:byte(256) == 255, "binary data was mangled")

            local tmp = component.proxy(computer.tmpAddress())
            local out = assert(tmp.open("/scratch/note.txt", "w"))
            tmp.write(out, "written from lua")
            tmp.close(out)
            local back = assert(tmp.open("/scratch/note.txt"))
            assert(tmp.read(back, math.huge) == "written from lua", "tmpfs round trip failed")
            tmp.close(back)

            local name, address, x, y
            repeat
              name, address, x, y = computer.pullSignal(10)
            until name == "touch" or name == nil
            assert(name == "touch" and address == screen, "wrong signal")
            gpu.setForeground(0xFFFFFF)
            gpu.set(1, 20, string.format("touch %d,%d", x, y))

            computer.shutdown()
            """, StandardCharsets.UTF_8);

        final byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        Files.write(disk.resolve("data.bin"), binary);

        final TestMachineHost host = new TestMachineHost();
        final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
        final GraphicsCardComponent gpu = new GraphicsCardComponent(UUID.randomUUID().toString());
        final KeyboardComponent keyboard = new KeyboardComponent(UUID.randomUUID().toString());
        keyboard.setScreen(screen);

        final FilesystemComponent tmpfs = new FilesystemComponent(
            UUID.randomUUID().toString(), new RamFileSystem(1 << 18), "tmpfs");

        host.add(eepromWith(bios()));
        host.add(screen);
        host.add(gpu);
        host.add(keyboard);
        host.add(new FilesystemComponent(
            UUID.randomUUID().toString(), new NioFileSystem(disk, false, 1 << 20), "boot"));
        host.add(tmpfs);
        host.setTmpAddress(tmpfs.getComponentAddress());

        final LuaMachine machine = new LuaMachine(host);
        host.add(new ComputerComponent(machine.getAddress()));
        machine.start();

        final boolean[] signalled = {false};
        TestMachineHost.run(machine, 600, () -> {
            // The queue starts full of component_added signals; wait for the program to work
            // through them before adding one it is actually waiting for.
            if (!signalled[0]
                && machine.getState() == LuaMachine.State.SLEEPING
                && machine.getSignalQueue().isEmpty()) {
                machine.signal("touch", screen.getComponentAddress(), 42.0, 7.0, 0.0, "Steve");
                signalled[0] = true;
            }
        });

        assertEquals(List.of(), host.getCrashes());
        assertTrue(signalled[0], "the machine never reached pullSignal");

        final TextBuffer buffer = screen.getBuffer();
        assertEquals(160, buffer.getWidth());
        assertEquals(50, buffer.getHeight());
        assertTrue(row(buffer, 5).startsWith("  Hello, MineOS!"), "copy did not land on row 6");
        assertTrue(row(buffer, 1).startsWith("  Hello, MineOS!"), "copy must not clear its source");
        assertEquals(3, buffer.getForegroundPaletteIndexAt(2, 5), "palette index lost through copy");
        assertEquals(0x336699, buffer.getForegroundAt(2, 5), "palette colour lost through copy");
        assertTrue(row(buffer, 19).contains("touch 42,7"), "the touch signal was not rendered");

        // The stock BIOS beeps once when it finds something to boot.
        assertEquals(List.of("1000Hz"), host.getBeeps());
    }

    @Test
    void reportsMissingBootMediumRatherThanHanging() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith(bios()));

        final LuaMachine machine = new LuaMachine(host);
        machine.start();
        TestMachineHost.run(machine, 60);

        assertFalse(machine.isRunning());
        assertEquals(1, host.getCrashes().size());
        assertTrue(host.getCrashes().get(0).contains("no bootable medium"),
            "unexpected error: " + host.getCrashes().get(0));
    }

    @Test
    void promotesDirectCallsToSynchronizedOnceTheirBudgetIsSpent() throws Exception {
        final CallCounter counter = new CallCounter();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("counter")()
            for i = 1, 12 do component.invoke(address, "tick") end
            computer.shutdown()
            """));
        host.add(counter);

        final LuaMachine machine = new LuaMachine(host);
        machine.start();
        TestMachineHost.run(machine, 120);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(12, counter.direct + counter.synchronized_, "some calls went missing");
        assertTrue(counter.synchronized_ > 0,
            "exceeding the per tick limit should have promoted calls to the synchronized path");
        assertTrue(counter.direct > 0, "calls within the limit should have stayed direct");
    }

    @Test
    void preemptsATightLoopInsteadOfBlockingTheHost() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local total = 0
            for i = 1, 2000000 do total = total + i end
            local address = component.list("counter")()
            component.invoke(address, "tick")
            computer.shutdown()
            """));
        final CallCounter counter = new CallCounter();
        host.add(counter);

        // A one millisecond slice makes this deterministic. Timing the default 50ms budget against
        // a loop of some fixed length would only be measuring how fast the machine running the
        // test is, and would pass or fail accordingly.
        final LuaMachine machine = new LuaMachine(host, UUID.randomUUID().toString(),
            m -> new LuaJArchitecture(m, TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.SECONDS.toNanos(30)));
        machine.start();
        final int ticks = TestMachineHost.run(machine, 6000);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(1, counter.direct + counter.synchronized_, "the loop never finished");
        // The loop cannot fit in a millisecond, so the hook must have preempted it repeatedly and
        // the machine must have needed many ticks to work through it.
        assertTrue(ticks > 10, "the loop was not preempted; it ran in " + ticks + " tick(s)");
    }

    @Test
    void nestedCoroutinesBubbleSystemYieldsButKeepUserYieldsLocal() throws Exception {
        final Recorder recorder = new Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local co = coroutine.create(function()
              -- A system yield from in here has to reach Java and come back with the signal.
              local name = computer.pullSignal(5)
              note("inner saw " .. tostring(name))
              -- A user yield must stop at our resume instead of reaching Java.
              coroutine.yield("from inner")
              return "finished"
            end)

            local ok, value = coroutine.resume(co)
            note("outer got " .. tostring(value))
            local ok2, value2 = coroutine.resume(co)
            note("outer got " .. tostring(value2))
            note("status " .. coroutine.status(co))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = new LuaMachine(host);
        machine.start();
        TestMachineHost.run(machine, 300);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(4, recorder.notes.size(), "recorded: " + recorder.notes);
        assertTrue(recorder.notes.get(0).startsWith("inner saw component_added"),
            "the inner coroutine did not receive a signal: " + recorder.notes.get(0));
        assertEquals("outer got from inner", recorder.notes.get(1));
        assertEquals("outer got finished", recorder.notes.get(2));
        assertEquals("status dead", recorder.notes.get(3));
    }

    @Test
    void killsAMachineThatNeverYields() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        // Wrapped in pcall, and re-entered forever, which is the shape that would defeat a plain
        // error. The hook yields instead, and a yield is not something pcall can catch.
        host.add(eepromWith("while true do pcall(function() while true do end end) end"));

        // One second of accumulated execution rather than the default five, so the test does not
        // spend five seconds proving a point it can prove in one.
        final LuaMachine machine = new LuaMachine(host, UUID.randomUUID().toString(),
            m -> new LuaJArchitecture(m, TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.SECONDS.toNanos(1)));
        machine.start();
        TestMachineHost.run(machine, 2000);

        assertFalse(machine.isRunning(), "a machine that never yields must be stopped");
        assertEquals(1, host.getCrashes().size());
        assertTrue(host.getCrashes().get(0).contains("too long without yielding"),
            "unexpected error: " + host.getCrashes().get(0));
    }

    @Test
    void deniesTheSandboxAccessToTheHost() throws Exception {
        final Recorder recorder = new Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            for _, name in ipairs({"io", "package", "require", "dofile", "loadfile", "print",
                                   "luajava", "os.execute", "os.exit", "os.remove", "debug.sethook",
                                   "debug.getregistry", "debug.setupvalue"}) do
              local target = _G
              for part in name:gmatch("[^.]+") do
                target = type(target) == "table" and target[part] or nil
              end
              if target ~= nil then note("leaked " .. name) end
            end

            note("done")
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = new LuaMachine(host);
        machine.start();
        TestMachineHost.run(machine, 120);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of("done"), recorder.notes,
            "the sandbox exposes something it should not");
    }

    @Test
    void aChunkWithItsOwnEnvironmentStillGetsPreempted() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        final CallCounter counter = new CallCounter();
        host.add(eepromWith("""
            -- An operating system runs its programs with their own environment. If that were
            -- enough to escape the CPU limiter, every program would simply do it.
            local chunk = assert(load("local t = 0 for i = 1, 2000000 do t = t + i end", "=p", "t", {}))
            chunk()
            component.invoke(component.list("counter")(), "tick")
            computer.shutdown()
            """));
        host.add(counter);

        final LuaMachine machine = new LuaMachine(host, UUID.randomUUID().toString(),
            m -> new LuaJArchitecture(m, TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.SECONDS.toNanos(30)));
        machine.start();
        final int ticks = TestMachineHost.run(machine, 6000);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(1, counter.direct + counter.synchronized_, "the chunk never finished");
        assertTrue(ticks > 10, "a chunk with its own environment escaped preemption; it ran in "
            + ticks + " tick(s)");
    }

    @Test
    void aCustomEnvironmentBehavesLikeAnOrdinaryTable() throws Exception {
        final Recorder recorder = new Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local env = {seed = 1}
            local chunk = assert(load("answer = seed + 41 return answer", "=p", "t", env))
            note("returned " .. tostring(chunk()))

            -- The environment table is the storage, so the value has to be visible through it and
            -- through every raw accessor, not just through the chunk.
            note("index " .. tostring(env.answer))
            note("rawget " .. tostring(rawget(env, "answer")))

            local keys = {}
            for k in pairs(env) do keys[#keys + 1] = k end
            table.sort(keys)
            note("pairs " .. table.concat(keys, ","))

            -- Loading a second chunk against the same table must reuse the same wrapper, or the
            -- two would stop seeing each other's globals.
            local other = assert(load("return answer", "=q", "t", env))
            note("shared " .. tostring(other()))

            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = new LuaMachine(host);
        machine.start();
        TestMachineHost.run(machine, 300);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of(
            "returned 42",
            "index 42",
            "rawget 42",
            "pairs answer,seed",
            "shared 42"), recorder.notes);
    }

    ///////////////////////////////////////////////////////////////////

    private static String row(final TextBuffer buffer, final int y) {
        final StringBuilder builder = new StringBuilder(buffer.getWidth());
        for (int x = 0; x < buffer.getWidth(); x++) {
            final char value = buffer.getRawChar(x, y);
            builder.append(value == TextBuffer.WIDE_CHAR_CONTINUATION ? ' ' : value);
        }
        return builder.toString();
    }

    /**
     * Counts how a call reached it, which is what makes the direct call budget observable.
     */
    public static final class CallCounter implements LuaComponent {
        private final String address = UUID.randomUUID().toString();
        int direct;
        int synchronized_;

        @Override
        public String getComponentType() {
            return "counter";
        }

        @Override
        public String getComponentAddress() {
            return address;
        }

        @Callback(direct = true, limit = 5)
        public Object[] tick(final Context context, final Arguments args) {
            if (context.isSynchronized()) {
                synchronized_++;
            } else {
                direct++;
            }
            return null;
        }
    }

    public static final class Recorder implements LuaComponent {
        private final String address = UUID.randomUUID().toString();
        final List<String> notes = new ArrayList<>();

        @Override
        public String getComponentType() {
            return "recorder";
        }

        @Override
        public String getComponentAddress() {
            return address;
        }

        @Callback(direct = true, limit = 1024)
        public Object[] note(final Context context, final Arguments args) {
            notes.add(args.checkString(0));
            return null;
        }
    }
}
