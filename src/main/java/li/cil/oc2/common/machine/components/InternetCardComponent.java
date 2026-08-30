/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.components;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.Machine;
import li.cil.oc2.api.machine.Value;
import li.cil.oc2.common.machine.net.HttpRequestValue;
import li.cil.oc2.common.machine.net.InternetPolicy;
import li.cil.oc2.common.machine.net.TcpSocketValue;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@code internet} component: HTTP requests and raw TCP sockets.
 * <p>
 * Neither call does any networking itself. Both hand back a {@link Value} the program polls, so
 * nothing here ever blocks the machine thread, and both go through an {@link InternetPolicy} that
 * the server owns rather than the machine.
 * <p>
 * Open handles are tracked so that a machine which stops, crashes or is broken with a pickaxe does
 * not leave sockets behind. Without that, a program looping on {@code connect} would accumulate
 * file descriptors on the server for as long as it ran.
 */
public final class InternetCardComponent extends AbstractLuaComponent {
    private static final ExecutorService REQUEST_WORKERS = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("OC1 Internet Card");
        return thread;
    });

    private final InternetPolicy policy;
    private final List<Value> handles = new CopyOnWriteArrayList<>();

    ///////////////////////////////////////////////////////////////////

    public InternetCardComponent(final String address, final InternetPolicy policy) {
        super("internet", address);
        this.policy = policy;
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void onDisconnect(final Machine machine) {
        for (final Value handle : handles) {
            handle.dispose();
        }
        handles.clear();
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 16, doc = "function():boolean -- Whether HTTP requests are permitted.")
    public Object[] isHttpEnabled(final Context context, final Arguments args) {
        return new Object[]{policy.isHttpEnabled()};
    }

    @Callback(direct = true, limit = 16, doc = "function():boolean -- Whether TCP connections are permitted.")
    public Object[] isTcpEnabled(final Context context, final Arguments args) {
        return new Object[]{policy.isTcpEnabled()};
    }

    @Callback(doc = "function(url:string[, postData:string[, headers:table[, method:string]]]):table -- Starts an HTTP request and returns a handle to poll.")
    public Object[] request(final Context context, final Arguments args) {
        if (!policy.isHttpEnabled()) {
            return new Object[]{null, "http requests are unavailable"};
        }

        final String url = args.checkString(0);
        final byte[] postData = args.optByteArray(1, null);
        final Map<String, String> headers = toHeaders(args.optTable(2, null));
        final String method = args.optString(3, null);

        final String failure = pruneAndCheckCapacity();
        if (failure != null) {
            return new Object[]{null, failure};
        }

        // The policy is checked again on the worker thread against the resolved host. Doing it
        // here too keeps an obviously bad URL from costing a thread at all.
        try {
            final URI uri = URI.create(url);
            if (uri.getHost() == null) {
                return new Object[]{null, "invalid address"};
            }
        } catch (final IllegalArgumentException e) {
            return new Object[]{null, "invalid address"};
        }

        final HttpRequestValue handle = new HttpRequestValue(
            REQUEST_WORKERS, policy, url, postData, headers, method);
        handles.add(handle);
        return new Object[]{handle};
    }

    @Callback(doc = "function(address:string[, port:number]):table -- Opens a TCP connection and returns a handle to poll.")
    public Object[] connect(final Context context, final Arguments args) {
        if (!policy.isTcpEnabled()) {
            return new Object[]{null, "tcp connections are unavailable"};
        }

        final String address = args.checkString(0);
        final String failure = pruneAndCheckCapacity();
        if (failure != null) {
            return new Object[]{null, failure};
        }

        // OpenComputers accepts either connect("host", port) or connect("host:port"), and OpenOS
        // uses both spellings depending on which library called it.
        String host = address;
        int port = args.optInteger(1, -1);
        final int separator = address.lastIndexOf(':');
        if (port < 0 && separator > 0) {
            try {
                port = Integer.parseInt(address.substring(separator + 1));
                host = address.substring(0, separator);
            } catch (final NumberFormatException e) {
                return new Object[]{null, "invalid port"};
            }
        }
        if (port < 1 || port > 65535) {
            return new Object[]{null, "invalid port"};
        }

        try {
            final TcpSocketValue handle = new TcpSocketValue(policy, host, port);
            handles.add(handle);
            return new Object[]{handle};
        } catch (final IOException e) {
            final String message = e.getMessage();
            return new Object[]{null, message == null || message.isEmpty() ? "connection failed" : message};
        }
    }

    ///////////////////////////////////////////////////////////////////

    /**
     * Drops sockets that have already closed themselves, then reports whether there is room for
     * another. Pruning here rather than on a timer means a well behaved program that closes its
     * handles never hits the limit at all.
     */
    private String pruneAndCheckCapacity() {
        handles.removeIf(handle -> handle instanceof final TcpSocketValue socket && socket.isClosed());
        return handles.size() >= policy.getMaxConnections() ? "too many open connections" : null;
    }

    private static Map<String, String> toHeaders(final Map<?, ?> value) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((key, header) -> {
                if (key != null && header != null) {
                    result.put(String.valueOf(key), String.valueOf(header));
                }
            });
        }
        return result;
    }
}
