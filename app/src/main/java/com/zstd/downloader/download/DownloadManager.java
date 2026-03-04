package com.zstd.downloader.download;

import android.os.Handler;
import android.os.Looper;

import com.zstd.downloader.decompression.BatchDecompressionHandler;
import com.zstd.downloader.decompression.StreamingDecompressionHandler;
import com.zstd.downloader.decompression.ZstdDecompressor;
import com.zstd.downloader.progress.DownloadState;
import com.zstd.downloader.progress.ProgressCalculator;
import com.zstd.downloader.progress.ProgressEvent;
import com.zstd.downloader.storage.FileStorageManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core orchestration layer for download and decompression operations.
 * Manages task lifecycle, pause/resume coordination, and mode switching.
 */
public class DownloadManager {

    private final FileStorageManager fileStorageManager;
    private final MetadataStorage metadataStorage;
    private final Handler mainHandler;

    private DownloadTask downloadTask;
    private StreamingDecompressionHandler streamingHandler;
    private BatchDecompressionHandler batchHandler;

    private final AtomicReference<DownloadState> currentState;
    private final AtomicReference<DownloadMetadata> currentMetadata;
    private final AtomicReference<ProgressCalculator> progressCalculator;
    private ProgressEvent lastProgressEvent;

    private DownloadManagerListener listener;

    public interface DownloadManagerListener {
        void onProgress(ProgressEvent event);
        void onComplete(File outputFile);
        void onError(Throwable error);
    }

    private DownloadManager(Builder builder) {
        this.fileStorageManager = builder.fileStorageManager;
        this.metadataStorage = builder.metadataStorage;
        this.listener = builder.listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.currentState = new AtomicReference<>(DownloadState.IDLE);
        this.currentMetadata = new AtomicReference<>();
        this.progressCalculator = new AtomicReference<>(ProgressCalculator.forStreaming(-1));
        this.lastProgressEvent = ProgressEvent.idle();
    }

    /**
     * Starts a new download with the specified parameters
     *
     * @param url          The URL to download
     * @param streamingMode true for streaming decompression, false for batch
     * @throws IOException if file initialization fails
     */
    public void startDownload(String url, boolean streamingMode) throws IOException {
        // Check if already downloading
        if (currentState.get() == DownloadState.DOWNLOADING ||
            currentState.get() == DownloadState.DECOMPRESSING) {
            throw new IllegalStateException("Download already in progress");
        }

        // Clean up any existing temp files
        fileStorageManager.cleanupTempFiles(url);

        // Create metadata for new download
        DownloadMetadata metadata = metadataStorage.createForNewDownload(url, streamingMode);
        currentMetadata.set(metadata);
        metadataStorage.save(metadata);

        // Initialize progress calculator
        ProgressCalculator.Mode mode = streamingMode
                ? ProgressCalculator.Mode.STREAMING
                : ProgressCalculator.Mode.BATCH;
        progressCalculator.set(ProgressCalculator.builder()
                .mode(mode)
                .totalDownloadBytes(metadata.getTotalBytes())
                .build());

        // Update state
        setState(DownloadState.DOWNLOADING);
        notifyProgress(ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .statusMessage("Starting download...")
                .build());

        if (streamingMode) {
            startStreamingDownload(metadata);
        } else {
            startBatchDownload(metadata);
        }
    }

    /**
     * Resumes a paused or interrupted download
     *
     * @throws IOException if resume fails
     */
    public void resumeDownload() throws IOException {
        if (currentState.get() != DownloadState.PAUSED) {
            throw new IllegalStateException("No paused download to resume");
        }

        DownloadMetadata metadata = currentMetadata.get();
        if (metadata == null) {
            // Try to load from storage
            metadata = metadataStorage.loadWithUpdatedPaths();
            if (metadata == null) {
                throw new IOException("No download metadata found for resume");
            }
            currentMetadata.set(metadata);
        }

        if (!metadataStorage.canResume()) {
            throw new IOException("Cannot resume - server doesn't support Range requests or files are inconsistent");
        }

        setState(DownloadState.DOWNLOADING);
        notifyProgress(ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .statusMessage("Resuming download...")
                .build());

        if (metadata.isStreamingMode()) {
            resumeStreamingDownload(metadata);
        } else {
            resumeBatchDownload(metadata);
        }
    }

    /**
     * Pauses the current download
     */
    public void pauseDownload() {
        DownloadState state = currentState.get();
        if (state != DownloadState.DOWNLOADING && state != DownloadState.DECOMPRESSING) {
            return;
        }

        setState(DownloadState.PAUSED);

        if (downloadTask != null) {
            downloadTask.pause();
        }

        if (streamingHandler != null) {
            streamingHandler.pause();
        }

        if (batchHandler != null) {
            batchHandler.pause();
        }

        // Save current progress
        DownloadMetadata metadata = currentMetadata.get();
        if (metadata != null) {
            long downloadedBytes = progressCalculator.get().getCurrentDownloadedBytes();
            metadataStorage.updateProgress(downloadedBytes);
            metadataStorage.save(metadata.withDownloadedBytes(downloadedBytes));
        }

        notifyProgress(ProgressEvent.builder()
                .state(DownloadState.PAUSED)
                .statusMessage("Paused")
                .build());
    }

    /**
     * Cancels the current download and cleans up
     */
    public void cancelDownload() {
        if (downloadTask != null) {
            downloadTask.cancel();
        }

        if (streamingHandler != null) {
            streamingHandler.cancel();
        }

        if (batchHandler != null) {
            batchHandler.cancel();
        }

        // Clean up files
        DownloadMetadata metadata = currentMetadata.get();
        if (metadata != null) {
            fileStorageManager.cleanupTempFiles(metadata.getUrl());
        }

        setState(DownloadState.IDLE);
        currentMetadata.set(null);
        metadataStorage.clear();

        notifyProgress(ProgressEvent.idle());
    }

    /**
     * Gets the current download state
     *
     * @return Current state
     */
    public DownloadState getState() {
        return currentState.get();
    }

    /**
     * Gets the current metadata if available
     *
     * @return Current metadata or null
     */
    public DownloadMetadata getMetadata() {
        return currentMetadata.get();
    }

    /**
     * Sets the listener for progress and completion events
     *
     * @param listener The listener
     */
    public void setListener(DownloadManagerListener listener) {
        this.listener = listener;
    }

    /**
     * Checks if there's a resumeable download available
     *
     * @return true if resume is possible
     */
    public boolean canResume() {
        return metadataStorage.hasMetadata() && metadataStorage.canResume();
    }

    private void startStreamingDownload(DownloadMetadata metadata) throws IOException {
        File tempCompressedFile = fileStorageManager.getFile(metadata.getTempCompressedPath());
        File tempDecompressedFile = fileStorageManager.getFile(metadata.getTempDecompressedPath());
        File outputFile = fileStorageManager.getFile(metadata.getOutputPath());

        streamingHandler = StreamingDecompressionHandler.builder()
                .outputFile(outputFile)
                .tempDecompressedFile(tempDecompressedFile)
                .totalBytes(metadata.getTotalBytes())
                .progressCallback(new StreamingDecompressionHandler.ProgressCallback() {
                    @Override
                    public void onProgress(ProgressEvent event) {
                        progressCalculator.get().update(
                                event.getDownloadedBytes(),
                                event.getDecompressedBytes(),
                                event.getState()
                        );
                        notifyProgress(event);
                    }

                    @Override
                    public void onComplete(File file) {
                        setState(DownloadState.COMPLETED);
                        notifyProgress(ProgressEvent.completed(event.getDecompressedBytes()));
                        if (listener != null) {
                            listener.onComplete(file);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(error);
                    }
                })
                .build();

        try {
            streamingHandler.open();
        } catch (IOException e) {
            throw new IOException("Failed to initialize streaming handler: " + e.getMessage(), e);
        }

        downloadTask = DownloadTask.builder()
                .metadata(metadata)
                .outputFile(tempCompressedFile)
                .progressCallback(new DownloadTask.ProgressCallback() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        // Progress is handled by streamingHandler
                    }

                    @Override
                    public void onComplete(File file) {
                        // Download complete, finalize streaming
                        try {
                            streamingHandler.complete();
                        } catch (IOException e) {
                            handleError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(error);
                    }
                })
                .build();

        downloadTask.start();
    }

    private void startBatchDownload(DownloadMetadata metadata) throws IOException {
        File tempCompressedFile = fileStorageManager.getFile(metadata.getTempCompressedPath());

        downloadTask = DownloadTask.builder()
                .metadata(metadata)
                .outputFile(tempCompressedFile)
                .progressCallback(new DownloadTask.ProgressCallback() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        progressCalculator.get().update(downloadedBytes, 0, DownloadState.DOWNLOADING);

                        ProgressEvent event = progressCalculator.get().createProgressEvent();
                        notifyProgress(event);

                        // Update metadata
                        metadataStorage.updateProgress(downloadedBytes);
                    }

                    @Override
                    public void onComplete(File compressedFile) {
                        // Download complete, start batch decompression
                        setState(DownloadState.DECOMPRESSING);
                        startBatchDecompression(compressedFile, metadata);
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(error);
                    }
                })
                .build();

        downloadTask.start();
    }

    private void startBatchDecompression(File compressedFile, DownloadMetadata metadata) {
        File outputFile = fileStorageManager.getFile(metadata.getOutputPath());
        File tempDecompressedFile = fileStorageManager.getFile(metadata.getTempDecompressedPath());

        batchHandler = BatchDecompressionHandler.builder()
                .fileStorageManager(fileStorageManager)
                .compressedFile(compressedFile)
                .outputFile(outputFile)
                .tempDecompressedFile(tempDecompressedFile)
                .progressCallback(new BatchDecompressionHandler.ProgressCallback() {
                    @Override
                    public void onProgress(ProgressEvent event) {
                        progressCalculator.get().update(
                                event.getDownloadedBytes(),
                                event.getDecompressedBytes(),
                                event.getState()
                        );
                        notifyProgress(event);
                    }

                    @Override
                    public void onComplete(File file) {
                        setState(DownloadState.COMPLETED);
                        notifyProgress(ProgressEvent.completed(event.getDecompressedBytes()));
                        if (listener != null) {
                            listener.onComplete(file);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(error);
                    }
                })
                .build();

        batchHandler.start();
    }

    private void resumeStreamingDownload(DownloadMetadata metadata) throws IOException {
        // Similar to start but with resume logic
        startStreamingDownload(metadata);
    }

    private void resumeBatchDownload(DownloadMetadata metadata) throws IOException {
        // Similar to start but with resume logic
        startBatchDownload(metadata);
    }

    private void handleError(Throwable error) {
        setState(DownloadState.FAILED);

        notifyProgress(ProgressEvent.failed(
                error.getMessage(),
                error
        ));

        if (listener != null) {
            listener.onError(error);
        }
    }

    private void setState(DownloadState state) {
        currentState.set(state);
        if (progressCalculator.get() != null) {
            progressCalculator.get().setState(state);
        }
    }

    private void notifyProgress(ProgressEvent event) {
        lastProgressEvent = event;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onProgress(event);
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private FileStorageManager fileStorageManager;
        private MetadataStorage metadataStorage;
        private DownloadManagerListener listener;

        public Builder fileStorageManager(FileStorageManager manager) {
            this.fileStorageManager = manager;
            return this;
        }

        public Builder metadataStorage(MetadataStorage storage) {
            this.metadataStorage = storage;
            return this;
        }

        public Builder listener(DownloadManagerListener listener) {
            this.listener = listener;
            return this;
        }

        public DownloadManager build() {
            if (fileStorageManager == null || metadataStorage == null) {
                throw new IllegalStateException("fileStorageManager and metadataStorage are required");
            }
            return new DownloadManager(this);
        }
    }
}
