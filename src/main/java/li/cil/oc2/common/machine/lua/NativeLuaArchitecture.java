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
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua53.Lua53;
import party.iroiro.luajava.lua53.Lua53Consts;
import party.iroiro.luajava.lua53.Lua53Natives;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The same machine as {@link LuaJArchitecture}, on a real Lua 5.3 rather than a Java one.
 * <p>
 * It exists because MineOS does not fit in a pure Java Lua. Not for want of features: LuaJ's
 * compiler counts local variables across the whole enclosing chain rather than per function, so a
 * file with many top level locals and a nested function of its own is rejected with "too many local
 * variables" even though the standard compiler accepts it happily. Several of MineOS's libraries
 * are shaped exactly like that, and since every pure Java Lua in circulation is either LuaJ or a
 * fork of it, the only way past it is the real compiler.
 * <p>
 * Being real Lua changes three things about how the machine is driven.
 * <p>
 * There is no preemption. A hook in real Lua runs inside a C call and Lua will not yield across
 * one, so a slice runs until the program yields of its own accord or the deadline kills it. Slices
 * run on a worker thread, so a long one costs the machine that ran it and nothing else, which is
 * exactly the arrangement OpenComputers 1 uses.
 * <p>
 * The deadline is enforced from Lua. {@code native.lua} installs a hook that asks
 * {@code checkdeadline} how things stand and raises when they are bad, re-arming itself to fire on
 * every instruction so the error cannot be pcalled away. Java keeps the last word regardless: a
 * slice that comes back from a tripped deadline is reported as a crash however it ended.
 * <p>
 * Memory is measured rather than estimated. Real Lua knows what its heap holds, so
 * {@code computer.freeMemory} finally answers with something true.
 */
public final class NativeLuaArchitecture implements LuaArchitecture {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String MACHINE_SCRIPT = "/assets/oc2r/lua/machine.lua";
    private static final String NATIVE_SCRIPT = "/assets/oc2r/lua/native.lua";

    /**
     * The dialect this reports to programs. MineOS compiles a bitwise fast path when the answer is
     * not {@code "Lua 5.2"}, which is code this VM runs and the Java one cannot even parse.
     */
    public static final String ARCHITECTURE_NAME = "Lua 5.3";

    /**
     * How much execution time a machine may accumulate without yielding before it is killed.
     * Generous, because unpacking an operating system image takes a while, but finite, because a
     * {@code while true do end} would otherwise hold a worker thread indefinitely.
     */
    private static final long DEFAULT_HARD_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Bytecode instructions between hook invocations. Each one crosses into Java, so this wants to
     * be large enough not to show up in a profile and small enough that a runaway program is
     * noticed promptly.
     */
    private static final int HOOK_INTERVAL = 10_000;

    /**
     * Shortest gap between forced collections when the heap is over its ceiling.
     */
    private static final long COLLECT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    ///////////////////////////////////////////////////////////////////

    private final LuaMachine machine;

    /**
     * An explicit no-yield budget, or zero to take the host's. Tests pin it; a machine in the world
     * asks the host at the start of every slice, so changing the server config takes effect
     * without a restart.
     */
    private final long hardDeadlineOverrideNanos;

    /**
     * The budget in force for the slice currently running, and the memory ceiling alongside it,
     * both read once at its start so the hook does not go back to the host every ten thousand
     * instructions.
     */
    private long hardDeadlineNanos = DEFAULT_HARD_DEADLINE_NANOS;
    private int memoryCeiling = Integer.MAX_VALUE;

    private final NativeLuaValues values = new NativeLuaValues(this::pushValue);

    /**
     * Guards the state's lifetime. The server thread may ask for the machine to be torn down while
     * a slice is still running on a worker, and closing a Lua state out from under a thread that is
     * executing in it would take the process with it.
     */
    private final Object lifecycle = new Object();

    @Nullable private Lua lua;
    private int trampolineRef = -1;
    private boolean sliceRunning;
    private boolean closeRequested;

    private long sliceStart;

    /**
     * When the heap was last collected on purpose, so a machine sitting at its ceiling does not
     * spend its whole slice collecting.
     */
    private long lastCollect;

    /**
     * Set by the hook when it decides the machine is finished. Java reports the crash whatever the
     * slice went on to do, so that a program cannot survive its own deadline by catching the error.
     */
    private volatile boolean deadlineTripped;

    /**
     * Last measured heap size, in bytes. Sampled on the machine thread, because asking a Lua state
     * anything from another thread is not safe.
     */
    private volatile int memoryUsed;

    private volatile boolean acceptingSignals;
    private boolean syncPending;

    // Handoff for the synchronized call currently in flight. Written by the machine thread before
    // it yields, answered by the server thread, read back by the machine thread when it resumes.
    @Nullable private volatile LuaComponent pendingComponent;
    @Nullable private volatile CallbackMethod pendingMethod;
    @Nullable private volatile Arguments pendingArguments;
    @Nullable private volatile Object[] syncResults;
    @Nullable private volatile String syncError;

    ///////////////////////////////////////////////////////////////////

    public NativeLuaArchitecture(final LuaMachine machine) {
        this(machine, 0);
    }

    public NativeLuaArchitecture(final LuaMachine machine, final long hardDeadlineNanos) {
        this.machine = machine;
        this.hardDeadlineOverrideNanos = hardDeadlineNanos;
    }

    /**
     * Whether the native library for this platform could be loaded.
     * <p>
     * Asked once, when the mod decides which backend to use. The natives ship for the desktop
     * platforms Minecraft runs on, but a server on something else should fall back rather than
     * refuse to start.
     */
    public static boolean isAvailable() {
        try (final Lua probe = new Lua53()) {
            return probe.getPointer() != 0;
        } catch (final Throwable e) {
            LOGGER.info("Native Lua is unavailable, falling back to the Java implementation.", e);
            return false;
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public String getName() {
        return ARCHITECTURE_NAME;
    }

    @Override
    public boolean initialize() {
        Lua state = null;
        try {
            state = new Lua53();
            state.openLibraries();

            pushNativeApi(state);
            state.setGlobal("_JAVA");

            // The preamble fills in the parts of the native table that only Lua can provide, so it
            // has to run while _JAVA is still there and before machine.lua reads it.
            run(state, NATIVE_SCRIPT, "=native", 0);
            run(state, MACHINE_SCRIPT, "=machine", 1);

            if (!state.isFunction(-1)) {
                LOGGER.error("machine.lua did not return a function.");
                state.close();
                return false;
            }

            // Parked in the registry rather than a global: a program able to reach the resume
            // function could re-enter its own machine.
            trampolineRef = state.ref();

            state.pushNil();
            state.setGlobal("_JAVA");
            state.setTop(0);

            synchronized (lifecycle) {
                if (closeRequested) {
                    state.close();
                    return false;
                }
                lua = state;
            }

            acceptingSignals = false;
            sample(state);

            return true;
        } catch (final Throwable e) {
            LOGGER.error("Failed initializing Lua machine.", e);
            if (state != null) {
                try {
                    state.close();
                } catch (final Throwable ignored) {
                    // Nothing useful to do about a state that will not close.
                }
            }
            return false;
        }
    }

    @Override
    public boolean isInitialized() {
        return lua != null;
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closeRequested = true;
            // A slice that is still running owns the state and closes it on its way out; the hook
            // sees the request on its next tick and stops the machine promptly.
            if (!sliceRunning) {
                closeState();
            }
        }

        pendingComponent = null;
        pendingMethod = null;
        pendingArguments = null;
        syncResults = null;
        syncError = null;
        syncPending = false;
        acceptingSignals = false;
    }

    private void closeState() {
        final Lua state = lua;
        lua = null;
        trampolineRef = -1;
        if (state != null) {
            try {
                state.close();
            } catch (final Throwable e) {
                LOGGER.error("Failed closing Lua state.", e);
            }
        }
    }

    @Override
    public boolean isAcceptingSignals() {
        return acceptingSignals;
    }

    @Override
    public ExecutionResult runThreaded(@Nullable final Signal signal) {
        final Lua state;
        synchronized (lifecycle) {
            state = lua;
            if (state == null || closeRequested) {
                return new ExecutionResult.Error("machine is not initialized");
            }
            sliceRunning = true;
        }

        try {
            return slice(state, signal);
        } finally {
            synchronized (lifecycle) {
                sliceRunning = false;
                if (closeRequested) {
                    closeState();
                }
            }
        }
    }

    private ExecutionResult slice(final Lua state, @Nullable final Signal signal) {
        hardDeadlineNanos = hardDeadlineOverrideNanos > 0 ? hardDeadlineOverrideNanos
            : TimeUnit.MILLISECONDS.toNanos(machine.getHost().getCpuTimeoutMillis());
        memoryCeiling = getMemoryTotal();

        sliceStart = System.nanoTime();
        deadlineTripped = false;
        lastCollect = 0;

        state.setTop(0);
        state.refGet(trampolineRef);

        final int argumentCount;
        if (syncPending) {
            syncPending = false;
            argumentCount = pushSyncResult(state);
        } else if (acceptingSignals && signal != null) {
            argumentCount = pushSignal(state, signal);
        } else {
            argumentCount = 0;
        }
        acceptingSignals = false;

        try {
            // machine.lua answers (ok, kind, payload), so ask for exactly three and let Lua pad.
            state.pCall(argumentCount, 3);
        } catch (final LuaException e) {
            state.setTop(0);
            sample(state);
            return new ExecutionResult.Error(describe(e));
        } catch (final Throwable e) {
            LOGGER.error("Unexpected error resuming Lua machine.", e);
            state.setTop(0);
            return new ExecutionResult.Error(String.valueOf(e.getMessage()));
        }

        final ExecutionResult result = interpret(state);
        state.setTop(0);
        sample(state);

        if (deadlineTripped && !(result instanceof ExecutionResult.Error)) {
            // The program caught the deadline error and carried on. It does not get to.
            return new ExecutionResult.Error("too long without yielding");
        }

        return result;
    }

    private ExecutionResult interpret(final Lua state) {
        if (!state.toBoolean(1)) {
            return new ExecutionResult.Error(state.isNil(3) ? "unknown error" : readString(state, 3));
        }

        if (state.isNil(2)) {
            return new ExecutionResult.Shutdown(false);
        }

        return switch ((int) state.toInteger(2)) {
            case SystemYield.SLEEP -> {
                acceptingSignals = true;
                yield new ExecutionResult.Sleep(secondsToTicks(state.toNumber(3)));
            }
            case SystemYield.SHUTDOWN -> new ExecutionResult.Shutdown(state.toBoolean(3));
            case SystemYield.SYNCHRONIZED_CALL -> {
                syncPending = true;
                yield ExecutionResult.SynchronizedCall.INSTANCE;
            }
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
        // Clamped, because the Lua heap is not actually capped at the installed memory: there is no
        // way to hand a real Lua an allocator that refuses, so the figure is honest right up to the
        // limit and then stops rather than reporting negative free memory.
        return Math.min(memoryUsed, getMemoryTotal());
    }

    /**
     * Reads the real heap size. Only ever called from the machine thread, because asking a Lua
     * state anything from another one is not safe; the answer is published for whoever else wants
     * it through {@link #getMemoryUsed()}.
     */
    private void sample(final Lua state) {
        try {
            final int kibibytes = ((Lua53Natives) state.getLuaNatives())
                .lua_gc(state.getPointer(), Lua53Consts.LUA_GCCOUNT, 0);
            memoryUsed = kibibytes > 0 ? kibibytes * 1024 : 0;
        } catch (final Throwable e) {
            // Not worth failing a slice over.
            LOGGER.debug("Failed sampling Lua memory use.", e);
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static void run(final Lua state, final String resource, final String name, final int results)
        throws IOException {
        final byte[] source;
        try (final InputStream stream = NativeLuaArchitecture.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing script [" + resource + "].");
            }
            source = stream.readAllBytes();
        }

        final ByteBuffer buffer = ByteBuffer.allocateDirect(source.length).order(ByteOrder.nativeOrder());
        buffer.put(source).flip();

        state.load(buffer, name);
        state.pCall(0, results);
    }

    private int pushSignal(final Lua state, final Signal signal) {
        final Object[] args = signal.args();
        state.checkStack(args.length + 4);
        values.pushString(state, signal.name());
        for (final Object arg : args) {
            values.push(state, arg);
        }
        return args.length + 1;
    }

    /**
     * The answer to a deferred component call, shaped the way the kernel's invoke wrapper reads it:
     * {@code (true, results...)} or {@code (false, message)}.
     */
    private int pushSyncResult(final Lua state) {
        final String error = syncError;
        final Object[] results = syncResults;
        syncError = null;
        syncResults = null;

        if (error != null) {
            state.checkStack(2);
            state.push(false);
            values.pushString(state, error);
            return 2;
        }

        final int count = results == null ? 0 : results.length;
        state.checkStack(count + 4);
        state.push(true);
        for (int i = 0; i < count; i++) {
            values.push(state, results[i]);
        }
        return count + 1;
    }

    private String readString(final Lua state, final int index) {
        try {
            return new String(values.toBytes(state, index), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final Throwable e) {
            return String.valueOf(state.toString(index));
        }
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

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    ///////////////////////////////////////////////////////////////////
    // The native table

    /**
     * Wraps a {@link Value} in a table of bound closures, which is how Lua sees objects such as the
     * handle {@code internet.request} returns.
     */
    private void pushValue(final Lua state, final Value value) {
        state.checkStack(3);
        state.newTable();
        for (final Map.Entry<String, CallbackMethod> entry : Callbacks.collect(value).entrySet()) {
            final CallbackMethod method = entry.getValue();
            state.push(fn(inner -> {
                // Values are always called on the machine thread. They exist for short lived,
                // thread safe handles; anything needing the server thread belongs on a component,
                // where the direct flag can route it properly.
                final Arguments arguments = new NativeArguments(values.snapshot(inner, 1), 0);
                return push(inner, method.invoke(value, machine.getDirectContext(), arguments));
            }));
            state.setField(-2, entry.getKey());
        }
    }

    /**
     * The table of raw Java functions handed to {@code machine.lua}. Everything the sandbox can
     * eventually reach comes from here.
     */
    private void pushNativeApi(final Lua state) {
        state.checkStack(4);
        state.newTable();

        pushComponentApi(state);
        state.setField(-2, "component");

        pushComputerApi(state);
        state.setField(-2, "computer");

        pushUnicodeApi(state);
        state.setField(-2, "unicode");

        state.push(fn(inner -> {
            final String reason = deadlineReason(inner);
            if (reason == null) {
                return 0;
            }
            // (message, fatal): a fatal reason has the hook re-arm itself so the error cannot be
            // caught, which is the only way to stop a program that will not stop itself.
            values.pushString(inner, reason);
            inner.push(deadlineTripped);
            return 2;
        }));
        state.setField(-2, "checkdeadline");

        state.push(HOOK_INTERVAL);
        state.setField(-2, "HOOK_INTERVAL");

        // The kernel yields these back; Java owns the numbering.
        state.push(SystemYield.SLEEP);
        state.setField(-2, "KIND_SLEEP");
        state.push(SystemYield.SHUTDOWN);
        state.setField(-2, "KIND_SHUTDOWN");
        state.push(SystemYield.SYNCHRONIZED_CALL);
        state.setField(-2, "KIND_SYNCHRONIZED_CALL");
    }

    /**
     * Why the machine should stop, or null to carry on. Asked by the hook every
     * {@link #HOOK_INTERVAL} instructions.
     * <p>
     * Sets {@link #deadlineTripped} for the reasons the machine does not get to survive; a reason
     * raised without it is an ordinary Lua error the program may catch, which is how running out
     * of memory behaves in OpenComputers 1 and what operating systems there expect.
     */
    @Nullable
    private String deadlineReason(final Lua state) {
        if (closeRequested) {
            // The host tore the machine down while this slice was running. Unwinding is how the
            // worker thread lets go of a state that is about to be closed.
            deadlineTripped = true;
            return "machine stopped";
        }

        if (System.nanoTime() - sliceStart >= hardDeadlineNanos) {
            deadlineTripped = true;
            return "too long without yielding";
        }

        if (isOverMemoryCeiling(state)) {
            return "not enough memory";
        }

        return null;
    }

    /**
     * Whether the Lua heap has outgrown the machine's installed memory.
     * <p>
     * There is no way to hand a real Lua an allocator that refuses, so the ceiling has to be
     * checked rather than imposed. That makes it worth being sure before acting on it: a heap over
     * the line is usually a heap that simply has not been collected yet, so the reading only counts
     * once a full collection has failed to bring it back. Collections are rate limited, because one
     * every ten thousand instructions would cost far more than the memory it saves.
     * <p>
     * Without this {@code computer.totalMemory} would be a number the machine reports and nothing
     * honours, and a single Lua program could take as much of the server's memory as it liked.
     */
    private boolean isOverMemoryCeiling(final Lua state) {
        sample(state);
        if (memoryUsed <= memoryCeiling) {
            return false;
        }

        final long now = System.nanoTime();
        if (lastCollect != 0 && now - lastCollect < COLLECT_INTERVAL_NANOS) {
            // Already collected recently and still over; say so without paying for it again.
            return true;
        }

        return collect(state) > memoryCeiling;
    }

    /**
     * Collects the Lua heap and returns what is left, in bytes.
     */
    private int collect(final Lua state) {
        lastCollect = System.nanoTime();
        try {
            ((Lua53Natives) state.getLuaNatives())
                .lua_gc(state.getPointer(), Lua53Consts.LUA_GCCOLLECT, 0);
        } catch (final Throwable e) {
            LOGGER.debug("Failed collecting the Lua heap.", e);
            return 0;
        }
        sample(state);
        return memoryUsed;
    }

    private void pushComponentApi(final Lua state) {
        final ComponentBus bus = machine.getBus();
        state.checkStack(3);
        state.newTable();

        state.push(fn(inner -> {
            final NativeArguments args = arguments(inner);
            final String filter = args.isDefined(0) ? args.checkString(0) : null;
            final boolean exact = args.optBoolean(1, false);
            inner.checkStack(3);
            inner.newTable();
            bus.list(filter, exact).forEach((address, type) -> {
                values.pushString(inner, address);
                values.pushString(inner, type);
                inner.rawSet(-3);
            });
            return 1;
        }));
        state.setField(-2, "list");

        state.push(fn(inner -> {
            final String address = arguments(inner).checkString(0);
            return bus.getComponent(address)
                .map(component -> push(inner, component.getComponentType()))
                .orElseGet(() -> noSuchComponent(inner));
        }));
        state.setField(-2, "type");

        state.push(fn(inner -> {
            final String address = arguments(inner).checkString(0);
            return bus.getComponent(address)
                .map(component -> push(inner, component.getComponentSlot()))
                .orElseGet(() -> noSuchComponent(inner));
        }));
        state.setField(-2, "slot");

        state.push(fn(inner -> {
            final Map<String, Boolean> methods = bus.methods(arguments(inner).checkString(0));
            if (methods == null) {
                return noSuchComponent(inner);
            }
            inner.checkStack(3);
            inner.newTable();
            methods.forEach((name, direct) -> {
                values.pushString(inner, name);
                inner.push(direct.booleanValue());
                inner.rawSet(-3);
            });
            return 1;
        }));
        state.setField(-2, "methods");

        state.push(fn(inner -> {
            final NativeArguments args = arguments(inner);
            final CallbackMethod method = bus.lookup(args.checkString(0), args.checkString(1));
            if (method == null) {
                inner.pushNil();
            } else {
                values.pushString(inner, method.getDoc());
            }
            return 1;
        }));
        state.setField(-2, "doc");

        state.push(fn(this::invokeComponent));
        state.setField(-2, "invoke");

        // Retained for compatibility with programs that still probe for the removed field API.
        state.push(fn(inner -> {
            inner.newTable();
            return 1;
        }));
        state.setField(-2, "fields");
    }

    private int invokeComponent(final Lua state) throws Throwable {
        final NativeArguments all = arguments(state);
        final String address = all.checkString(0);
        final String methodName = all.checkString(1);

        final ComponentBus bus = machine.getBus();
        final LuaComponent component = bus.getComponent(address).orElse(null);
        if (component == null) {
            throw new IllegalArgumentException("no such component");
        }

        final CallbackMethod method = Callbacks.collect(component).get(methodName);
        if (method == null) {
            throw new IllegalArgumentException("no such method");
        }

        final Arguments arguments = all.skip(2);

        if (machine.getDirectCallBudget().tryConsume(address, method)) {
            // (true, results...) or (false, message): a component that fails should surface at the
            // call site, and the kernel cannot tell the two apart without the flag.
            state.checkStack(2);
            try {
                final Object[] results = method.invoke(component, machine.getDirectContext(), arguments);
                state.push(true);
                return 1 + push(state, results);
            } catch (final Throwable e) {
                state.push(false);
                values.pushString(state, describe(e));
                return 2;
            }
        }

        // Either the method is not thread safe or it has used up its direct call allowance for this
        // tick. Stash it and return no values at all: that is the signal machine.lua turns into a
        // system yield, which hands the call to the server thread.
        pendingComponent = component;
        pendingMethod = method;
        pendingArguments = arguments;

        return 0;
    }

    private void pushComputerApi(final Lua state) {
        state.checkStack(3);
        state.newTable();

        pushFunction(state, "address", inner -> push(inner, machine.getAddress()));
        pushFunction(state, "tmpAddress", inner -> push(inner, machine.getHost().getTmpAddress()));
        pushFunction(state, "totalMemory", inner -> push(inner, getMemoryTotal()));
        pushFunction(state, "freeMemory", inner -> {
            // Take a fresh reading rather than the cached one. A program watching its own memory
            // does so from inside a slice, and a slice can allocate a great deal without ever
            // reaching the point where the cached figure is refreshed.
            sample(inner);
            if (memoryUsed > memoryCeiling) {
                // Over the line, which is usually a heap that has not been collected rather than
                // one that is actually full. A program asking this question has just freed
                // something and wants to know whether it worked, so answer it properly: this is an
                // explicit call, not the hook, so it can afford the collection.
                collect(inner);
            }
            return push(inner, Math.max(0, getMemoryTotal() - getMemoryUsed()));
        });
        pushFunction(state, "energy", inner -> push(inner, machine.getHost().getEnergyStored()));
        pushFunction(state, "maxEnergy", inner -> push(inner, machine.getHost().getEnergyCapacity()));
        pushFunction(state, "uptime", inner -> push(inner, machine.getUptime()));
        pushFunction(state, "realTime", inner -> push(inner, System.currentTimeMillis() / 1000.0));

        pushFunction(state, "pushSignal", inner -> {
            final String name = arguments(inner).checkString(0);
            return push(inner, machine.signal(name, values.toJavaArray(inner, 2)));
        });

        pushFunction(state, "beep", inner -> {
            // Sound has to be played from the server thread, and this is a direct call, so it goes
            // through the machine's queue and is flushed on the next tick.
            final NativeArguments args = arguments(inner);
            if (args.isString(0) && !args.isNumber(0)) {
                // computer.beep("...-") is the pattern form; the OpenOS shipped one only varies
                // duration, so a single tone of the right total length is the honest rendering.
                final String pattern = args.checkString(0);
                machine.queueBeep(1000, Math.max(0.05, pattern.length() * 0.1));
            } else {
                machine.queueBeep(args.optInteger(0, 1000), args.optDouble(1, 0.1));
            }
            return 0;
        });

        pushFunction(state, "getDeviceInfo", inner -> {
            inner.checkStack(4);
            inner.newTable();
            for (final LuaComponent component : machine.getComponents()) {
                values.pushString(inner, component.getComponentAddress());
                inner.newTable();
                values.pushString(inner, component.getComponentType());
                inner.setField(-2, "class");
                values.pushString(inner, component.getComponentType());
                inner.setField(-2, "description");
                inner.rawSet(-3);
            }
            return 1;
        });

        // Programs use these to decide which dialect to emit.
        pushFunction(state, "getArchitecture", inner -> push(inner, machine.getArchitectureName()));
        pushFunction(state, "getArchitectures", inner -> {
            inner.checkStack(2);
            inner.newTable();
            values.pushString(inner, machine.getArchitectureName());
            inner.rawSetI(-2, 1);
            return 1;
        });
        pushFunction(state, "setArchitecture", inner -> {
            // There is only one, so this either changes nothing or asks for something that does not
            // exist. Either way the machine does not reboot.
            final String requested = arguments(inner).checkString(0);
            if (requested.equals(machine.getArchitectureName())) {
                inner.push(true);
                return 1;
            }
            inner.pushNil();
            values.pushString(inner, "unknown architecture");
            return 2;
        });
        pushFunction(state, "getProgramLocations", inner -> {
            inner.newTable();
            return 1;
        });

        pushFunction(state, "users", inner -> {
            int count = 0;
            for (final String user : machine.getUsers()) {
                inner.checkStack(2);
                values.pushString(inner, user);
                count++;
            }
            return count;
        });
        pushFunction(state, "addUser", inner -> {
            if (machine.addUser(arguments(inner).checkString(0))) {
                inner.push(true);
                return 1;
            }
            inner.pushNil();
            values.pushString(inner, "player is already registered");
            return 2;
        });
        pushFunction(state, "removeUser", inner ->
            push(inner, machine.removeUser(arguments(inner).checkString(0))));
    }

    private void pushUnicodeApi(final Lua state) {
        state.checkStack(3);
        state.newTable();

        pushFunction(state, "char", inner -> {
            final NativeArguments args = arguments(inner);
            final StringBuilder builder = new StringBuilder(args.count());
            for (int i = 0; i < args.count(); i++) {
                builder.appendCodePoint(args.checkInteger(i));
            }
            return push(inner, builder.toString());
        });
        pushFunction(state, "len", inner ->
            push(inner, UnicodeSupport.length(arguments(inner).checkString(0))));
        pushFunction(state, "lower", inner ->
            push(inner, arguments(inner).checkString(0).toLowerCase()));
        pushFunction(state, "upper", inner ->
            push(inner, arguments(inner).checkString(0).toUpperCase()));
        pushFunction(state, "reverse", inner ->
            push(inner, UnicodeSupport.reverse(arguments(inner).checkString(0))));
        pushFunction(state, "sub", inner -> {
            final NativeArguments args = arguments(inner);
            final String value = args.checkString(0);
            return push(inner, UnicodeSupport.sub(value, args.checkInteger(1),
                args.optInteger(2, UnicodeSupport.length(value))));
        });
        pushFunction(state, "isWide", inner ->
            push(inner, UnicodeSupport.isWide(arguments(inner).checkString(0))));
        pushFunction(state, "charWidth", inner -> {
            final String value = arguments(inner).checkString(0);
            return push(inner, value.isEmpty() ? 0 : UnicodeSupport.charWidth(value.codePointAt(0)));
        });
        pushFunction(state, "wlen", inner ->
            push(inner, UnicodeSupport.displayWidth(arguments(inner).checkString(0))));
        pushFunction(state, "wtrunc", inner -> {
            final NativeArguments args = arguments(inner);
            return push(inner, UnicodeSupport.truncateToWidth(args.checkString(0), args.checkInteger(1)));
        });
    }

    ///////////////////////////////////////////////////////////////////

    private NativeArguments arguments(final Lua state) {
        return new NativeArguments(values.snapshot(state, 1), 0);
    }

    private int push(final Lua state, @Nullable final Object value) {
        state.checkStack(2);
        values.push(state, value);
        return 1;
    }

    private int push(final Lua state, @Nullable final Object[] results) {
        if (results == null) {
            return 0;
        }
        state.checkStack(results.length + 2);
        for (final Object result : results) {
            values.push(state, result);
        }
        return results.length;
    }

    private int noSuchComponent(final Lua state) {
        state.checkStack(2);
        state.pushNil();
        values.pushString(state, "no such component");
        return 2;
    }

    private void pushFunction(final Lua state, final String name, final NativeFunction body) {
        state.push(fn(body));
        state.setField(-2, name);
    }

    /**
     * Turns a lambda into a Lua callable, translating Java exceptions into Lua errors so a
     * component throwing does not take the machine thread down.
     */
    private static JFunction fn(final NativeFunction body) {
        return state -> {
            try {
                return body.invoke(state);
            } catch (final Throwable e) {
                throw new MachineError(describe(e));
            }
        };
    }

    @FunctionalInterface
    private interface NativeFunction {
        int invoke(Lua state) throws Throwable;
    }

    /**
     * The error a native function raises.
     * <p>
     * The binding turns a Java exception into a Lua error by way of {@code toString}, so a plain
     * exception would reach the program as "java.lang.IllegalArgumentException: bad argument #1".
     * Programs match on these messages, and the class name has no business being in one.
     */
    private static final class MachineError extends RuntimeException {
        MachineError(final String message) {
            super(message, null, false, false);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
