/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.FilesystemComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.KeyboardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.fs.RamFileSystem;
import li.cil.oc2.common.machine.fs.RomFileSystem;
import li.cil.oc2.common.machine.lua.LuaArchitectures;
import li.cil.oc2.common.machine.lua.LuaMachine;
import li.cil.oc2.common.machine.screen.TextBuffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the built in ROM: that a computer with nothing installed boots the shell on it, and that
 * typing at that shell does something.
 * <p>
 * This is the path a player meets first. A computer that has just been placed has an empty disk,
 * and before the ROM existed the BIOS ran out of things to try, stopped with "no bootable medium
 * found", and left a black screen and a keyboard that appeared to be broken. So the assertions here
 * are deliberately about what is on the screen after a few keystrokes, rather than about any
 * internal state: that is the thing that was wrong.
 * <p>
 * These run on whichever backend {@link LuaArchitectures} prefers, because the ROM has to boot on
 * both. It is written to Lua 5.2 syntax for exactly that reason, and a test that only ever ran on
 * real Lua would not notice an integer division creeping into it.
 */
public final class RomShellTest {
    /**
     * Legacy key codes, as {@code KeyboardMap} produces and the shell expects.
     */
    private static final int KEY_ENTER = 0x1C;
    private static final int KEY_BACKSPACE = 0x0E;

    ///////////////////////////////////////////////////////////////////

    /**
     * A machine wired the way {@code LuaComputerBlockEntity} wires one, plus the means to type at
     * it.
     */
    private static final class Computer {
        final TestMachineHost host = new TestMachineHost();
        final ScreenComponent screen = new ScreenComponent(UUID.randomUUID().toString());
        final KeyboardComponent keyboard = new KeyboardComponent(UUID.randomUUID().toString());
        final FilesystemComponent disk;
        final LuaMachine machine;

        /**
         * What to do at the next opportunity. Input is only fed when the machine is actually
         * waiting for a signal, so a test never races the boot sequence.
         */
        final Deque<Runnable> script = new ArrayDeque<>();

        Computer(final RamFileSystem diskFileSystem) throws IOException {
            final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
            eeprom.setCode(resource("/assets/oc2r/lua/bios.lua"));

            final FilesystemComponent tmpfs = new FilesystemComponent(
                UUID.randomUUID().toString(), new RamFileSystem(1 << 18), "tmpfs");
            disk = new FilesystemComponent(
                UUID.randomUUID().toString(), diskFileSystem, "disk");

            keyboard.setScreen(screen);

            host.add(eeprom);
            host.add(screen);
            host.add(keyboard);
            host.add(new GraphicsCardComponent(UUID.randomUUID().toString()));
            host.add(disk);
            host.add(tmpfs);
            host.add(new FilesystemComponent(UUID.randomUUID().toString(),
                new RomFileSystem(RomFileSystem.load("/assets/oc2r/lua/rom")), "rom"));
            host.setTmpAddress(tmpfs.getComponentAddress());

            machine = new LuaMachine(host, UUID.randomUUID().toString(),
                LuaArchitectures.preferred());
            host.add(new ComputerComponent(machine.getAddress()));
        }

        /**
         * Types text the way the client does: a character event per printable key, which is what
         * arrives with no key code, then enter as a key event with no character.
         */
        void type(final String text) {
            for (final int codePoint : text.codePoints().toArray()) {
                script.add(() -> machine.signal("key_down",
                    keyboard.getComponentAddress(), (double) codePoint, 0.0, "Tester"));
            }
        }

        void press(final int keyCode) {
            script.add(() -> machine.signal("key_down",
                keyboard.getComponentAddress(), 0.0, (double) keyCode, "Tester"));
        }

        void enter(final String line) {
            type(line);
            press(KEY_ENTER);
        }

        /**
         * Runs the machine, feeding one scripted input whenever it settles, then a few more ticks
         * so the last command has somewhere to draw.
         */
        void run(final int ticks) throws InterruptedException {
            machine.start();
            TestMachineHost.run(machine, ticks, () -> {
                if (!script.isEmpty()
                    && machine.getState() == LuaMachine.State.SLEEPING
                    && machine.getSignalQueue().isEmpty()) {
                    script.poll().run();
                }
            });
        }

        String screenText() {
            final StringBuilder builder = new StringBuilder();
            final TextBuffer buffer = screen.getBuffer();
            for (int y = 0; y < buffer.getHeight(); y++) {
                for (int x = 0; x < buffer.getWidth(); x++) {
                    final char value = buffer.getRawChar(x, y);
                    builder.append(value == TextBuffer.WIDE_CHAR_CONTINUATION ? ' ' : value);
                }
                builder.append('\n');
            }
            return builder.toString();
        }
    }

    private static String resource(final String path) throws IOException {
        try (final InputStream stream = RomShellTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path + " is missing from the mod resources");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void theRomCarriesTheShell() throws Exception {
        final RomFileSystem rom = new RomFileSystem(RomFileSystem.load("/assets/oc2r/lua/rom"));

        assertTrue(rom.isReadOnly());
        assertTrue(rom.exists("/init.lua"));
        assertTrue(rom.size("/init.lua") > 0);
        assertEquals(List.of("init.lua"), List.of(rom.list("/")));
        assertFalse(rom.makeDirectory("/nope"));
        assertFalse(rom.remove("/init.lua"));
        assertThrows(IOException.class,
            () -> rom.open("/init.lua", RomFileSystem.Mode.WRITE));
    }

    @Test
    void aComputerWithAnEmptyDiskBootsToAPrompt() throws Exception {
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.run(200);

        final String text = computer.screenText();
        assertEquals(List.of(), computer.host.getCrashes(), () -> "machine crashed: " + text);
        assertTrue(text.contains("oc2r shell"), () -> "no banner on screen:\n" + text);
        assertTrue(text.contains("/disk > "), () -> "no prompt on screen:\n" + text);
    }

    @Test
    void aComputerWithAnEmptyEnergyBufferStillBoots() throws Exception {
        // What a freshly placed computer is: nothing has charged it yet. It used to start, fail to
        // pay for its first tick, and stop with "not enough energy" before anything reached the
        // screen - which a player experiences as a block that does nothing when right clicked. The
        // shipped cost is zero for exactly that reason.
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.host.setEnergy(0);
        computer.run(200);

        final String text = computer.screenText();
        assertEquals(List.of(), computer.host.getCrashes(), () -> "machine crashed: " + text);
        assertTrue(text.contains("/disk > "), () -> "no prompt on screen:\n" + text);
    }

    @Test
    void aComputerThatIsChargedForItsPowerStopsWithoutIt() throws Exception {
        // And the cost still works when a server turns it on: the machine stops, and says why.
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.host.setEnergy(0);
        computer.host.setEnergyPerTick(10);
        computer.run(200);

        assertEquals(List.of("not enough energy"), computer.host.getCrashes());
    }

    @Test
    void typingAtThePromptRunsCommands() throws Exception {
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.enter("echo the keyboard works");
        computer.run(400);

        final String text = computer.screenText();
        assertEquals(List.of(), computer.host.getCrashes(), () -> "machine crashed: " + text);
        assertTrue(text.contains("the keyboard works"), () -> "the command did not run:\n" + text);
    }

    @Test
    void backspaceEditsTheLineBeforeItIsRun() throws Exception {
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.type("echo typoo");
        computer.press(KEY_BACKSPACE);
        computer.press(KEY_ENTER);
        computer.run(400);

        final String text = computer.screenText();
        assertTrue(text.contains("typo\n") || text.contains("typo "),
            () -> "backspace did not take the character back:\n" + text);
    }

    @Test
    void filesTypedAtTheShellSurviveOnTheDisk() throws Exception {
        final RamFileSystem diskFileSystem = new RamFileSystem(1 << 20);
        final Computer computer = new Computer(diskFileSystem);

        computer.enter("edit /disk/hello.lua");
        computer.enter("return 6 * 7");
        computer.enter(".");
        computer.enter("run /disk/hello.lua");
        computer.run(900);

        final String text = computer.screenText();
        assertEquals(List.of(), computer.host.getCrashes(), () -> "machine crashed: " + text);
        assertTrue(diskFileSystem.exists("/hello.lua"), () -> "the file was not written:\n" + text);
        assertTrue(text.contains("42"), () -> "the program did not run:\n" + text);
    }

    @Test
    void anOperatingSystemOnTheDiskWinsOverTheRom() throws Exception {
        final RamFileSystem diskFileSystem = new RamFileSystem(1 << 20);
        write(diskFileSystem, "/init.lua", """
            local gpu = component.proxy((component.list("gpu")()))
            -- The extra parentheses matter: component.list returns the address and the type, and
            -- bind's second argument is a boolean.
            gpu.bind((component.list("screen")()))
            gpu.set(1, 1, "installed system, not the rom")
            computer.shutdown()
            """);

        final Computer computer = new Computer(diskFileSystem);
        computer.run(200);

        final String text = computer.screenText();
        assertEquals(List.of(), computer.host.getCrashes(), () -> "machine crashed: " + text);
        assertTrue(text.contains("installed system, not the rom"),
            () -> "the disk did not win the boot order:\n" + text);
        assertFalse(text.contains("oc2r shell"), () -> "the rom booted anyway:\n" + text);
    }

    @Test
    void bootingTheRomIsNotRemembered() throws Exception {
        // The BIOS writes the address it booted from into the EEPROM so the same disk is chosen
        // next time. Doing that for the ROM would mean a machine that has since had an operating
        // system installed still boots the ROM, and the installation would look like it failed.
        final Computer computer = new Computer(new RamFileSystem(1 << 20));
        computer.run(200);

        final EepromComponent eeprom = (EepromComponent) computer.host.getComponents().stream()
            .filter(EepromComponent.class::isInstance)
            .findFirst()
            .orElseThrow();
        assertEquals(0, eeprom.getData().length,
            "the read only rom was remembered as the boot device");
    }

    private static void write(final RamFileSystem fileSystem, final String path, final String content)
        throws IOException {
        final int handle = fileSystem.open(path, RamFileSystem.Mode.WRITE);
        fileSystem.write(handle, content.getBytes(StandardCharsets.UTF_8));
        fileSystem.close(handle);
    }
}
