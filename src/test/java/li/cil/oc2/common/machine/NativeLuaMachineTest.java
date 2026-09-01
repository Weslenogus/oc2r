/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.FilesystemComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.fs.NioFileSystem;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.lua.LuaArchitectures;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.lua.NativeLuaArchitecture;
import li.cil.oc2.common.machine.screen.TextBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same runtime as {@link LuaMachineTest}, on real Lua 5.3.
 * <p>
 * The point of this backend is the things the Java one cannot do, so that is what is covered here:
 * compiling the chunk shape that stops MineOS, reporting 5.3 so programs take their 5.3 code path,
 * and carrying arbitrary bytes across the boundary in both directions, which is not free when the
 * bridge underneath re-encodes every string it is handed.
 * <p>
 * These skip rather than fail where the natives will not load, because that is a supported state:
 * the machine falls back to the Java backend and the tests for it still run. A skip here means
 * this platform is on the fallback.
 */
public class NativeLuaMachineTest {
    @BeforeEach
    void requireNativeLua() {
        assumeTrue(NativeLuaArchitecture.isAvailable(), "native Lua is not available here");
    }

    private static String bios() throws IOException {
        try (final InputStream stream = NativeLuaMachineTest.class
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

    private static LuaMachine native_(final TestMachineHost host) {
        return new LuaMachine(host, UUID.randomUUID().toString(), NativeLuaArchitecture::new);
    }

    private static LuaMachine native_(final TestMachineHost host, final long deadlineMillis) {
        return new LuaMachine(host, UUID.randomUUID().toString(),
            machine -> new NativeLuaArchitecture(machine, TimeUnit.MILLISECONDS.toNanos(deadlineMillis)));
    }

    private static String row(final TextBuffer buffer, final int y) {
        final StringBuilder builder = new StringBuilder();
        for (int x = 0; x < buffer.getWidth(); x++) {
            builder.append(buffer.getRawChar(x, y));
        }
        return builder.toString().stripTrailing();
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void reportsFiveThreeSoProgramsTakeTheirFastPath() throws Exception {
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            note("version " .. _VERSION)
            note("arch " .. tostring(computer.getArchitecture()))
            note("list " .. table.concat(computer.getArchitectures(), ","))
            note("set-known " .. tostring(computer.setArchitecture("Lua 5.3")))
            note("set-unknown " .. tostring(computer.setArchitecture("Lua 5.2")))

            -- The shape MineOS's colour library uses to pick a code path. Here it has to take the
            -- fast path, and that path has to actually compile.
            if computer.getArchitecture and computer.getArchitecture() ~= "Lua 5.2" then
              local fast = assert(load("local a, b = ... return (a << 4) | (b & 0xF), a // b"))
              note("fast path " .. table.concat({fast(3, 2)}, ","))
            else
              note("took the 5.2 fallback")
            end

            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 120);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of(
            "version Lua 5.3",
            "arch Lua 5.3",
            "list Lua 5.3",
            "set-known true",
            "set-unknown nil",
            "fast path 50,1"), recorder.notes);
    }

    @Test
    void compilesTheChunkShapeThatStopsTheJavaBackend() throws Exception {
        // The counterpart to LuaMachineTest.documentsTheParserLimitThatBlocksMineOS: the same
        // chunk, which LuaJ rejects because it counts locals across every enclosing function, and
        // which the standard compiler accepts because it subtracts the enclosing function's base.
        final StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            nested.append("local a").append(i).append(" = ").append(i).append('\n');
        }
        nested.append("local f = function()\n");
        for (int i = 0; i < 60; i++) {
            nested.append("  local b").append(i).append(" = ").append(i).append('\n');
        }
        nested.append("  return b0\nend\nreturn f() + a0\n");

        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith(
            "local address = component.list(\"recorder\")()\n"
                + "local chunk, reason = load([==[\n" + nested + "]==], \"=nested\", \"t\")\n"
                + "component.invoke(address, \"note\", chunk and (\"compiled \" .. chunk())\n"
                + "  or (\"rejected: \" .. tostring(reason)))\n"
                + "computer.shutdown()\n"));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 120);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of("compiled 0"), recorder.notes);
    }

    @Test
    void carriesArbitraryBytesInBothDirections(@TempDir final Path root) throws Exception {
        // A Lua string is a byte string, and the JNI bridge underneath is not: handing it a Java
        // string gets modified UTF-8 back out, which would corrupt every byte above 0x7F. This is
        // the test that the escaping path around that actually holds.
        final Path disk = root.resolve("disk");
        Files.createDirectories(disk);

        final byte[] every = new byte[256];
        for (int i = 0; i < every.length; i++) {
            every[i] = (byte) i;
        }
        Files.write(disk.resolve("data.bin"), every);

        Files.writeString(disk.resolve("init.lua"), """
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local fs = component.proxy(computer.getBootAddress())
            local handle = assert(fs.open("/data.bin"))
            local data = fs.read(handle, math.huge)
            fs.close(handle)
            note("read " .. #data .. " " .. data:byte(1) .. "," .. data:byte(129) .. "," .. data:byte(256))

            -- Back out through a component call and in again, which exercises the push direction.
            local tmp = component.proxy(computer.tmpAddress())
            local out = assert(tmp.open("/copy.bin", "w"))
            tmp.write(out, data)
            tmp.close(out)
            local back = assert(tmp.open("/copy.bin"))
            local copy = tmp.read(back, math.huge)
            tmp.close(back)
            note("round trip " .. tostring(copy == data))

            -- And text, which takes the cheap path only while it stays inside ASCII.
            local gpu = component.proxy(component.list("gpu")())
            local screen = component.list("screen")()
            gpu.bind(screen)
            gpu.set(1, 1, "hello \\u{e9} \\u{2713} \\u{65e5}")
            note("glyphs " .. gpu.get(1, 1) .. gpu.get(7, 1) .. gpu.get(9, 1) .. gpu.get(11, 1))
            note("unicode.len " .. unicode.len("hello \\u{e9} \\u{2713} \\u{65e5}"))

            computer.shutdown()
            """, StandardCharsets.UTF_8);

        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
        final FilesystemComponent tmpfs = new FilesystemComponent(
            UUID.randomUUID().toString(), new RamFileSystem(1 << 18), "tmpfs");

        host.add(eepromWith(bios()));
        host.add(recorder);
        host.add(screen);
        host.add(new GraphicsCardComponent(UUID.randomUUID().toString()));
        host.add(new FilesystemComponent(
            UUID.randomUUID().toString(), new NioFileSystem(disk, false, 1 << 20), "boot"));
        host.add(tmpfs);
        host.setTmpAddress(tmpfs.getComponentAddress());

        final LuaMachine machine = native_(host);
        host.add(new ComputerComponent(machine.getAddress()));
        machine.start();
        TestMachineHost.run(machine, 300);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of(
            "read 256 0,128,255",
            "round trip true",
            "glyphs h\u00e9\u2713\u65e5",
            "unicode.len 11"), recorder.notes);
        assertEquals('\u65e5', screen.getBuffer().getRawChar(10, 0));
    }

    @Test
    void killsAMachineThatNeverYields() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("while true do end"));

        final LuaMachine machine = native_(host, 300);
        machine.start();
        TestMachineHost.run(machine, 200);

        assertFalse(machine.isRunning());
        assertEquals(List.of("too long without yielding"), host.getCrashes());
    }

    @Test
    void theDeadlineCannotBePcalledAway() throws Exception {
        // The hook raises rather than yields here, because a Lua hook runs inside a C call and Lua
        // will not yield across one. What makes the error inescapable is that the hook re-arms
        // itself to fire on every instruction, so the handler cannot get anywhere either.
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("while true do pcall(function() while true do end end) end"));

        final LuaMachine machine = native_(host, 300);
        machine.start();
        TestMachineHost.run(machine, 200);

        assertFalse(machine.isRunning());
        assertEquals(List.of("too long without yielding"), host.getCrashes());
    }

    @Test
    void deniesTheSandboxAccessToTheHost() throws Exception {
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            -- java is the binding's own interop table: java.import("java.lang.Runtime") would be
            -- a way straight out of the sandbox and into the host.
            for _, name in ipairs{"java", "luajava", "io", "package", "require", "dofile",
                                  "loadfile", "print", "module"} do
              if _G[name] ~= nil then note("leaked " .. name) end
            end

            local keys = {}
            for key in pairs(debug) do keys[#keys + 1] = key end
            table.sort(keys)
            note("debug " .. table.concat(keys, ","))

            keys = {}
            for key in pairs(os) do keys[#keys + 1] = key end
            table.sort(keys)
            note("os " .. table.concat(keys, ","))

            note("string.dump " .. tostring(string.dump))
            note("binary chunks " .. tostring(load(string.rep("\\27", 4) .. "Lua", "=b", "b")))

            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 120);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of(
            "debug getinfo,traceback",
            "os clock,date,difftime,time",
            "string.dump nil",
            "binary chunks nil"), recorder.notes);
    }

    @Test
    void bubblesSystemYieldsOutOfNestedCoroutines() throws Exception {
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local inner = coroutine.create(function()
              -- A system yield from two levels down has to reach Java, not the resume above it.
              computer.pullSignal(0)
              coroutine.yield("user yield")
              return "returned"
            end)

            local outer = coroutine.create(function()
              note("first " .. tostring(select(2, coroutine.resume(inner))))
              note("second " .. tostring(select(2, coroutine.resume(inner))))
              return "outer done"
            end)

            note("outer " .. tostring(select(2, coroutine.resume(outer))))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 200);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of("first user yield", "second returned", "outer outer done"),
            recorder.notes);
    }

    @Test
    void reportsRealMemoryUse() throws Exception {
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.setMemorySize(16 * 1024 * 1024);
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local before = computer.freeMemory()
            local hog = {}
            for i = 1, 20000 do hog[i] = ("x"):rep(64) end
            local after = computer.freeMemory()
            component.invoke(address, "note", "total " .. computer.totalMemory())
            component.invoke(address, "note", "spent " .. tostring(before > after))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 200);

        assertEquals(List.of(), host.getCrashes());
        // A real figure, not the fixed fraction the Java backend has to invent.
        assertEquals(List.of("total " + host.getMemorySize(), "spent true"), recorder.notes);
    }

    @Test
    void memoryReportingScalesWithInstalledMemory() throws Exception {
        // The Java backend has to invent a fixed fraction here. A real Lua knows what its heap
        // holds, which is what makes a modern memory budget worth configuring at all.
        for (final int megabytes : new int[]{4, 16, 32, 64}) {
            final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
            final TestMachineHost host = new TestMachineHost();
            host.setMemorySize(megabytes * 1024 * 1024);
            host.add(eepromWith("""
                local address = component.list("recorder")()
                component.invoke(address, "note", computer.totalMemory() .. " " .. computer.freeMemory())
                computer.shutdown()
                """));
            host.add(recorder);

            final LuaMachine machine = native_(host);
            machine.start();
            TestMachineHost.run(machine, 200);

            assertEquals(List.of(), host.getCrashes());
            final String[] parts = recorder.notes.get(0).split(" ");
            final long total = Long.parseLong(parts[0]);
            final long free = Long.parseLong(parts[1]);

            assertEquals(megabytes * 1024L * 1024L, total);
            // An idle machine has the sandbox and the kernel loaded and very little else, so nearly
            // all of a modern budget should still be there. If this starts failing, something is
            // reporting a fixed fraction again rather than measuring.
            assertTrue(free > total * 9 / 10,
                "at " + megabytes + "MB only " + free + " of " + total + " bytes were free");
        }
    }

    @Test
    void enforcesTheMemoryCeilingAndLetsAProgramRecover() throws Exception {
        // There is no way to hand a real Lua an allocator that refuses, so the ceiling has to be
        // checked from the hook. Without this computer.totalMemory() would be a number the machine
        // reports and nothing honours, and one Lua program could take the whole server's memory.
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.setMemorySize(16 * 1024 * 1024);
        host.add(eepromWith("""
            local address = component.list("recorder")()
            local function note(text) component.invoke(address, "note", text) end

            local keep = {}
            local ok, err = pcall(function()
              for i = 1, 4000000 do keep[i] = ("x"):rep(200) end
            end)
            note("stopped " .. tostring(ok) .. " " .. tostring(err))

            -- Running out of memory is an ordinary error, not the uncatchable timeout, so a
            -- program that frees what it was holding carries on. MineOS closes windows here.
            keep = nil
            note("recovered " .. tostring(computer.freeMemory() > 8 * 1024 * 1024))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = native_(host);
        machine.start();
        TestMachineHost.run(machine, 2000);

        assertEquals(List.of("stopped false not enough memory", "recovered true"), recorder.notes);
        assertEquals(List.of(), host.getCrashes(), "running out of memory must not kill the machine");
    }

    @Test
    void survivesBeingStoppedMidSlice() throws Exception {
        // Closing a Lua state out from under a thread that is executing in it would take the
        // process with it, and the server thread is allowed to stop a machine at any moment.
        for (int i = 0; i < 10; i++) {
            final TestMachineHost host = new TestMachineHost();
            host.add(eepromWith("while true do end"));

            final LuaMachine machine = native_(host, TimeUnit.SECONDS.toMillis(30));
            machine.start();
            machine.tick();  // builds the state
            machine.tick();  // hands it a slice
            Thread.sleep(10);
            machine.stop();
        }
    }

    @Test
    void isWhatAMachineChoosesByDefault() {
        // Not when the backend has been pinned for this run, which is how the suite is taken
        // through the pure Java one:  ./gradlew test -Poc2r.lua.architecture=luaj
        assumeTrue(System.getProperty(LuaArchitectures.OVERRIDE_PROPERTY, "").isBlank(),
            "the backend is pinned for this run");

        // LuaArchitectures picks the backend, and a machine built without naming one takes its
        // answer. With the natives loadable that answer has to be real Lua, or every operating
        // system this exists to run would quietly land on the fallback instead.
        assertEquals(NativeLuaArchitecture.ARCHITECTURE_NAME,
            new LuaMachine(new TestMachineHost()).getArchitectureName());
        assertEquals(NativeLuaArchitecture.ARCHITECTURE_NAME,
            LuaArchitectures.preferred().apply(
                new LuaMachine(new TestMachineHost(), UUID.randomUUID().toString(),
                    NativeLuaArchitecture::new)).getName());
    }
}
