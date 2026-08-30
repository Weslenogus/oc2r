/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.net;

import li.cil.oc2.api.machine.Arguments;
import li.cil.oc2.api.machine.Callback;
import li.cil.oc2.api.machine.Context;
import li.cil.oc2.api.machine.Value;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The handle {@code internet.request} returns.
 * <p>
 * The request runs on a worker thread and this object is a view of its progress, because the
 * machine thread must never block: a call that waits on a remote server would hold up the tick it
 * was scheduled in for as long as that server felt like taking.
 * <p>
 * That is why programs poll {@code finishConnect} until it returns true, and why every accessor
 * has to cope with the request not having finished yet.
 */
public final class HttpRequestValue implements Value {
    private record Response(int code, String message, Map<String, Object> headers, byte[] body) {
    }

    private final Future<Response> future;
    private volatile boolean closed;
    private int position;

    ///////////////////////////////////////////////////////////////////

    public HttpRequestValue(final ExecutorService executor,
                            final InternetPolicy policy,
                            final String url,
                            @Nullable final byte[] postData,
                            final Map<String, String> headers,
                            @Nullable final String method) {
        this.future = executor.submit(() -> perform(policy, url, postData, headers, method));
    }

    ///////////////////////////////////////////////////////////////////

    @Callback(direct = true, limit = 32, doc = "function():boolean -- Whether the request has completed. Poll this before reading.")
    public Object[] finishConnect(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }
        if (!future.isDone()) {
            return new Object[]{false};
        }
        try {
            future.get();
            return new Object[]{true};
        } catch (final Exception e) {
            return new Object[]{null, describe(e)};
        }
    }

    @Callback(direct = true, limit = 32, doc = "function():number, string, table -- The status code, status message and headers of the response.")
    public Object[] response(final Context context, final Arguments args) {
        final Response response = tryGet();
        if (response == null) {
            return new Object[]{null};
        }
        return new Object[]{response.code(), response.message(), response.headers()};
    }

    @Callback(direct = true, limit = 64, doc = "function([n:number]):string or nil -- Reads up to n bytes of the response body. Returns nil at the end.")
    public Object[] read(final Context context, final Arguments args) {
        if (closed) {
            return new Object[]{null, "connection lost"};
        }

        final Response response = tryGet();
        if (response == null) {
            // Not finished yet. An empty string rather than nil, because nil means end of stream
            // and a program that saw it would stop reading.
            return new Object[]{new byte[0]};
        }

        final byte[] body = response.body();
        if (position >= body.length) {
            return new Object[]{null};
        }

        final double requested = args.optDouble(0, Double.POSITIVE_INFINITY);
        final int count = (int) Math.min(
            Double.isInfinite(requested) ? Integer.MAX_VALUE : Math.max(0, requested),
            body.length - position);

        final byte[] result = new byte[count];
        System.arraycopy(body, position, result, 0, count);
        position += count;
        return new Object[]{result};
    }

    @Callback(direct = true, limit = 16, doc = "function() -- Closes the request.")
    public Object[] close(final Context context, final Arguments args) {
        dispose();
        return null;
    }

    @Override
    public void dispose() {
        closed = true;
        future.cancel(true);
    }

    ///////////////////////////////////////////////////////////////////

    @Nullable
    private Response tryGet() {
        if (closed || !future.isDone()) {
            return null;
        }
        try {
            return future.get();
        } catch (final Exception e) {
            return null;
        }
    }

    private static Response perform(final InternetPolicy policy,
                                    final String url,
                                    @Nullable final byte[] postData,
                                    final Map<String, String> headers,
                                    @Nullable final String method) throws IOException {
        final URI uri = URI.create(url);
        final String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("unsupported protocol");
        }

        final String host = uri.getHost();
        if (host == null) {
            throw new IOException("invalid address");
        }

        final int port = uri.getPort() < 0 ? (scheme.equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        final String denied = policy.checkAllowed(host, port);
        if (denied != null) {
            throw new IOException(denied);
        }

        final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(policy.getTimeoutMillis());
            connection.setReadTimeout(policy.getTimeoutMillis());
            // Redirects are followed manually rather than automatically, so that each hop is
            // checked against the policy instead of only the address the program named.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method != null && !method.isEmpty()
                ? method.toUpperCase()
                : (postData != null ? "POST" : "GET"));

            headers.forEach(connection::setRequestProperty);

            if (postData != null) {
                connection.setDoOutput(true);
                try (final OutputStream output = connection.getOutputStream()) {
                    output.write(postData);
                }
            }

            final int code = connection.getResponseCode();
            final String message = connection.getResponseMessage();

            final Map<String, Object> responseHeaders = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((name, values) -> {
                if (name != null) {
                    responseHeaders.put(name, new ArrayList<>(values));
                }
            });

            final byte[] body = readBody(connection, policy.getMaxResponseSize());
            return new Response(code, message == null ? "" : message, responseHeaders, body);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBody(final HttpURLConnection connection, final int limit) throws IOException {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (final IOException e) {
            // A 4xx or 5xx puts the body on the error stream instead, and programs want to see it.
            stream = connection.getErrorStream();
        }
        if (stream == null) {
            return new byte[0];
        }

        try (final InputStream input = stream) {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (buffer.size() + read > limit) {
                    throw new IOException("response too large");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static String describe(final Exception e) {
        final Throwable cause = e.getCause() != null ? e.getCause() : e;
        final String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

}
