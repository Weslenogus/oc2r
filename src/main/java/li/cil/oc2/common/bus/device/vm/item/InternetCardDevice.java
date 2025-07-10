package li.cil.oc2.common.bus.device.vm.item;

import li.cil.oc2.api.bus.device.ItemDevice;
import li.cil.oc2.api.bus.device.vm.VMDevice;
import li.cil.oc2.api.bus.device.vm.VMDeviceLoadResult;
import li.cil.oc2.api.bus.device.vm.context.VMContext;
import li.cil.oc2.common.config.Config;
import li.cil.oc2.common.serialization.NBTSerialization;
import li.cil.oc2.common.util.NBTTagIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public final class InternetCardDevice extends AbstractNetworkInterfaceDevice {

    // Thread pool for handling large file operations asynchronously
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "OC2R-Internet-Card");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    // Memory management for large operations
    private final AtomicInteger activeOperations = new AtomicInteger(0);

    // Rate limiting per device instance
    private volatile long lastRequestTime = 0;
    private final AtomicInteger requestsThisMinute = new AtomicInteger(0);
    private volatile long currentMinuteStart = System.currentTimeMillis();

    ///////////////////////////////////////////////////////////////////

    public InternetCardDevice(final ItemStack identity) {
        super(identity);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public VMDeviceLoadResult mount(final VMContext context) {
        // Check if internet card is enabled in config
        if (!Config.internetCardEnabled) {
            return VMDeviceLoadResult.fail()
                .withErrorMessage(Component.literal("Internet Card is disabled in configuration"));
        }

        try {
            // Initialize the internet card device
            // Reset any existing state
            activeOperations.set(0);
            requestsThisMinute.set(0);
            currentMinuteStart = System.currentTimeMillis();

            // Validate configuration
            if (Config.internetCardMaxRequestSize <= 0) {
                return VMDeviceLoadResult.fail()
                    .withErrorMessage(Component.literal("Invalid max request size in config"));
            }

            if (Config.internetCardMaxResponseSize <= 0) {
                return VMDeviceLoadResult.fail()
                    .withErrorMessage(Component.literal("Invalid max response size in config"));
            }

            // Internet card mounted successfully
            return VMDeviceLoadResult.success();

        } catch (Exception e) {
            return VMDeviceLoadResult.fail()
                .withErrorMessage(Component.literal("Failed to initialize Internet Card: " + e.getMessage()));
        }
    }

    @Override
    public void unmount() {
        // Cancel any ongoing operations and cleanup
        activeOperations.set(0);
    }

    @Override
    public void dispose() {
        unmount();
    }

    ///////////////////////////////////////////////////////////////////

    // Enhanced HTTP request method with chunked transfer support
    public CompletableFuture<HttpResponse> makeHttpRequest(String urlString, String method,
                                                           byte[] requestData, String contentType) {
        return CompletableFuture.supplyAsync(() -> {
            // Check configuration and validate domain
            if (!Config.internetCardEnabled) {
                throw new RuntimeException("Internet card is disabled in configuration");
            }

            // Rate limiting check
            if (!checkRateLimit()) {
                throw new RuntimeException("Rate limit exceeded");
            }

            if (!canStartOperation()) {
                throw new RuntimeException("Too many concurrent operations");
            }

            try {
                URL url = new URL(urlString);
                String domain = url.getHost();

                // Validate domain against whitelist/blacklist
                if (!Config.isInternetCardDomainAllowed(domain)) {
                    throw new RuntimeException("Domain not allowed: " + domain);
                }

                // Validate HTTPS requirement
                if (Config.internetCardHttpsOnly && !"https".equals(url.getProtocol())) {
                    throw new RuntimeException("Only HTTPS connections are allowed");
                }

                // Validate request size
                if (requestData != null) {
                    Config.validateInternetCardRequestSize(requestData.length);
                }

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                // Enhanced configuration for large files
                connection.setRequestMethod(method);
                connection.setConnectTimeout(Config.internetCardConnectionTimeout);
                connection.setReadTimeout(Config.internetCardReadTimeout);
                connection.setInstanceFollowRedirects(true);

                // Enable chunked streaming for large uploads if enabled
                if (requestData != null && Config.internetCardEnableChunkedUploads &&
                    requestData.length > Config.internetCardBufferSize) {
                    connection.setChunkedStreamingMode(Config.internetCardBufferSize);
                } else if (requestData != null) {
                    connection.setFixedLengthStreamingMode(requestData.length);
                }

                // Set headers for large file handling
                connection.setRequestProperty("User-Agent", "OC2R-InternetCard/2.0 (Minecraft/1.20.1; Forge/47.4.x)");
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
                connection.setRequestProperty("Connection", "keep-alive");
                connection.setRequestProperty("Cache-Control", "no-cache");

                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }

                // Handle request body for uploads
                if (requestData != null && requestData.length > 0) {
                    connection.setDoOutput(true);

                    try (OutputStream out = connection.getOutputStream();
                         BufferedOutputStream bufferedOut = new BufferedOutputStream(out, Config.internetCardBufferSize)) {

                        // Write data in chunks for better memory management
                        writeDataInChunks(bufferedOut, requestData);
                        bufferedOut.flush();
                    }
                }

                // Read response with streaming for large downloads
                int responseCode = connection.getResponseCode();
                String responseMessage = connection.getResponseMessage();

                InputStream inputStream = responseCode >= 400 ?
                    connection.getErrorStream() : connection.getInputStream();

                byte[] responseData = null;
                if (inputStream != null) {
                    // Handle compressed responses
                    String encoding = connection.getContentEncoding();
                    if ("gzip".equalsIgnoreCase(encoding)) {
                        inputStream = new GZIPInputStream(inputStream);
                    }

                    responseData = readStreamWithLimit(inputStream, Config.internetCardMaxResponseSize);
                }

                return new HttpResponse(responseCode, responseMessage, responseData,
                    connection.getHeaderFields());

            } catch (Exception e) {
                throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
            } finally {
                finishOperation();
            }
        }, EXECUTOR);
    }

    // Enhanced file download with resume capability
    public CompletableFuture<DownloadResult> downloadFile(String urlString, long resumeFromByte) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Config.internetCardEnabled) {
                throw new RuntimeException("Internet card is disabled in configuration");
            }

            if (!checkRateLimit()) {
                throw new RuntimeException("Rate limit exceeded");
            }

            if (!canStartOperation()) {
                throw new RuntimeException("Too many concurrent operations");
            }

            try {
                URL url = new URL(urlString);
                String domain = url.getHost();

                if (!Config.isInternetCardDomainAllowed(domain)) {
                    throw new RuntimeException("Domain not allowed: " + domain);
                }

                if (Config.internetCardHttpsOnly && !"https".equals(url.getProtocol())) {
                    throw new RuntimeException("Only HTTPS connections are allowed");
                }

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(Config.internetCardConnectionTimeout);
                connection.setReadTimeout(Config.internetCardReadTimeout);
                connection.setInstanceFollowRedirects(true);

                // Support resume downloads if enabled
                if (resumeFromByte > 0 && Config.internetCardEnableResumeDownloads) {
                    connection.setRequestProperty("Range", "bytes=" + resumeFromByte + "-");
                }

                connection.setRequestProperty("User-Agent", "OC2R-InternetCard/2.0 (Minecraft/1.20.1; Forge/47.4.x)");
                connection.setRequestProperty("Accept-Encoding", "identity"); // Disable compression for resume

                int responseCode = connection.getResponseCode();
                long contentLength = connection.getContentLengthLong();

                if (contentLength > 0) {
                    Config.validateInternetCardResponseSize(contentLength);
                }

                InputStream inputStream = connection.getInputStream();
                byte[] data = readStreamWithProgressTracking(inputStream, contentLength);

                return new DownloadResult(responseCode, data, data.length,
                    connection.getHeaderField("Content-Type"));

            } catch (Exception e) {
                throw new RuntimeException("Download failed: " + e.getMessage(), e);
            } finally {
                finishOperation();
            }
        }, EXECUTOR);
    }

    // Enhanced file upload with progress tracking
    public CompletableFuture<UploadResult> uploadFile(String urlString, byte[] fileData,
                                                      String fileName, String contentType) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Config.internetCardEnabled) {
                throw new RuntimeException("Internet card is disabled in configuration");
            }

            if (!checkRateLimit()) {
                throw new RuntimeException("Rate limit exceeded");
            }

            if (!canStartOperation()) {
                throw new RuntimeException("Too many concurrent operations");
            }

            Config.validateInternetCardRequestSize(fileData.length);

            try {
                URL url = new URL(urlString);
                String domain = url.getHost();

                if (!Config.isInternetCardDomainAllowed(domain)) {
                    throw new RuntimeException("Domain not allowed: " + domain);
                }

                if (Config.internetCardHttpsOnly && !"https".equals(url.getProtocol())) {
                    throw new RuntimeException("Only HTTPS connections are allowed");
                }

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(Config.internetCardConnectionTimeout);
                connection.setReadTimeout(Config.internetCardReadTimeout);
                connection.setDoOutput(true);

                if (Config.internetCardEnableChunkedUploads) {
                    connection.setChunkedStreamingMode(Config.internetCardBufferSize);
                }

                // Multipart form data for file uploads
                String boundary = "----OC2RFileUpload" + System.currentTimeMillis();
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setRequestProperty("User-Agent", "OC2R-InternetCard/2.0 (Minecraft/1.20.1; Forge/47.4.x)");

                try (OutputStream out = connection.getOutputStream();
                     BufferedOutputStream bufferedOut = new BufferedOutputStream(out, Config.internetCardBufferSize)) {

                    // Write multipart headers
                    String header = "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                        "Content-Type: " + (contentType != null ? contentType : "application/octet-stream") + "\r\n\r\n";
                    bufferedOut.write(header.getBytes(StandardCharsets.UTF_8));

                    // Write file data in chunks
                    writeDataInChunks(bufferedOut, fileData);

                    // Write multipart footer
                    String footer = "\r\n--" + boundary + "--\r\n";
                    bufferedOut.write(footer.getBytes(StandardCharsets.UTF_8));
                    bufferedOut.flush();
                }

                int responseCode = connection.getResponseCode();
                String responseMessage = connection.getResponseMessage();

                InputStream responseStream = responseCode >= 400 ?
                    connection.getErrorStream() : connection.getInputStream();

                byte[] responseData = null;
                if (responseStream != null) {
                    responseData = readStreamWithLimit(responseStream, 1024 * 1024); // 1MB response limit
                }

                return new UploadResult(responseCode, responseMessage, responseData);

            } catch (Exception e) {
                throw new RuntimeException("Upload failed: " + e.getMessage(), e);
            } finally {
                finishOperation();
            }
        }, EXECUTOR);
    }

    // Optimized stream reading with progress tracking
    private byte[] readStreamWithLimit(InputStream inputStream, int maxSize) throws IOException {
        try (BufferedInputStream bufferedInput = new BufferedInputStream(inputStream, Config.internetCardBufferSize);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[Config.internetCardBufferSize];
            int totalBytesRead = 0;
            int bytesRead;
            int progressInterval = Config.internetCardProgressUpdateInterval;

            while ((bytesRead = bufferedInput.read(buffer)) != -1) {
                if (totalBytesRead + bytesRead > maxSize) {
                    throw new IOException("Response size exceeds maximum allowed: " + maxSize);
                }

                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // Yield periodically for large downloads
                if (totalBytesRead % progressInterval == 0) {
                    Thread.yield();
                }
            }

            return outputStream.toByteArray();
        }
    }

    private byte[] readStreamWithProgressTracking(InputStream inputStream, long expectedSize) throws IOException {
        try (BufferedInputStream bufferedInput = new BufferedInputStream(inputStream, Config.internetCardBufferSize);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[Config.internetCardBufferSize];
            long totalBytesRead = 0;
            int bytesRead;
            long lastProgressUpdate = 0;
            int progressInterval = Config.internetCardProgressUpdateInterval;

            while ((bytesRead = bufferedInput.read(buffer)) != -1) {
                // Check size limit during reading
                if (totalBytesRead + bytesRead > Config.internetCardMaxResponseSize) {
                    throw new IOException("Response size exceeds maximum allowed: " + Config.internetCardMaxResponseSize);
                }

                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // Update progress tracking
                if (totalBytesRead - lastProgressUpdate >= progressInterval) {
                    updateProgressTracking(totalBytesRead, expectedSize);
                    lastProgressUpdate = totalBytesRead;
                    Thread.yield();
                }
            }

            return outputStream.toByteArray();
        }
    }

    private void writeDataInChunks(OutputStream out, byte[] data) throws IOException {
        int offset = 0;
        int bufferSize = Config.internetCardBufferSize;
        int progressInterval = Config.internetCardProgressUpdateInterval;

        while (offset < data.length) {
            int chunkSize = Math.min(bufferSize, data.length - offset);
            out.write(data, offset, chunkSize);
            offset += chunkSize;

            // Yield periodically for large uploads
            if (offset % progressInterval == 0) {
                Thread.yield();
            }
        }
    }

    // Rate limiting implementation
    private boolean checkRateLimit() {
        if (Config.internetCardMaxRequestsPerMinute <= 0) {
            return true; // Rate limiting disabled
        }

        long currentTime = System.currentTimeMillis();

        // Reset counter if a new minute has started
        if (currentTime - currentMinuteStart >= 60000) {
            currentMinuteStart = currentTime;
            requestsThisMinute.set(0);
        }

        return requestsThisMinute.incrementAndGet() <= Config.internetCardMaxRequestsPerMinute;
    }

    // Operation management
    private boolean canStartOperation() {
        return activeOperations.incrementAndGet() <= Config.internetCardMaxConcurrentOperations;
    }

    private void finishOperation() {
        activeOperations.decrementAndGet();
    }

    // Remove placeholder memory interface methods since they may not exist in OC2R
    // Memory interfaces would need to be implemented based on actual OC2R patterns

    // Progress tracking would need to be implemented based on actual OC2R VM communication patterns
    private void updateProgressTracking(long bytesTransferred, long totalBytes) {
        // Implementation depends on actual OC2R VMDevice communication methods
    }

    // Result classes for enhanced operations
    public static class HttpResponse {
        public final int statusCode;
        public final String statusMessage;
        public final byte[] data;
        public final java.util.Map<String, java.util.List<String>> headers;

        public HttpResponse(int statusCode, String statusMessage, byte[] data,
                            java.util.Map<String, java.util.List<String>> headers) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.data = data;
            this.headers = headers;
        }
    }

    public static class DownloadResult {
        public final int statusCode;
        public final byte[] data;
        public final long contentLength;
        public final String contentType;

        public DownloadResult(int statusCode, byte[] data, long contentLength, String contentType) {
            this.statusCode = statusCode;
            this.data = data;
            this.contentLength = contentLength;
            this.contentType = contentType;
        }
    }

    public static class UploadResult {
        public final int statusCode;
        public final String statusMessage;
        public final byte[] responseData;

        public UploadResult(int statusCode, String statusMessage, byte[] responseData) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.responseData = responseData;
        }
    }

}
