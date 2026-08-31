/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.net;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.Value;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The handle {@code internet.connect} returns: a raw TCP socket.
 * <p>
 * Two things are kept off the calling thread. The policy check resolves the host name, and the
 * connect that follows waits on a remote peer; neither is something to do from a tick, so both
 * happen on a worker and the handle reports progress instead. Once connected, the channel is
 * non-blocking, so reads and writes can run on the machine thread without ever parking it.
 * <p>
 * Programs poll {@code finishConnect} until it returns true, then read in a loop, treating an
 * empty string as "nothing yet" and nil as "the peer hung up". That is the contract
 * OpenComputers 1 uses, and OpenOS's {@code internet} library is written against it.
 */
public final class TcpSocketValue implements Value {
    private final String id = UUID.randomUUID().toString();
    private final Future<SocketChannel> pendingChannel;
    private final int maxReadSize;

    @Nullable private volatile SocketChannel channel;
    private volatile boolean closed;

    ///////////////////////////////////////////////////////////////////

    public TcpSocketValue(final ExecutorService executor, final InternetPolicy policy,
                          final String host, final int port) {
        this.maxReadSize = Math.min(policy.getMaxResponseSize(), 64 * 1024);
        this.pendingChannel = executor.submit(() -> {
            // Resolving the host is part of deciding whether the connection is allowed at all: a
            // name pointing at a private address has to be rejected just as a literal one would.
            final String denied = policy.checkAllowed(host, port);
            if (denied != null) {
                throw new IOException(denied);
            }

            final SocketChannel opened = SocketChannel.open();
            opened.configureBlocking(false);
            opened.connect(new InetSocketAddress(host, port));
            return opened;
        });
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 32, doc = "function():boolean -- Whether the connection has been established. Poll this before reading.")
    public Object[] finishConnect(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }

        final SocketChannel open;
        try {
            open = resolveChannel();
        } catch (final IOException e) {
            dispose();
            return new Object[]{null, describe(e)};
        }
        if (open == null) {
            return new Object[]{false};
        }

        try {
            return new Object[]{open.isConnected() || open.finishConnect()};
        } catch (final IOException e) {
            dispose();
            return new Object[]{null, describe(e)};
        }
    }

    @Callback(direct = true, limit = 64, doc = "function([n:number]):string or nil -- Reads up to n bytes. Empty means nothing available yet, nil means the peer closed.")
    public Object[] read(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }

        final double requested = args.optDouble(0, maxReadSize);
        final int count = (int) Math.max(0, Math.min(
            Double.isInfinite(requested) ? maxReadSize : requested, maxReadSize));
        if (count == 0) {
            return new Object[]{new byte[0]};
        }

        try {
            final SocketChannel open = resolveChannel();
            if (open == null || (!open.isConnected() && !open.finishConnect())) {
                return new Object[]{new byte[0]};
            }

            final ByteBuffer buffer = ByteBuffer.allocate(count);
            final int read = open.read(buffer);
            if (read < 0) {
                // End of stream: the peer is done sending.
                return new Object[]{null};
            }

            final byte[] result = new byte[read];
            buffer.flip();
            buffer.get(result);
            return new Object[]{result};
        } catch (final IOException e) {
            dispose();
            return new Object[]{null, describe(e)};
        }
    }

    @Callback(direct = true, limit = 64, doc = "function(data:string):number -- Writes bytes. Returns how many were accepted.")
    public Object[] write(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }

        final byte[] data = args.checkByteArray(0);
        try {
            final SocketChannel open = resolveChannel();
            if (open == null || (!open.isConnected() && !open.finishConnect())) {
                return new Object[]{0};
            }
            // A non-blocking write can be partial. Reporting the real count rather than pretending
            // it all went is what lets the caller resume from the right offset.
            return new Object[]{open.write(ByteBuffer.wrap(data))};
        } catch (final IOException e) {
            dispose();
            return new Object[]{null, describe(e)};
        }
    }

    @Callback(direct = true, limit = 16, doc = "function():string -- The unique identifier of this socket.")
    public Object[] id(final Context context, final Arguments args) {
        return new Object[]{id};
    }

    @Callback(direct = true, limit = 16, doc = "function() -- Closes the socket.")
    public Object[] close(final Context context, final Arguments args) {
        dispose();
        return null;
    }

    @Override
    public void dispose() {
        closed = true;
        pendingChannel.cancel(true);

        final SocketChannel open = channel;
        channel = null;
        if (open != null) {
            try {
                open.close();
            } catch (final IOException ignored) {
                // Already broken; there is nothing useful left to do with it.
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * The channel, or {@code null} while the worker is still resolving and connecting.
     *
     * @throws IOException if the connection was refused by the policy or failed to open.
     */
    @Nullable
    private SocketChannel resolveChannel() throws IOException {
        final SocketChannel existing = channel;
        if (existing != null) {
            return existing;
        }
        if (!pendingChannel.isDone()) {
            return null;
        }

        try {
            final SocketChannel opened = pendingChannel.get();
            channel = opened;
            return opened;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof final IOException io ? io : new IOException(describe(cause));
        }
    }

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
