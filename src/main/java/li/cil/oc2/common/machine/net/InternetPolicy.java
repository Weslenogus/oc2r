/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.machine.net;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * What an internet card is allowed to do, decided by the server rather than by the machine.
 * <p>
 * Kept as an interface with no Minecraft types so the card can be exercised without a server, and
 * so a pack or a host can substitute its own rules. The mod's implementation reads the same
 * configuration the existing internet card uses.
 * <p>
 * The default implementation blocks private and loopback address ranges. That is the part worth
 * being deliberate about: without it, a player could point an internet card at a service on the
 * host machine or elsewhere on the server operator's network, using the server as a proxy into
 * places they cannot reach themselves.
 */
public interface InternetPolicy {
    /**
     * Refuses everything. The safe default for a host that has not been configured.
     */
    InternetPolicy DENY_ALL = new InternetPolicy() {
        @Override
        public boolean isHttpEnabled() {
            return false;
        }

        @Override
        public boolean isTcpEnabled() {
            return false;
        }

        @Override
        public String checkAllowed(final String host, final int port) {
            return "internet access is disabled";
        }
    };

    boolean isHttpEnabled();

    boolean isTcpEnabled();

    /**
     * Decides whether a machine may reach a host.
     *
     * @return {@code null} if the connection is allowed, otherwise the reason it is not.
     */
    @Nullable
    String checkAllowed(String host, int port);

    /**
     * How many requests and sockets a single card may have open at once.
     */
    default int getMaxConnections() {
        return 4;
    }

    /**
     * Connect and read timeout in milliseconds.
     */
    default int getTimeoutMillis() {
        return 10_000;
    }

    /**
     * Largest response body a card will buffer, in bytes.
     */
    default int getMaxResponseSize() {
        return 8 * 1024 * 1024;
    }

    /**
     * Whether an address is one a machine has no business reaching: loopback, link local, site
     * local and the wildcard address.
     * <p>
     * Resolution happens here rather than being left to the connect call, so that a name pointing
     * at a private address is rejected too.
     */
    static boolean isReservedAddress(final String host) {
        try {
            for (final InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (final UnknownHostException e) {
            // A name that will not resolve cannot be connected to either; treating it as reserved
            // keeps the failure on the safe side.
            return true;
        }
    }

    /**
     * A policy allowing everything except the reserved ranges, optionally narrowed by a list of
     * permitted host name suffixes.
     *
     * @param allowedHostSuffixes host suffixes to permit, or an empty list to permit any public
     *                            host.
     */
    static InternetPolicy allowPublic(final boolean http, final boolean tcp,
                                      final List<String> allowedHostSuffixes) {
        return new InternetPolicy() {
            @Override
            public boolean isHttpEnabled() {
                return http;
            }

            @Override
            public boolean isTcpEnabled() {
                return tcp;
            }

            @Override
            public String checkAllowed(final String host, final int port) {
                if (host.isEmpty()) {
                    return "invalid address";
                }
                if (isReservedAddress(host)) {
                    return "address is not allowed";
                }
                if (allowedHostSuffixes.isEmpty()) {
                    return null;
                }
                final String lower = host.toLowerCase();
                for (final String suffix : allowedHostSuffixes) {
                    if (lower.equals(suffix) || lower.endsWith("." + suffix)) {
                        return null;
                    }
                }
                return "address is not allowed";
            }
        };
    }
}
