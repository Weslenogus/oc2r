/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine;

import li.cil.oc2.common.machine.components.ComputerComponent;
import li.cil.oc2.common.machine.components.EepromComponent;
import li.cil.oc2.common.machine.components.GraphicsCardComponent;
import li.cil.oc2.common.machine.components.ScreenComponent;
import li.cil.oc2.common.machine.lua.LuaJArchitecture;
import li.cil.oc2.common.machine.lua.LuaMachine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limits a host imposes on its machine, which the server config sets.
 * <p>
 * These run on the pure Java backend so they run everywhere; the limits themselves are the
 * scheduler's and the bus's, not any one Lua implementation's. The memory ceiling is the exception
 * and lives in {@code NativeLuaMachineTest}, because only a real Lua can measure its own heap.
 */
public class MachineLimitsTest {
    private static EepromComponent eepromWith(final String code) {
        final EepromComponent eeprom = new EepromComponent(UUID.randomUUID().toString());
        eeprom.setCode(code);
        return eeprom;
    }

    private static LuaMachine machineFor(final TestMachineHost host) {
        return new LuaMachine(host, UUID.randomUUID().toString(), LuaJArchitecture::new);
    }

    ///////////////////////////////////////////////////////////////////

    @Test
    void theDirectCallBudgetScalesWithTheHostsFactor() throws Exception {
        // The counter's method allows five direct calls per tick. Twelve calls therefore spill
        // over at a factor of one, and fit comfortably once the host says its hardware can take
        // four times the 2015 allowance.
        final LuaMachineTest.CallCounter tight = new LuaMachineTest.CallCounter();
        final TestMachineHost tightHost = new TestMachineHost();
        tightHost.setDirectCallsPerTickFactor(1);
        tightHost.add(eepromWith(callTwelveTimes()));
        tightHost.add(tight);
        final LuaMachine tightMachine = machineFor(tightHost);
        tightMachine.start();
        TestMachineHost.run(tightMachine, 200);

        assertEquals(List.of(), tightHost.getCrashes());
        assertEquals(12, tight.direct + tight.synchronized_);
        assertTrue(tight.synchronized_ > 0, "the stock allowance should have been exceeded");

        final LuaMachineTest.CallCounter roomy = new LuaMachineTest.CallCounter();
        final TestMachineHost roomyHost = new TestMachineHost();
        roomyHost.setDirectCallsPerTickFactor(4);
        roomyHost.add(eepromWith(callTwelveTimes()));
        roomyHost.add(roomy);
        final LuaMachine roomyMachine = machineFor(roomyHost);
        roomyMachine.start();
        TestMachineHost.run(roomyMachine, 200);

        assertEquals(List.of(), roomyHost.getCrashes());
        assertEquals(12, roomy.direct);
        assertEquals(0, roomy.synchronized_, "nothing should have needed the server thread");
    }

    private static String callTwelveTimes() {
        return """
            local address = component.list("counter")()
            for i = 1, 12 do component.invoke(address, "tick") end
            computer.shutdown()
            """;
    }

    @Test
    void aFullScreenRepaintIsNotSpentWaitingForTicks() throws Exception {
        // Repainting 160x50 a cell at a time is 8000 gpu calls. Every call past the allowance is
        // promoted to the synchronized path, which costs the caller a whole tick, so at the stock
        // allowance one frame is over thirty ticks, or more than a second and a half of a player
        // watching a half drawn screen. This is the regression guard on that.
        final int tightTicks = repaintTicks(1);
        final int roomyTicks = repaintTicks(8);

        assertTrue(roomyTicks * 3 < tightTicks,
            "raising the factor should cut the ticks a repaint costs, but went from "
                + tightTicks + " to " + roomyTicks);
        assertTrue(roomyTicks <= 15,
            "a single repaint should fit in well under a second, took " + roomyTicks + " ticks");
    }

    private static int repaintTicks(final int factor) throws Exception {
        final TestMachineHost host = new TestMachineHost();
        host.setDirectCallsPerTickFactor(factor);
        host.add(eepromWith("""
            local gpu = component.proxy(component.list("gpu")())
            local screen = component.list("screen")()
            gpu.bind(screen)
            for y = 1, 50 do
              for x = 1, 160 do gpu.set(x, y, "#") end
            end
            computer.shutdown()
            """));
        host.add(new ScreenComponent(UUID.randomUUID().toString()));
        host.add(new GraphicsCardComponent(UUID.randomUUID().toString()));

        final LuaMachine machine = machineFor(host);
        machine.start();
        final int ticks = TestMachineHost.run(machine, 4000);
        assertEquals(List.of(), host.getCrashes());
        return ticks;
    }

    @Test
    void honoursTheCpuTimeoutTheHostAsksFor() throws Exception {
        final TestMachineHost host = new TestMachineHost();
        host.setCpuTimeoutMillis(200);
        host.add(eepromWith("while true do end"));

        final LuaMachine machine = machineFor(host);
        machine.start();
        TestMachineHost.run(machine, 400);

        assertFalse(machine.isRunning());
        assertEquals(List.of("too long without yielding"), host.getCrashes());
    }

    @Test
    void aGenerousTimeoutLetsOrdinaryWorkFinish() throws Exception {
        // The measurement behind the shipped default: sorting a couple of hundred thousand numbers
        // is perfectly ordinary work for an operating system, takes roughly a tenth of a second on
        // a developer machine and several times that on a server, and has no component call in it
        // to yield through. A timeout set in the low hundreds of milliseconds kills it.
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.setCpuTimeoutMillis(30_000);
        // Enough room for the table below, so that this is measuring the timeout and could not
        // pass or fail for want of memory instead.
        host.setMemorySize(16 * 1024 * 1024);
        host.add(eepromWith("""
            local t = {}
            for i = 1, 200000 do t[i] = (i * 7919) % 200003 end
            table.sort(t)
            local address = component.list("recorder")()
            component.invoke(address, "note", "sorted " .. #t .. " ascending " .. tostring(t[1] <= t[#t]))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = machineFor(host);
        host.add(new ComputerComponent(machine.getAddress()));
        machine.start();
        TestMachineHost.run(machine, 4000);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of("sorted 200000 ascending true"), recorder.notes);
    }

    @Test
    void aPreemptedMachineIsNotACrashedOne() throws Exception {
        // The slice length and the timeout are different things, and a short slice must only
        // reschedule the machine rather than kill it.
        final LuaMachineTest.Recorder recorder = new LuaMachineTest.Recorder();
        final TestMachineHost host = new TestMachineHost();
        host.setCpuSliceMillis(1);
        host.setCpuTimeoutMillis(30_000);
        host.add(eepromWith("""
            local n = 0
            for i = 1, 3000000 do n = n + i end
            local address = component.list("recorder")()
            component.invoke(address, "note", "finished " .. tostring(n > 0))
            computer.shutdown()
            """));
        host.add(recorder);

        final LuaMachine machine = machineFor(host);
        machine.start();
        TestMachineHost.run(machine, 4000);

        assertEquals(List.of(), host.getCrashes());
        assertEquals(List.of("finished true"), recorder.notes);
    }
}
