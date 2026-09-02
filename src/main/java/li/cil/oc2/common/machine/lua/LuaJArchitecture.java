/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.ExecutionResult;
import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.Signal;
import li.cil.oc2.api.machine.Value;
import li.cil.oc2.common.machine.bus.CallbackMethod;
import li.cil.oc2.common.machine.bus.Callbacks;
import li.cil.oc2.common.machine.bus.ComponentBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.OrphanedThread;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.lib.jse.JseOsLib;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Lua state, the sandbox around it and the yield protocol that ties it to the server tick.
 * <p>
 * LuaJ implements coroutines as real Java threads, which is normally a wart but is exactly what
 * makes this port workable: a Java function can yield from the middle of a call and be resumed
 * later, so component calls that must run on the server thread do not need any Lua side plumbing
 * to bounce through. The Lua side only has to bubble system yields out through nested coroutines,
 * which is what the {@code coroutine} wrapper in {@code machine.lua} does.
 * <p>
 * A slice ends when the kernel yields a {@link SystemYield} kind. The sandbox's coroutine library
 * prepends {@code nil} to every user yield, so a non-nil kind can only have come from the kernel.
 * <p>
 * A deferred component call yields from Lua rather than from Java, even though this backend could
 * do it either way. That keeps one kernel script working for both this and a native Lua, where
 * yielding across the Java call boundary is not possible at all.
 */
public final class LuaJArchitecture implements LuaArchitecture {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String MACHINE_SCRIPT = "/assets/oc2r/lua/machine.lua";

    /**
     * Slice length used until the first slice has asked the host for its own figure, which is
     * where the server config's answer arrives. Only ever in force for a machine whose host does
     * not override {@link li.cil.oc2.api.machine.MachineHost#getCpuSliceMillis()}.
     */
    private static final long DEFAULT_SLICE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /**
     * No-yield budget used until the first slice has asked the host, as above.
     * <p>
     * Measured as time actually spent executing rather than wall clock, so a machine is judged on
     * the work it did and not on how often the server got round to scheduling it.
     */
    private static final long DEFAULT_HARD_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Bytecode instructions between debug hook invocations. Small enough that the slice deadline
     * is honoured closely, large enough that the check itself does not dominate.
     */
    private static final int HOOK_INTERVAL = 10_000;

    ///////////////////////////////////////////////////////////////////

    private final LuaMachine machine;

    /**
     * Why the last {@link #initialize()} failed, so the machine can say so rather than reporting
     * the same three words whatever went wrong.
     */
    @Nullable private String initializationError;

    /**
     * Explicit budgets, or zero to take the host's. Tests pin them; a machine in the world asks the
     * host every slice, so changing the server config takes effect without a restart.
     */
    private final long sliceBudgetOverrideNanos;
    private final long hardDeadlineOverrideNanos;

    /**
     * The budgets in force for the slice currently running, read once at its start so the hook does
     * not go back to the host every ten thousand instructions.
     */
    private long sliceBudgetNanos = DEFAULT_SLICE_BUDGET_NANOS;
    private long hardDeadlineNanos = DEFAULT_HARD_DEADLINE_NANOS;

    @Nullable private Globals globals;

    /**
     * The resume function {@code machine.lua} hands back, and the only entry point into the
     * machine. Deliberately not a global: a program able to reach it could re-enter itself.
     */
    @Nullable private LuaValue resume;

    @Nullable private LuaValue deadlineHook;

    private long sliceStart;
    private long sliceDeadline;

    /**
     * Execution time accumulated since the machine last yielded on its own.
     * <p>
     * Being preempted does not count as yielding. It cannot: preemption is something done to the
     * machine, so resetting the budget on it would mean an infinite loop resets its own deadline
     * every 50ms and never trips it, which is exactly the case this exists to catch.
     */
    private long nonYieldingNanos;

    /**
     * Whether the machine is parked in {@code computer.pullSignal} and can therefore be handed a
     * signal on the next resume. False while it is preempted or waiting on a synchronized call,
     * where it is mid-expression and a signal would be delivered to the wrong place.
     */
    private volatile boolean acceptingSignals;

    // Handoff for the synchronized call currently in flight. Written by the machine thread before
    // it yields, read and answered by the server thread, read back by the machine thread after it
    // is resumed. The yield and resume handshake orders these, but they are volatile so the
    // ordering does not depend on reasoning about LuaJ's internals.
    @Nullable private volatile LuaComponent pendingComponent;
    @Nullable private volatile CallbackMethod pendingMethod;
    @Nullable private volatile Arguments pendingArguments;
    @Nullable private volatile Object[] syncResults;
    @Nullable private volatile String syncError;

    /**
     * Whether the kernel is parked on a deferred component call, so the next resume carries its
     * answer rather than a signal.
     */
    private boolean syncPending;

    ///////////////////////////////////////////////////////////////////

    public LuaJArchitecture(final LuaMachine machine) {
        this(machine, 0, 0);
    }

    public LuaJArchitecture(final LuaMachine machine, final long sliceBudgetNanos, final long hardDeadlineNanos) {
        this.machine = machine;
        this.sliceBudgetOverrideNanos = sliceBudgetNanos;
        this.hardDeadlineOverrideNanos = hardDeadlineNanos;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * LuaJ implements Lua 5.2. Reporting anything else would be a lie programs act on.
     */
    public static final String ARCHITECTURE_NAME = "Lua 5.2";

    @Override
    public String getName() {
        return ARCHITECTURE_NAME;
    }

    @Override
    public boolean initialize() {
        initializationError = null;
        try {
            final Globals globals = createGlobals();
            this.globals = globals;

            deadlineHook = new DeadlineHook();

            globals.set("_JAVA", createNativeApi());

            final LuaValue chunk;
            try (final InputStream stream = openMachineScript()) {
                chunk = globals.load(stream, "=machine", "t", globals);
            }

            // machine.lua builds the sandbox and hands back its resume function. It captures
            // everything it needs from _JAVA into locals, so the table can go away afterwards and
            // sandboxed code has no path back to the natives except through the sandbox itself.
            final LuaValue entryPoint = chunk.call();
            globals.set("_JAVA", LuaValue.NIL);

            if (!entryPoint.isfunction()) {
                LOGGER.error("machine.lua did not return a function.");
                initializationError = "machine.lua did not return a function";
                return false;
            }

            resume = entryPoint;
            acceptingSignals = false;

            return true;
        } catch (final Throwable e) {
            LOGGER.error("Failed initializing Lua machine.", e);
            initializationError = LuaArchitectures.describe(e);
            close();
            return false;
        }
    }

    @Nullable
    @Override
    public String getInitializationError() {
        return initializationError;
    }

    @Override
    public boolean isInitialized() {
        return resume != null;
    }

    @Override
    public void close() {
        // There is no way to unwind a suspended LuaJ coroutine from the outside. Dropping the
        // references is the supported way out: LuaJ's coroutine threads wake periodically, notice
        // their LuaThread has been collected and throw OrphanedThread, which unwinds them. Holding
        // on to any of this would keep those threads parked forever.
        resume = null;
        globals = null;
        deadlineHook = null;
        pendingComponent = null;
        pendingMethod = null;
        pendingArguments = null;
        syncResults = null;
        syncError = null;
        syncPending = false;
        acceptingSignals = false;
        nonYieldingNanos = 0;
    }

    @Override
    public boolean isAcceptingSignals() {
        return acceptingSignals;
    }

    @Override
    public ExecutionResult runThreaded(@Nullable final Signal signal) {
        final LuaValue trampoline = this.resume;
        if (trampoline == null) {
            return new ExecutionResult.Error("machine is not initialized");
        }

        sliceBudgetNanos = sliceBudgetOverrideNanos > 0 ? sliceBudgetOverrideNanos
            : TimeUnit.MILLISECONDS.toNanos(machine.getHost().getCpuSliceMillis());
        hardDeadlineNanos = hardDeadlineOverrideNanos > 0 ? hardDeadlineOverrideNanos
            : TimeUnit.MILLISECONDS.toNanos(machine.getHost().getCpuTimeoutMillis());

        final long now = System.nanoTime();
        sliceStart = now;
        sliceDeadline = now + sliceBudgetNanos;

        final Varargs resumeArgs;
        if (syncPending) {
            // Answering the deferred component call the kernel is parked on.
            syncPending = false;
            resumeArgs = takeSyncResult();
        } else if (acceptingSignals && signal != null) {
            resumeArgs = toVarargs(signal);
        } else {
            resumeArgs = LuaValue.NONE;
        }
        acceptingSignals = false;

        final ExecutionResult result = resume(trampoline, resumeArgs);

        // Preemption means the machine still has not yielded, so its budget keeps running.
        // Anything else means it reached a point of its own choosing, and the clock resets.
        if (result instanceof ExecutionResult.Preempted) {
            nonYieldingNanos += System.nanoTime() - sliceStart;
        } else {
            nonYieldingNanos = 0;
        }

        return result;
    }

    private ExecutionResult resume(final LuaValue trampoline, final Varargs resumeArgs) {
        final Varargs result;
        try {
            result = trampoline.invoke(resumeArgs);
        } catch (final OrphanedThread e) {
            return new ExecutionResult.Shutdown(false);
        } catch (final LuaError e) {
            return new ExecutionResult.Error(describe(e));
        } catch (final Throwable e) {
            LOGGER.error("Unexpected error resuming Lua machine.", e);
            return new ExecutionResult.Error(String.valueOf(e.getMessage()));
        }

        // machine.lua answers (ok, kind, payload).
        if (!result.arg1().toboolean()) {
            return new ExecutionResult.Error(result.arg(3).tojstring());
        }

        final LuaValue kind = result.arg(2);
        if (kind.isnil()) {
            return new ExecutionResult.Shutdown(false);
        }

        final LuaValue payload = result.arg(3);
        return switch (kind.toint()) {
            case SystemYield.SLEEP -> {
                acceptingSignals = true;
                yield new ExecutionResult.Sleep(secondsToTicks(payload.todouble()));
            }
            case SystemYield.SHUTDOWN -> new ExecutionResult.Shutdown(payload.toboolean());
            case SystemYield.SYNCHRONIZED_CALL -> {
                syncPending = true;
                yield ExecutionResult.SynchronizedCall.INSTANCE;
            }
            case SystemYield.PREEMPT -> ExecutionResult.Preempted.INSTANCE;
            case SystemYield.KILL -> new ExecutionResult.Error("too long without yielding");
            default -> {
                // Not something machine.lua produces, but treating it as "run me again shortly"
                // keeps a misbehaving kernel from wedging the host.
                acceptingSignals = true;
                yield new ExecutionResult.Sleep(0);
            }
        };
    }

    @Override
    public void runSynchronized() {
        final LuaComponent component = pendingComponent;
        final CallbackMethod method = pendingMethod;
        final Arguments arguments = pendingArguments;

        pendingComponent = null;
        pendingMethod = null;
        pendingArguments = null;

        if (component == null || method == null || arguments == null) {
            syncResults = null;
            syncError = "no call pending";
            return;
        }

        try {
            syncResults = method.invoke(component, machine.getSynchronizedContext(), arguments);
            syncError = null;
        } catch (final Throwable e) {
            syncResults = null;
            syncError = describe(e);
        }
    }

    @Override
    public int getMemoryTotal() {
        return machine.getHost().getMemorySize();
    }

    @Override
    public int getMemoryUsed() {
        // LuaJ allocates on the JVM heap and offers no per state accounting, so there is no
        // honest number to report here. Claiming a constant quarter of the installed memory keeps
        // operating systems that budget against computer.freeMemory() working, and keeps the
        // figure stable rather than jittering with unrelated garbage collection. A native backend
        // implementing LuaArchitecture can report the real figure.
        return getMemoryTotal() / 4;
    }

    ///////////////////////////////////////////////////////////////////

    private Globals createGlobals() {
        final Globals globals = new Globals();
        globals.load(new JseBaseLib());
        globals.load(new PackageLib());
        globals.load(new Bit32Lib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new CoroutineLib());
        globals.load(new JseMathLib());
        globals.load(new JseOsLib());
        // DebugLib is what makes onInstruction fire, which is what drives the deadline hook. The
        // debug table itself never reaches the sandbox; machine.lua exposes only traceback.
        globals.load(new DebugLib());
        LoadState.install(globals);
        LuaC.install(globals);

        // OsLib is loaded for date and time, but it also brings process control with it. The
        // sandbox never exposes those, and they are stripped from the globals as well so no
        // amount of cleverness with an error object's metatable can reach System.exit.
        final LuaValue os = globals.get("os");
        for (final String name : new String[]{"execute", "exit", "getenv", "remove", "rename", "tmpname"}) {
            os.set(name, LuaValue.NIL);
        }

        // Nothing in here gets to touch the host's file system or streams. PackageLib is loaded
        // because DebugLib expects it to be, not because require is going anywhere.
        globals.finder = name -> null;
        globals.STDIN = InputStream.nullInputStream();
        globals.STDOUT = new PrintStream(OutputStream.nullOutputStream());
        globals.STDERR = globals.STDOUT;

        return globals;
    }

    private static InputStream openMachineScript() throws IOException {
        final InputStream stream = LuaJArchitecture.class.getResourceAsStream(MACHINE_SCRIPT);
        if (stream == null) {
            throw new IOException("Missing machine script [" + MACHINE_SCRIPT + "].");
        }
        return stream;
    }

    /**
     * Creates a {@link Globals} for {@code machine.lua} to use as a chunk environment.
     * <p>
     * LuaJ only invokes debug hooks from a closure whose environment is a {@link Globals}: see
     * {@code LuaClosure}, which keeps a {@code globals} field set only when the environment it was
     * loaded with happens to be one, and skips {@code onInstruction} entirely when it is null.
     * A chunk loaded with a plain table for an environment therefore runs with no hook at all,
     * which would mean {@code load(code, nil, nil, {})} is all it takes to escape the CPU limiter.
     * <p>
     * These stand-ins carry the shared debug library so hooks fire, and a non-null
     * {@code running}, which LuaJ dereferences without checking when it builds an error traceback.
     * They hold no state of their own: {@code machine.lua} points their metatable at the real
     * environment table, so they stay empty and every access falls through to it.
     */
    private LuaValue createEnvironment() {
        final Globals globals = this.globals;
        if (globals == null) {
            throw new LuaError("machine is shutting down");
        }

        final Globals environment = new Globals();
        environment.debuglib = globals.debuglib;
        // Only ever read to look for an error handler, which nothing here installs, so whichever
        // thread is running now is as good a stand-in as any.
        environment.running = globals.running;
        return environment;
    }

    private void installHook(final LuaThread thread) {
        final LuaThread.State state = thread.state;
        state.hookfunc = deadlineHook;
        state.hookcount = HOOK_INTERVAL;
        state.hookcall = false;
        state.hookline = false;
        state.hookrtrn = false;
    }

    private static int secondsToTicks(final double seconds) {
        if (Double.isNaN(seconds) || seconds <= 0) {
            return 0;
        }
        if (Double.isInfinite(seconds)) {
            return Integer.MAX_VALUE;
        }
        final double ticks = Math.ceil(seconds * 20.0);
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    private Varargs toVarargs(final Signal signal) {
        final Object[] args = signal.args();
        final LuaValue[] values = new LuaValue[args.length + 1];
        values[0] = LuaValue.valueOf(signal.name());
        for (int i = 0; i < args.length; i++) {
            values[i + 1] = LuaValues.toLua(args[i], this::wrapValue);
        }
        return LuaValue.varargsOf(values);
    }

    /**
     * Copies a varargs list into a standalone one.
     * <p>
     * Argument lists handed to a Java function can be views over the caller's stack. That is fine
     * for a direct call, which returns before anything else runs, but a deferred call keeps its
     * arguments across a yield and a hop to the server thread, so it needs its own copy.
     */
    private static Varargs snapshot(final Varargs args, final int from) {
        final int count = Math.max(0, args.narg() - from + 1);
        final LuaValue[] values = new LuaValue[count];
        for (int i = 0; i < count; i++) {
            values[i] = args.arg(from + i);
        }
        return LuaValue.varargsOf(values);
    }

    /**
     * The answer to a deferred component call, shaped the way the kernel's invoke wrapper reads
     * it: {@code (true, results...)} or {@code (false, message)}.
     */
    private Varargs takeSyncResult() {
        final String error = syncError;
        final Object[] results = syncResults;
        syncError = null;
        syncResults = null;

        if (error != null) {
            return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(error));
        }
        return LuaValue.varargsOf(LuaValue.TRUE, LuaValues.toVarargs(results, this::wrapValue));
    }

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static LuaError toLuaError(final Throwable e) {
        return e instanceof final LuaError luaError ? luaError : new LuaError(describe(e));
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Wraps a {@link Value} in a table of bound closures, which is how Lua sees objects such as
     * the handle {@code internet.request} returns.
     */
    private LuaValue wrapValue(final Value value) {
        final LuaTable table = new LuaTable();
        for (final Map.Entry<String, CallbackMethod> entry : Callbacks.collect(value).entrySet()) {
            final CallbackMethod method = entry.getValue();
            table.set(entry.getKey(), new VarArgFunction() {
                @Override
                public Varargs invoke(final Varargs args) {
                    // Values are always called on the machine thread. They exist for short lived,
                    // thread safe handles; anything needing the server thread belongs on a
                    // component, where the direct flag can route it properly.
                    try {
                        return LuaValues.toVarargs(
                            method.invoke(value, machine.getDirectContext(), new LuaArguments(args, 1)),
                            LuaJArchitecture.this::wrapValue);
                    } catch (final Throwable e) {
                        throw toLuaError(e);
                    }
                }
            });
        }
        return table;
    }

    /**
     * The table of raw Java functions handed to {@code machine.lua}. Everything the sandbox can
     * eventually reach comes from here.
     */
    private LuaTable createNativeApi() {
        final LuaTable api = new LuaTable();
        api.set("component", createComponentApi());
        api.set("computer", createComputerApi());
        api.set("unicode", createUnicodeApi());
        api.set("sethook", fn(args -> {
            // Hooks are per coroutine in LuaJ, so machine.lua installs one on every coroutine the
            // sandbox creates. Without this a program could escape preemption simply by doing its
            // work inside a coroutine, which is precisely what an operating system does.
            installHook(args.checkthread(1));
            return LuaValue.NONE;
        }));
        api.set("newenv", fn(args -> createEnvironment()));

        // The kernel yields these back; Java owns the numbering.
        api.set("KIND_SLEEP", LuaValue.valueOf(SystemYield.SLEEP));
        api.set("KIND_SHUTDOWN", LuaValue.valueOf(SystemYield.SHUTDOWN));
        api.set("KIND_SYNCHRONIZED_CALL", LuaValue.valueOf(SystemYield.SYNCHRONIZED_CALL));

        return api;
    }

    private LuaTable createComponentApi() {
        final ComponentBus bus = machine.getBus();
        final LuaTable api = new LuaTable();

        api.set("list", fn(args -> {
            final String filter = args.isnoneornil(1) ? null : args.checkjstring(1);
            final boolean exact = args.optboolean(2, false);
            final LuaTable result = new LuaTable();
            bus.list(filter, exact).forEach((address, type) ->
                result.set(LuaValue.valueOf(address), LuaValue.valueOf(type)));
            return result;
        }));

        api.set("type", fn(args -> bus.getComponent(args.checkjstring(1))
            .map(component -> (Varargs) LuaValue.valueOf(component.getComponentType()))
            .orElseGet(() -> LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("no such component")))));

        api.set("slot", fn(args -> bus.getComponent(args.checkjstring(1))
            .map(component -> (Varargs) LuaValue.valueOf(component.getComponentSlot()))
            .orElseGet(() -> LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("no such component")))));

        api.set("methods", fn(args -> {
            final Map<String, Boolean> methods = bus.methods(args.checkjstring(1));
            if (methods == null) {
                return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("no such component"));
            }
            final LuaTable result = new LuaTable();
            methods.forEach((name, direct) -> result.set(LuaValue.valueOf(name), LuaValue.valueOf(direct)));
            return result;
        }));

        api.set("doc", fn(args -> {
            final CallbackMethod method = bus.lookup(args.checkjstring(1), args.checkjstring(2));
            return method == null ? LuaValue.NIL : LuaValue.valueOf(method.getDoc());
        }));

        api.set("invoke", fn(args -> invokeComponent(args.checkjstring(1), args.checkjstring(2), args)));

        // Retained for compatibility with programs that still probe for the removed field API.
        api.set("fields", fn(args -> new LuaTable()));

        return api;
    }

    private Varargs invokeComponent(final String address, final String methodName, final Varargs args) {
        final ComponentBus bus = machine.getBus();
        final LuaComponent component = bus.getComponent(address).orElse(null);
        if (component == null) {
            throw new LuaError("no such component");
        }

        final CallbackMethod method = Callbacks.collect(component).get(methodName);
        if (method == null) {
            throw new LuaError("no such method");
        }

        final Arguments arguments = new LuaArguments(snapshot(args, 3), 0);

        if (machine.getDirectCallBudget().tryConsume(address, method)) {
            // (true, results...) or (false, message): a component that fails should surface at the
            // call site, and the kernel cannot tell the two apart without the flag.
            try {
                return LuaValue.varargsOf(LuaValue.TRUE, LuaValues.toVarargs(
                    method.invoke(component, machine.getDirectContext(), arguments), this::wrapValue));
            } catch (final Throwable e) {
                return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(describe(e)));
            }
        }

        // Either the method is not thread safe or it has used up its direct call allowance for
        // this tick. Stash it and return no values at all: that is the signal machine.lua turns
        // into a system yield, which hands the call to the server thread. The yield happens in Lua
        // rather than here so that the same kernel script also works on a native Lua, where a Java
        // function cannot yield at all.
        pendingComponent = component;
        pendingMethod = method;
        pendingArguments = arguments;

        return LuaValue.NONE;
    }

    private LuaTable createComputerApi() {
        final LuaTable api = new LuaTable();

        api.set("address", fn(args -> LuaValue.valueOf(machine.getAddress())));
        api.set("tmpAddress", fn(args -> {
            final String address = machine.getHost().getTmpAddress();
            return address == null ? LuaValue.NIL : LuaValue.valueOf(address);
        }));
        api.set("totalMemory", fn(args -> LuaValue.valueOf(getMemoryTotal())));
        api.set("freeMemory", fn(args -> LuaValue.valueOf(Math.max(0, getMemoryTotal() - getMemoryUsed()))));
        api.set("energy", fn(args -> LuaValue.valueOf(machine.getHost().getEnergyStored())));
        api.set("maxEnergy", fn(args -> LuaValue.valueOf(machine.getHost().getEnergyCapacity())));
        api.set("uptime", fn(args -> LuaValue.valueOf(machine.getUptime())));
        api.set("realTime", fn(args -> LuaValue.valueOf(System.currentTimeMillis() / 1000.0)));

        api.set("pushSignal", fn(args -> LuaValue.valueOf(
            machine.signal(args.checkjstring(1), LuaValues.toJavaArray(args, 2)))));

        api.set("beep", fn(args -> {
            // Sound has to be played from the server thread, and this is a direct call, so it goes
            // through the machine's queue and is flushed on the next tick.
            if (args.isstring(1) && !args.isnumber(1)) {
                // computer.beep("...-") is the pattern form; the OpenOS shipped one only varies
                // duration, so a single tone of the right total length is the honest rendering.
                final String pattern = args.checkjstring(1);
                machine.queueBeep(1000, Math.max(0.05, pattern.length() * 0.1));
            } else {
                machine.queueBeep(args.optint(1, 1000), args.optdouble(2, 0.1));
            }
            return LuaValue.NONE;
        }));

        api.set("getDeviceInfo", fn(args -> {
            final LuaTable result = new LuaTable();
            for (final LuaComponent component : machine.getComponents()) {
                final LuaTable info = new LuaTable();
                info.set("class", LuaValue.valueOf(component.getComponentType()));
                info.set("description", LuaValue.valueOf(component.getComponentType()));
                result.set(LuaValue.valueOf(component.getComponentAddress()), info);
            }
            return result;
        }));

        // Programs use these to decide which dialect to emit. MineOS in particular compiles a
        // bitwise fast path when the architecture is not Lua 5.2, so answering honestly is what
        // keeps it on the code path this VM can actually run.
        api.set("getArchitecture", fn(args -> LuaValue.valueOf(machine.getArchitectureName())));
        api.set("getArchitectures", fn(args -> {
            final LuaTable result = new LuaTable();
            result.set(1, LuaValue.valueOf(machine.getArchitectureName()));
            return result;
        }));
        api.set("setArchitecture", fn(args -> {
            // There is only one, so this either changes nothing or asks for something that does
            // not exist. Either way the machine does not reboot.
            final String requested = args.checkjstring(1);
            return requested.equals(machine.getArchitectureName())
                ? LuaValue.TRUE
                : LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("unknown architecture"));
        }));
        api.set("getProgramLocations", fn(args -> new LuaTable()));

        api.set("users", fn(args -> LuaValue.varargsOf(
            machine.getUsers().stream().map(LuaValue::valueOf).toArray(LuaValue[]::new))));
        api.set("addUser", fn(args -> machine.addUser(args.checkjstring(1))
            ? LuaValue.TRUE
            : LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("player is already registered"))));
        api.set("removeUser", fn(args -> LuaValue.valueOf(machine.removeUser(args.checkjstring(1)))));

        return api;
    }

    private LuaTable createUnicodeApi() {
        final LuaTable api = new LuaTable();

        api.set("char", fn(args -> {
            final StringBuilder builder = new StringBuilder(args.narg());
            for (int i = 1; i <= args.narg(); i++) {
                builder.appendCodePoint(args.checkint(i));
            }
            return LuaValue.valueOf(builder.toString());
        }));
        api.set("len", fn(args -> LuaValue.valueOf(UnicodeSupport.length(str(args, 1)))));
        api.set("lower", fn(args -> LuaValue.valueOf(str(args, 1).toLowerCase())));
        api.set("upper", fn(args -> LuaValue.valueOf(str(args, 1).toUpperCase())));
        api.set("reverse", fn(args -> LuaValue.valueOf(UnicodeSupport.reverse(str(args, 1)))));
        api.set("sub", fn(args -> {
            final String value = str(args, 1);
            return LuaValue.valueOf(UnicodeSupport.sub(value, args.checkint(2),
                args.optint(3, UnicodeSupport.length(value))));
        }));
        api.set("isWide", fn(args -> LuaValue.valueOf(UnicodeSupport.isWide(str(args, 1)))));
        api.set("charWidth", fn(args -> {
            final String value = str(args, 1);
            return LuaValue.valueOf(value.isEmpty() ? 0 : UnicodeSupport.charWidth(value.codePointAt(0)));
        }));
        api.set("wlen", fn(args -> LuaValue.valueOf(UnicodeSupport.displayWidth(str(args, 1)))));
        api.set("wtrunc", fn(args -> LuaValue.valueOf(
            UnicodeSupport.truncateToWidth(str(args, 1), args.checkint(2)))));

        return api;
    }

    private static String str(final Varargs args, final int index) {
        return LuaValues.toString(args.checkvalue(index));
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Turns a lambda into a Lua callable, translating Java exceptions into Lua errors so a
     * component throwing does not take the machine thread down.
     */
    private static LuaValue fn(final NativeFunction body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(final Varargs args) {
                try {
                    return body.invoke(args);
                } catch (final LuaError e) {
                    throw e;
                } catch (final Exception e) {
                    throw new LuaError(describe(e));
                }
            }
        };
    }

    @FunctionalInterface
    private interface NativeFunction {
        Varargs invoke(Varargs args) throws Exception;
    }

    /**
     * The debug hook that enforces both deadlines.
     * <p>
     * Yielding from a hook is not something you can do in stock Lua, where a hook runs on the C
     * stack. In LuaJ the coroutine is a Java thread and the yield simply parks it, so this is a
     * genuine preemption point: the machine stops wherever it happens to be and resumes there.
     */
    private final class DeadlineHook extends VarArgFunction {
        @Override
        public Varargs invoke(final Varargs args) {
            final Globals globals = LuaJArchitecture.this.globals;
            if (globals == null) {
                return LuaValue.NONE;
            }

            final long now = System.nanoTime();

            if (nonYieldingNanos + (now - sliceStart) >= hardDeadlineNanos) {
                // Yield rather than raise. An error here would be a Lua error like any other, and
                // a program wrapping its main loop in pcall, which OpenOS does, would swallow it
                // and carry straight on. A yield cannot be caught: it unwinds to Java, which
                // reports the failure and stops the machine for good.
                globals.yield(LuaValue.varargsOf(
                    LuaValue.valueOf(SystemYield.KILL), LuaValue.NIL));
                return LuaValue.NONE;
            }

            if (now - sliceDeadline >= 0) {
                globals.yield(LuaValue.varargsOf(
                    LuaValue.valueOf(SystemYield.PREEMPT), LuaValue.NIL));
            }

            return LuaValue.NONE;
        }
    }
}
