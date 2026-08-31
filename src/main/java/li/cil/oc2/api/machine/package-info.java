/* SPDX-License-Identifier: MIT */

/**
 * Public API of the OpenComputers 1 compatible Lua runtime.
 * <p>
 * This sits alongside the RISC-V virtual machine API in {@link li.cil.oc2.api.bus.device.vm}
 * rather than replacing it: a world can hold both kinds of computer, and a device may expose
 * itself to either or both runtimes.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
package li.cil.oc2.api.machine;

import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
