/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.net;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.Value;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;

/**
 * The handle {@code internet.connect} returns: a raw TCP socket.
 * <p>
 * The channel is non-blocking throughout, which is what lets every call here run on the machine
 * thread. A blocking socket would mean a read on a quiet connection parks the tick it was
 * scheduled in until the peer says something, and a peer that says nothing would park it forever.
 * <p>
 * Programs are expected to poll {@code finishConnect} until it returns true, then read in a loop,
 * treating an empty string as "nothing yet" and nil as "the peer hung up". That is the same
 * contract OpenComputers 1 uses, and OpenOS's {@code internet} library is written against it.
 */
public final class TcpSocketValue implements Value {
    private final String id = UUID.randomUUID().toString();
    private final SocketChannel channel;
    private final int maxReadSize;
    private volatile boolean closed;

    ///////////////////////////////////////////////////////////////////

    public TcpSocketValue(final InternetPolicy policy, final String host, final int port) throws IOException {
        final String denied = policy.checkAllowed(host, port);
        if (denied != null) {
            throw new IOException(denied);
        }

        this.maxReadSize = Math.min(policy.getMaxResponseSize(), 64 * 1024);
        this.channel = SocketChannel.open();
        this.channel.configureBlocking(false);
        this.channel.connect(new InetSocketAddress(host, port));
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 32, doc = "function():boolean -- Whether the connection has been established. Poll this before reading.")
    public Object[] finishConnect(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }
        try {
            return new Object[]{channel.isConnected() || channel.finishConnect()};
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
            if (!channel.isConnected() && !channel.finishConnect()) {
                return new Object[]{new byte[0]};
            }

            final ByteBuffer buffer = ByteBuffer.allocate(count);
            final int read = channel.read(buffer);
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
            if (!channel.isConnected() && !channel.finishConnect()) {
                return new Object[]{0};
            }
            // A non-blocking write can be partial. Reporting the real count rather than pretending
            // it all went is what lets the caller resume from the right offset.
            return new Object[]{channel.write(ByteBuffer.wrap(data))};
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
        try {
            channel.close();
        } catch (final IOException ignored) {
            // Already broken; there is nothing useful left to do with it.
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static String describe(final IOException e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
