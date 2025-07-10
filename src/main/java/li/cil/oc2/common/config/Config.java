/* SPDX-License-Identifier: MIT */

package li.cil.oc2.common.config;

import li.cil.oc2.common.Constants;
import li.cil.oc2.common.config.client.GUISpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.common.TierSortingRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("FieldMayBeFinal")
public final class Config {
    //TODO: Implement configuration of CPU MHzs
    public static long maxAllocatedMemory = 512 * Constants.MEGABYTE;
    public static int diskSizeFactor = 2 * Constants.MEGABYTE;

    public static double busCableEnergyPerTick = 0.1;
    public static double busInterfaceEnergyPerTick = 0.5;
    public static int computerEnergyPerTick = 10;
    public static int computerEnergyStorage = 2000;
    public static int chargerEnergyPerTick = 2500;
    public static int chargerEnergyStorage = 10000;
    public static int projectorEnergyPerTick = 20;
    public static int projectorEnergyStorage = 2000;
    public static int monitorEnergyPerTick = 15;
    public static int monitorEnergyStorage = 2000;
    public static int cardCageEnergyPerTick = 20;
    public static int cardCageEnergyStorage = 2000;
    public static int gatewayEnergyPerPacket = 20;
    public static int gatewayEnergyStorage = 2000;

    public static int robotEnergyPerTick = 5;
    public static int robotEnergyStorage = 750000;

    public static double memoryEnergyPerMegabytePerTick = 0.5;
    public static double hardDriveEnergyPerMegabytePerTick = 1;
    public static double cpuEnergyPerMegahertzPerTick = 0.1;
    public static int redstoneInterfaceCardEnergyPerTick = 1;
    public static int networkInterfaceEnergyPerTick = 1;
    public static int fileImportExportCardEnergyPerTick = 1;
    public static int soundCardEnergyPerTick = 1;
    public static int blockOperationsModuleEnergyPerTick = 2;
    public static int inventoryOperationsModuleEnergyPerTick = 1;
    public static int networkTunnelEnergyPerTick = 2;

    public static ResourceLocation blockOperationsModuleToolTier = TierSortingRegistry.getName(Tiers.DIAMOND);
    public static long soundCardCoolDownSeconds = 2;

    public static UUID fakePlayerUUID = UUID.fromString("e39dd9a7-514f-4a2d-aa5e-b6030621416d");
    public static int projectorAverageMaxBytesPerSecond = 160 * 1024;
    public static int ethernetFrameTimeToLive = 12;
    public static int hubEthernetFramesPerTick = 32;

    public static boolean enable = false;
    public static String remoteHost = "::1";
    public static int remotePort = 4789;
    public static String bindHost = "::1";
    public static int bindPort = 4789;
    public static boolean internetCardEnabled = false;
    public static int defaultSessionLifetimeMs = 60 * 1000;
    public static int defaultSessionsNumberPerCardLimit = 10;
    public static int defaultSessionsNumberLimit = 100;
    public static int defaultEchoRequestTimeoutMs = 1000;
    public static List<String> deniedHosts =
        Arrays.asList("127.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4");
    public static List<String> allowedHosts = List.of();
    public static String defaultNameServer = "1.1.1.1";
    public static boolean useSynchronisedNAT = false;
    public static int streamBufferSize = 2000;
    public static int tcpRetransmissionTimeoutMs = 2 * 1000;

    public static GUISpec.CaptureInputMode captureInputMode = GUISpec.CaptureInputMode.PER_BLOCK;
    public static boolean captureInputDefaultState = false;

    // ===== ENHANCED INTERNET CARD DEVICE SETTINGS =====
    // File transfer settings for large uploads/downloads
    public static int internetCardMaxRequestSize = 50 * Constants.MEGABYTE;
    public static int internetCardMaxResponseSize = 100 * Constants.MEGABYTE;
    public static int internetCardConnectionTimeout = 60000; // 60 seconds
    public static int internetCardReadTimeout = 300000; // 5 minutes
    public static int internetCardBufferSize = 64 * 1024; // 64KB
    public static int internetCardMaxConcurrentOperations = 10;

    // Rate limiting and security
    public static int internetCardMaxRequestsPerMinute = 60;
    public static boolean internetCardHttpsOnly = false;
    public static boolean internetCardEnableResumeDownloads = true;
    public static boolean internetCardEnableChunkedUploads = true;

    // Memory management for VM integration
    public static int internetCardVmMemoryAllocation = 2 * Constants.MEGABYTE;
    public static int internetCardProgressUpdateInterval = 1 * Constants.MEGABYTE;

    // Enhanced domain filtering (extends existing deniedHosts/allowedHosts)
    public static boolean internetCardUseWhitelist = false;
    public static List<String> internetCardAllowedDomains = Arrays.asList(
        "api.github.com",
        "pastebin.com",
        "httpbin.org",
        "*.github.com",
        "*.githubusercontent.com"
    );
    public static List<String> internetCardBlockedDomains = Arrays.asList(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "10.*",
        "192.168.*",
        "172.16.*",
        "169.254.*"
    );

    // Energy consumption for internet card operations
    public static int internetCardEnergyPerTick = 2;
    public static int internetCardEnergyPerRequest = 50;
    public static int internetCardEnergyPerMegabyte = 100;

    public static boolean computersUseEnergy() {
        return computerEnergyPerTick > 0 && computerEnergyStorage > 0;
    }

    public static boolean projectorsUseEnergy() {
        return projectorEnergyStorage > 0 && projectorEnergyPerTick > 0;
    }

    public static boolean cardCagesUseEnergy() {
        return cardCageEnergyStorage > 0 && cardCageEnergyPerTick > 0;
    }

    public static boolean robotsUseEnergy() {
        return robotEnergyPerTick > 0 && robotEnergyStorage > 0;
    }

    public static boolean monitorsUseEnergy() {
        return computerEnergyPerTick > 0 && computerEnergyStorage > 0;
    }

    public static boolean gatewayUseEnergy() {
        return gatewayEnergyPerPacket > 0 && gatewayEnergyStorage > 0;
    }

    // ===== INTERNET CARD UTILITY METHODS =====

    /**
     * Check if a domain is allowed based on whitelist/blacklist configuration
     */
    public static boolean isInternetCardDomainAllowed(String domain) {
        if (!internetCardEnabled) {
            return false;
        }

        String normalizedDomain = domain.toLowerCase();

        if (internetCardUseWhitelist) {
            return internetCardAllowedDomains.stream().anyMatch(allowed ->
                matchesDomainPattern(normalizedDomain, allowed.toLowerCase()));
        } else {
            // Check both the original deniedHosts and new blocked domains
            boolean blockedByOriginal = deniedHosts.stream().anyMatch(blocked ->
                matchesCidrOrDomainPattern(normalizedDomain, blocked));
            boolean blockedByNew = internetCardBlockedDomains.stream().anyMatch(blocked ->
                matchesDomainPattern(normalizedDomain, blocked.toLowerCase()));
            return !blockedByOriginal && !blockedByNew;
        }
    }

    /**
     * Validate request size against configured limits
     */
    public static void validateInternetCardRequestSize(int size) {
        if (size > internetCardMaxRequestSize) {
            throw new IllegalArgumentException(
                String.format("Request size %d exceeds maximum allowed size %d",
                    size, internetCardMaxRequestSize));
        }
    }

    /**
     * Validate response size against configured limits
     */
    public static void validateInternetCardResponseSize(long size) {
        if (size > internetCardMaxResponseSize) {
            throw new IllegalArgumentException(
                String.format("Response size %d exceeds maximum allowed size %d",
                    size, internetCardMaxResponseSize));
        }
    }

    /**
     * Format byte sizes for display
     */
    public static String formatByteSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean matchesDomainPattern(String domain, String pattern) {
        if (pattern.equals(domain)) {
            return true;
        }

        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(2);
            return domain.endsWith("." + suffix) || domain.equals(suffix);
        }

        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return domain.startsWith(prefix + ".");
        }

        return false;
    }

    private static boolean matchesCidrOrDomainPattern(String domain, String pattern) {
        // Handle CIDR patterns (like existing deniedHosts)
        if (pattern.contains("/")) {
            // This is a CIDR pattern - for domain checking, we skip IP-based blocking
            // The actual IP resolution and CIDR checking would happen at network level
            return false;
        }

        // Handle domain patterns
        return matchesDomainPattern(domain, pattern);
    }
}
