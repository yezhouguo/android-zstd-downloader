package com.zstd.downloader.download;

import com.zstd.downloader.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP download engine supporting Range requests for resume capability.
 * Uses OkHttp for efficient network operations with progress callbacks.
 */
public class DownloadTask {

    private final OkHttpClient httpClient;
    private final DownloadMetadata metadata;
    private final File outputFile;
    private final ProgressCallback progressCallback;
    private final ChunkCallback chunkCallback;
    private final AtomicBoolean isPaused;
    private final AtomicBoolean isCancelled;
    private Call currentCall;

    public interface ProgressCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        void onComplete(File outputFile);
        void onError(Throwable error);
    }

    public interface ChunkCallback {
        void onChunk(byte[] chunk, int size, long totalDownloaded);
    }

    private DownloadTask(Builder builder) {
        this.metadata = builder.metadata;
        this.outputFile = builder.outputFile;
        this.progressCallback = builder.progressCallback;
        this.chunkCallback = builder.chunkCallback;
        this.isPaused = new AtomicBoolean(false);
        this.isCancelled = new AtomicBoolean(false);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Constants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(Constants.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(Constants.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void start() {
        isPaused.set(false);
        isCancelled.set(false);
        new Thread(this::runDownload).start();
    }

    public void pause() {
        isPaused.set(true);
        if (currentCall != null) {
            currentCall.cancel();
        }
    }

    public void cancel() {
        isCancelled.set(true);
        isPaused.set(true);
        if (currentCall != null) {
            currentCall.cancel();
        }
    }

    public void resume() {
        isPaused.set(false);
        start();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public boolean isCancelled() {
        return isCancelled.get();
    }

    public boolean isRunning() {
        return !isPaused.get() && !isCancelled.get();
    }

    private void runDownload() {
        try {
            ServerInfo serverInfo = fetchServerInfo();

            if (!serverInfo.supportsRange && metadata.getDownloadedBytes() > 0) {
                progressCallback.onError(new IOException("Server doesn't support Range requests, cannot resume"));
                return;
            }

            performDownload(serverInfo);

        } catch (IOException e) {
            if (isCancelled.get()) {
                return;
            }
            if (isPaused.get() && e instanceof InterruptedIOException) {
                return;
            }
            progressCallback.onError(e);
        }
    }

    private ServerInfo fetchServerInfo() throws IOException {
        Request headRequest = new Request.Builder()
                .url(metadata.getUrl())
                .head()
                .build();

        Response response = httpClient.newCall(headRequest).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Server returned " + response.code() + ": " + response.message());
        }

        ServerInfo info = new ServerInfo();
        info.contentLength = parseContentLength(response.header("Content-Length"));
        info.supportsRange = "bytes".equalsIgnoreCase(response.header("Accept-Ranges"));
        info.etag = response.header("ETag");
        info.lastModified = response.header("Last-Modified");

        response.close();
        return info;
    }

    private void performDownload(ServerInfo serverInfo) throws IOException {
        long downloadedBytes = metadata.getDownloadedBytes();
        long totalBytes = metadata.getTotalBytes() > 0 ? metadata.getTotalBytes() : serverInfo.contentLength;

        Request.Builder requestBuilder = new Request.Builder()
                .url(metadata.getUrl())
                .get();

        if (downloadedBytes > 0 && serverInfo.supportsRange) {
            requestBuilder.header("Range", metadata.getRangeHeaderValue());
            if (metadata.getEtag() != null) {
                requestBuilder.header("If-Match", metadata.getEtag());
            }
            if (metadata.getLastModified() != null) {
                requestBuilder.header("If-Unmodified-Since", metadata.getLastModified());
            }
        }

        Request request = requestBuilder.build();
        currentCall = httpClient.newCall(request);

        Response response = currentCall.execute();
        if (!response.isSuccessful() && response.code() != 206) {
            throw new IOException("Download failed: " + response.code() + " " + response.message());
        }

        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Empty response body");
        }

        if (totalBytes <= 0) {
            totalBytes = body.contentLength();
        }

        boolean appendMode = downloadedBytes > 0 && response.code() == 206;
        try (InputStream input = body.byteStream();
             FileOutputStream output = new FileOutputStream(outputFile, appendMode)) {

            byte[] buffer = new byte[Constants.DOWNLOAD_BUFFER_SIZE];
            int bytesRead;
            long lastUpdateTime = System.currentTimeMillis();

            while ((bytesRead = input.read(buffer)) != -1) {
                if (isPaused.get()) {
                    throw new InterruptedIOException("Download paused");
                }
                if (isCancelled.get()) {
                    throw new InterruptedIOException("Download cancelled");
                }

                output.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;

                // Call chunk callback for streaming decompression
                if (chunkCallback != null) {
                    chunkCallback.onChunk(buffer, bytesRead, downloadedBytes);
                }

                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= Constants.MIN_PROGRESS_UPDATE_INTERVAL_MS) {
                    progressCallback.onProgress(downloadedBytes, totalBytes);
                    lastUpdateTime = now;
                }
            }

            progressCallback.onProgress(downloadedBytes, totalBytes);

            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw new IOException("Download incomplete: " + downloadedBytes + " of " + totalBytes + " bytes");
            }

            output.getFD().sync();

        } finally {
            response.close();
        }

        progressCallback.onComplete(outputFile);
    }

    private long parseContentLength(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static class ServerInfo {
        long contentLength = -1;
        boolean supportsRange = false;
        String etag;
        String lastModified;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DownloadMetadata metadata;
        private File outputFile;
        private ProgressCallback progressCallback;
        private ChunkCallback chunkCallback;

        public Builder metadata(DownloadMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder outputFile(File outputFile) {
            this.outputFile = outputFile;
            return this;
        }

        public Builder progressCallback(ProgressCallback callback) {
            this.progressCallback = callback;
            return this;
        }

        public Builder chunkCallback(ChunkCallback callback) {
            this.chunkCallback = callback;
            return this;
        }

        public DownloadTask build() {
            if (metadata == null || outputFile == null || progressCallback == null) {
                throw new IllegalStateException("metadata, outputFile, and progressCallback are required");
            }
            return new DownloadTask(this);
        }
    }
}
