/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.lua;

import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.ExecutionResult;
import li.cil.oc2.api.machine.LuaComponent;
import li.cil.oc2.api.machine.Machine;
import li.cil.oc2.api.machine.MachineHost;
import li.cil.oc2.api.machine.Signal;
import li.cil.oc2.common.machine.bus.ComponentBus;
import li.cil.oc2.common.machine.bus.DirectCallBudget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Scheduler and lifecycle for an OpenComputers 1 compatible Lua machine.
 * <p>
 * Work is split across two threads. {@link #tick()} runs on the server thread: it refills direct
 * call budgets, rescans the component bus, charges energy, services synchronized calls and decides
 * whether the machine deserves another time slice. The slice itself runs on a worker thread, where
 * it may not touch anything the server owns.
 * <p>
 * The two never run at the same time. {@link #tick()} returns immediately while a slice is in
 * flight, so a slow machine slows only itself.
 */
public final class LuaMachine implements Machine {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("OC1 Lua Machine");
        return thread;
    });

    private static final int TICKS_PER_SECOND = 20;

    /**
     * Where the machine is in its life cycle. Transitions happen on the server thread except for
     * those {@link #applyResult(ExecutionResult, int)} makes at the end of a time slice.
     */
    public enum State {
        /**
         * Powered off. No Lua state exists.
         */
        STOPPED,
        /**
         * Powered on but not yet initialized; the Lua state is built on the next tick.
         */
        STARTING,
        /**
         * Ready to be handed a time slice.
         */
        RUNNING,
        /**
         * Parked in {@code computer.pullSignal}, waiting for a signal or for the timeout.
         */
        SLEEPING,
        /**
         * Waiting for the server thread to run an indirect component call.
         */
        SYNCHRONIZED_CALL,
        /**
         * Shutting down; the Lua state is torn down on the next tick.
         */
        STOPPING,
        /**
         * Shutting down and coming straight back up.
         */
        RESTARTING,
    }

    private record Beep(int frequency, double duration) {
    }

    ///////////////////////////////////////////////////////////////////

    private final String address;
    private final MachineHost host;
    private final ComponentBus bus;
    private final SignalQueue signals = new SignalQueue();
    private final DirectCallBudget directCallBudget = new DirectCallBudget();
    private final LuaArchitecture architecture;
    private final Context directContext = new MachineContext(false);
    private final Context synchronizedContext = new MachineContext(true);
    private final Queue<Beep> beeps = new ConcurrentLinkedQueue<>();
    private final Set<String> users = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicReference<String> crashMessage = new AtomicReference<>();

    private final Object stateLock = new Object();
    private volatile State state = State.STOPPED;
    private volatile long uptimeTicks;
    private long wakeAtTick;
    private boolean paused;

    /**
     * Bumped whenever the machine stops or starts, so a time slice that was already in flight
     * cannot write its outcome into the state of a machine that has since moved on.
     */
    private volatile int runGeneration;

    @Nullable private Future<?> pendingSlice;

    ///////////////////////////////////////////////////////////////////

    public LuaMachine(final MachineHost host) {
        this(host, UUID.randomUUID().toString(), LuaJArchitecture::new);
    }

    public LuaMachine(final MachineHost host, final String address) {
        this(host, address, LuaJArchitecture::new);
    }

    public LuaMachine(final MachineHost host, final String address,
                      final Function<LuaMachine, LuaArchitecture> architectureFactory) {
        this.host = host;
        this.address = address;
        this.bus = new ComponentBus(this);
        this.architecture = architectureFactory.apply(this);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public String getAddress() {
        return address;
    }

    @Override
    public MachineHost getHost() {
        return host;
    }

    public ComponentBus getBus() {
        return bus;
    }

    public DirectCallBudget getDirectCallBudget() {
        return directCallBudget;
    }

    public Context getDirectContext() {
        return directContext;
    }

    public Context getSynchronizedContext() {
        return synchronizedContext;
    }

    public State getState() {
        return state;
    }

    /**
     * Whether a time slice is currently running on a worker thread.
     * <p>
     * {@link #getState()} alone cannot answer this: a machine that was preempted goes back to
     * {@link State#RUNNING} the moment its slice ends, so that state means both "executing" and
     * "ready to execute again". Anything waiting for a machine to come to rest, a save or a test
     * harness, needs to ask this instead.
     */
    public boolean isSliceInFlight() {
        final Future<?> slice = pendingSlice;
        return slice != null && !slice.isDone();
    }

    @Override
    public boolean isRunning() {
        return state != State.STOPPED;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    /**
     * Suspends or resumes scheduling, used when the host stops ticking, for instance because its
     * chunk was unloaded. The Lua state is kept intact.
     */
    public void setPaused(final boolean value) {
        paused = value;
    }

    @Override
    public boolean start() {
        synchronized (stateLock) {
            if (state != State.STOPPED) {
                return false;
            }
            runGeneration++;
            crashMessage.set(null);
            uptimeTicks = 0;
            wakeAtTick = 0;
            signals.clear();
            state = State.STARTING;
        }
        host.onMachineRunStateChanged(true);
        return true;
    }

    @Override
    public boolean stop() {
        synchronized (stateLock) {
            if (state == State.STOPPED) {
                return false;
            }
            // Do not wait for a slice that is still running: it cannot outlive its own deadline,
            // and the generation bump makes whatever it produces irrelevant.
            runGeneration++;
            state = State.STOPPED;
        }

        pendingSlice = null;
        architecture.close();
        bus.clear();
        signals.clear();
        beeps.clear();
        directCallBudget.reset();

        host.onMachineRunStateChanged(false);
        return true;
    }

    @Override
    public boolean signal(final String name, final Object... args) {
        if (state == State.STOPPED) {
            return false;
        }
        try {
            return signals.push(new Signal(name, args));
        } catch (final IllegalArgumentException e) {
            LOGGER.warn("Rejected signal [{}]: {}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public double getUptime() {
        return uptimeTicks / (double) TICKS_PER_SECOND;
    }

    @Override
    @Nullable
    public String getLastError() {
        return crashMessage.get();
    }

    @Override
    public Collection<LuaComponent> getComponents() {
        return bus.getComponents();
    }

    @Override
    public Optional<LuaComponent> getComponent(@Nullable final String address) {
        return bus.getComponent(address);
    }

    public SignalQueue getSignalQueue() {
        return signals;
    }

    public Set<String> getUsers() {
        synchronized (users) {
            return Set.copyOf(users);
        }
    }

    public boolean addUser(final String name) {
        return users.add(name);
    }

    public boolean removeUser(final String name) {
        return users.remove(name);
    }

    /**
     * Queues a note for the host to play. Called from the machine thread, flushed on the next tick.
     */
    public void queueBeep(final int frequency, final double duration) {
        if (beeps.size() < 16) {
            beeps.add(new Beep(frequency, duration));
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void tick() {
        if (pendingSlice != null) {
            if (!pendingSlice.isDone()) {
                return;
            }
            pendingSlice = null;
        }

        directCallBudget.reset();
        flushBeeps();

        final String crash = crashMessage.get();
        if (crash != null && state != State.STOPPED) {
            LOGGER.debug("Machine [{}] crashed: {}", address, crash);
            stop();
            host.onMachineCrashed(crash);
            return;
        }

        if (state == State.STOPPED || paused) {
            return;
        }

        uptimeTicks++;

        if (!host.tryConsumeEnergy(host.getEnergyPerTick())) {
            crashMessage.set("not enough energy");
            return;
        }

        if (state == State.STOPPING) {
            stop();
            return;
        }
        if (state == State.RESTARTING) {
            stop();
            start();
            return;
        }

        // Rescan here rather than in each branch below: it is safe only because no slice is in
        // flight, having returned at the top of this method if one were, so a component cannot be
        // detached out from under a call that is using it.
        bus.setComponents(host.getComponents());

        switch (state) {
            case STARTING -> {
                if (!architecture.initialize()) {
                    crashMessage.set("failed initializing machine");
                    return;
                }
                state = State.RUNNING;
            }
            case SYNCHRONIZED_CALL -> {
                architecture.runSynchronized();
                state = State.RUNNING;
            }
            case SLEEPING -> {
                if (signals.isEmpty() && uptimeTicks < wakeAtTick) {
                    return;
                }
                state = State.RUNNING;
            }
            default -> {
            }
        }

        scheduleSlice();
    }

    @Override
    public void runSynchronized() {
        architecture.runSynchronized();
    }

    @Override
    public ExecutionResult runThreaded() {
        // Only hand over a signal when the machine is actually parked in pullSignal. A preempted
        // machine, or one waiting on a synchronized call, is in the middle of an expression and
        // would drop the signal on the floor.
        final Signal signal = architecture.isAcceptingSignals() ? signals.poll() : null;
        try {
            return architecture.runThreaded(signal);
        } catch (final Throwable e) {
            LOGGER.error("Error running Lua machine [{}].", address, e);
            return new ExecutionResult.Error(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Persists the queued signals so a machine reloads with its pending input intact.
     */
    public List<Signal> getPendingSignals() {
        return signals.toList();
    }

    public void setPendingSignals(final Collection<Signal> pending) {
        signals.setAll(pending);
    }

    public long getUptimeTicks() {
        return uptimeTicks;
    }

    public void setUptimeTicks(final long value) {
        uptimeTicks = value;
    }

    ///////////////////////////////////////////////////////////////////

    private void scheduleSlice() {
        final int generation = runGeneration;
        pendingSlice = WORKERS.submit(() -> {
            final ExecutionResult result;
            try {
                result = runThreaded();
            } catch (final Throwable e) {
                LOGGER.error("Unhandled error in Lua machine [{}].", address, e);
                applyResult(new ExecutionResult.Error(String.valueOf(e.getMessage())), generation);
                return;
            }
            applyResult(result, generation);
        });
    }

    private void applyResult(final ExecutionResult result, final int generation) {
        synchronized (stateLock) {
            if (generation != runGeneration) {
                // The machine was stopped or restarted while this slice was running.
                return;
            }

            if (result instanceof final ExecutionResult.Sleep sleep) {
                state = State.SLEEPING;
                wakeAtTick = uptimeTicks + Math.max(0, sleep.ticks());
            } else if (result instanceof ExecutionResult.Preempted) {
                state = State.RUNNING;
            } else if (result instanceof ExecutionResult.SynchronizedCall) {
                state = State.SYNCHRONIZED_CALL;
            } else if (result instanceof final ExecutionResult.Shutdown shutdown) {
                state = shutdown.reboot() ? State.RESTARTING : State.STOPPING;
            } else if (result instanceof final ExecutionResult.Error error) {
                crashMessage.set(error.message() == null ? "unknown error" : error.message());
                state = State.STOPPING;
            }
        }
    }

    private void flushBeeps() {
        Beep beep;
        while ((beep = beeps.poll()) != null) {
            host.beep(beep.frequency(), beep.duration());
        }
    }

    ///////////////////////////////////////////////////////////////////

    private final class MachineContext implements Context {
        private final boolean isSynchronized;

        MachineContext(final boolean isSynchronized) {
            this.isSynchronized = isSynchronized;
        }

        @Override
        public Machine machine() {
            return LuaMachine.this;
        }

        @Override
        public boolean isSynchronized() {
            return isSynchronized;
        }

        @Override
        public boolean signal(final String name, final Object... args) {
            return LuaMachine.this.signal(name, args);
        }

        @Override
        public boolean consumeEnergy(final double amount) {
            return host.tryConsumeEnergy(amount);
        }

        @Override
        public Collection<LuaComponent> components() {
            return bus.getComponents();
        }

        @Override
        public Optional<LuaComponent> component(@Nullable final String address) {
            return bus.getComponent(address);
        }

        @Override
        public MachineHost host() {
            return host;
        }
    }
}
