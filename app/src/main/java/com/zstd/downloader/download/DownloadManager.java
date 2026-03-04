package com.zstd.downloader.download;

import android.os.Handler;
import android.os.Looper;

import com.zstd.downloader.decompression.BatchDecompressionHandler;
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
    private BatchDecompressionHandler batchHandler;

    private final AtomicReference<DownloadState> currentState;
    private final AtomicReference<DownloadMetadata> currentMetadata;
    private final AtomicReference<ProgressCalculator> progressCalculator;
    private ProgressEvent lastProgressEvent;

    private long currentDownloadedBytes;
    private long currentTotalBytes;

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
        this.currentDownloadedBytes = 0;
        this.currentTotalBytes = -1;
    }

    public void startDownload(String url, boolean streamingMode) throws IOException {
        if (currentState.get() == DownloadState.DOWNLOADING ||
            currentState.get() == DownloadState.DECOMPRESSING) {
            throw new IllegalStateException("Download already in progress");
        }

        fileStorageManager.cleanupTempFiles(url);

        DownloadMetadata metadata = metadataStorage.createForNewDownload(url, streamingMode);
        currentMetadata.set(metadata);
        metadataStorage.save(metadata);

        ProgressCalculator.Mode mode = streamingMode
                ? ProgressCalculator.Mode.STREAMING
                : ProgressCalculator.Mode.BATCH;
        progressCalculator.set(ProgressCalculator.builder()
                .mode(mode)
                .totalDownloadBytes(-1)
                .build());

        setState(DownloadState.DOWNLOADING);
        notifyProgress(ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .statusMessage("Starting download...")
                .build());

        // Reset counters
        currentDownloadedBytes = 0;
        currentTotalBytes = -1;

        startDownload(metadata);
    }

    public void resumeDownload() throws IOException {
        if (currentState.get() != DownloadState.PAUSED) {
            throw new IllegalStateException("No paused download to resume");
        }

        DownloadMetadata metadata = currentMetadata.get();
        if (metadata == null) {
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

        startDownload(metadata);
    }

    public void pauseDownload() {
        DownloadState state = currentState.get();
        if (state != DownloadState.DOWNLOADING && state != DownloadState.DECOMPRESSING) {
            return;
        }

        setState(DownloadState.PAUSED);

        if (downloadTask != null) {
            downloadTask.pause();
        }

        if (batchHandler != null) {
            batchHandler.pause();
        }

        DownloadMetadata metadata = currentMetadata.get();
        if (metadata != null) {
            long downloadedBytes = currentDownloadedBytes;
            metadataStorage.updateProgress(downloadedBytes);
            metadataStorage.save(metadata.withDownloadedBytes(downloadedBytes));
        }

        notifyProgress(ProgressEvent.builder()
                .state(DownloadState.PAUSED)
                .statusMessage("Paused")
                .build());
    }

    public void cancelDownload() {
        if (downloadTask != null) {
            downloadTask.cancel();
        }

        if (batchHandler != null) {
            batchHandler.cancel();
        }

        DownloadMetadata metadata = currentMetadata.get();
        if (metadata != null) {
            fileStorageManager.cleanupTempFiles(metadata.getUrl());
        }

        setState(DownloadState.IDLE);
        currentMetadata.set(null);
        metadataStorage.clear();

        notifyProgress(ProgressEvent.idle());
    }

    public DownloadState getState() {
        return currentState.get();
    }

    public DownloadMetadata getMetadata() {
        return currentMetadata.get();
    }

    public void setListener(DownloadManagerListener listener) {
        this.listener = listener;
    }

    public boolean canResume() {
        return metadataStorage.hasMetadata() && metadataStorage.canResume();
    }

    private void startDownload(DownloadMetadata metadata) throws IOException {
        File tempCompressedFile = fileStorageManager.getFile(metadata.getTempCompressedPath());

        downloadTask = DownloadTask.builder()
                .metadata(metadata)
                .outputFile(tempCompressedFile)
                .progressCallback(new DownloadTask.ProgressCallback() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        currentDownloadedBytes = downloadedBytes;
                        if (totalBytes > 0) {
                            currentTotalBytes = totalBytes;
                        }

                        progressCalculator.get().update(downloadedBytes, 0, DownloadState.DOWNLOADING);
                        notifyProgress(progressCalculator.get().createProgressEvent());
                        metadataStorage.updateProgress(downloadedBytes);
                    }

                    @Override
                    public void onComplete(File compressedFile) {
                        // Download complete, start decompression
                        setState(DownloadState.DECOMPRESSING);
                        startDecompression(compressedFile, metadata);
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(error);
                    }
                })
                .build();

        downloadTask.start();
    }

    private void startDecompression(File compressedFile, DownloadMetadata metadata) {
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
                        notifyProgress(event);
                    }

                    @Override
                    public void onComplete(File file) {
                        setState(DownloadState.COMPLETED);
                        notifyProgress(ProgressEvent.completed(file != null ? file.length() : 0));
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
