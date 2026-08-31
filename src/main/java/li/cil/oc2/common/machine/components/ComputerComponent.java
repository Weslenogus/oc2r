/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.LuaComponent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The machine's own {@code computer} component: the case, as seen from inside.
 * <p>
 * Its address is the machine's address, which is what lets a program tell itself apart from the
 * other computers it can reach over a network card, and what makes
 * {@code component.proxy(computer.address())} work.
 * <p>
 * Note that this is a different thing from the {@code computer} library in the sandbox. The
 * library is always about this machine; the component can also be seen by a neighbouring computer,
 * which is how one machine turns another on.
 */
public final class ComputerComponent extends AbstractLuaComponent {
    public ComputerComponent(final String machineAddress) {
        super("computer", machineAddress);
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(doc = "function():boolean -- Starts the computer. Returns true if it was off.")
    public Object[] start(final Context context, final Arguments args) {
        return new Object[]{context.machine().start()};
    }

    @Callback(doc = "function():boolean -- Stops the computer. Returns true if it was on.")
    public Object[] stop(final Context context, final Arguments args) {
        return new Object[]{context.machine().stop()};
    }

    @Callback(direct = true, limit = 16, doc = "function():boolean -- Whether the computer is running.")
    public Object[] isRunning(final Context context, final Arguments args) {
        return new Object[]{context.machine().isRunning()};
    }

    @Callback(doc = "function([frequency:number[, duration:number]]) -- Plays a note through the computer's speaker.")
    public Object[] beep(final Context context, final Arguments args) {
        // Clamped to what a note block can actually produce, and to a length that cannot be used
        // to hold a note indefinitely.
        final int frequency = Math.max(20, Math.min(2000, args.optInteger(0, 1000)));
        final double duration = Math.max(0.05, Math.min(5.0, args.optDouble(1, 0.1)));
        context.host().beep(frequency, duration);
        return null;
    }

    @Callback(direct = true, limit = 4, doc = "function():table -- Information about the devices attached to this computer.")
    public Object[] getDeviceInfo(final Context context, final Arguments args) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final LuaComponent component : context.components()) {
            final Map<String, Object> info = new LinkedHashMap<>();
            info.put("class", component.getComponentType());
            info.put("description", component.getComponentType());
            info.put("vendor", "OpenComputers II");
            result.put(component.getComponentAddress(), info);
        }
        return new Object[]{result};
    }

    @Callback(direct = true, limit = 4, doc = "function():table -- Where to find installable programs. Empty; software is distributed on disks.")
    public Object[] getProgramLocations(final Context context, final Arguments args) {
        // OpenOS calls this when a program is missing, to suggest where to get it. There is no
        // package index here, so the honest answer is an empty table rather than a fabricated one.
        return new Object[]{new LinkedHashMap<String, Object>()};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The energy currently stored.")
    public Object[] energy(final Context context, final Arguments args) {
        return new Object[]{context.host().getEnergyStored()};
    }

    @Callback(direct = true, limit = 16, doc = "function():number -- The maximum energy that can be stored.")
    public Object[] maxEnergy(final Context context, final Arguments args) {
        return new Object[]{context.host().getEnergyCapacity()};
    }
}
