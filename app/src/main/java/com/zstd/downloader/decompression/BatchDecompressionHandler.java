package com.zstd.downloader.decompression;

import com.zstd.downloader.Constants;
import com.zstd.downloader.storage.FileStorageManager;
import com.zstd.downloader.progress.DownloadState;
import com.zstd.downloader.progress.ProgressEvent;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles decompression after download completes (batch mode).
 * Downloads the entire file first, then decompresses it.
 * Progress shows sequential stages: download 0-100%, then decompress 0-100%.
 */
public class BatchDecompressionHandler {

    private final ZstdDecompressor decompressor;
    private final FileStorageManager fileStorageManager;
    private final File compressedFile;
    private final File outputFile;
    private final File tempDecompressedFile;
    private final ProgressCallback progressCallback;

    private final AtomicBoolean isRunning;
    private final AtomicBoolean isPaused;
    private final AtomicLong decompressedBytes;

    public interface ProgressCallback {
        void onProgress(ProgressEvent event);
        void onComplete(File outputFile);
        void onError(Throwable error);
    }

    private BatchDecompressionHandler(Builder builder) {
        this.decompressor = new ZstdDecompressor();
        this.fileStorageManager = builder.fileStorageManager;
        this.compressedFile = builder.compressedFile;
        this.outputFile = builder.outputFile;
        this.tempDecompressedFile = builder.tempDecompressedFile;
        this.progressCallback = builder.progressCallback;
        this.isRunning = new AtomicBoolean(false);
        this.isPaused = new AtomicBoolean(false);
        this.decompressedBytes = new AtomicLong(0);
    }

    /**
     * Starts the decompression process
     * Should be called after download completes
     */
    public void start() {
        if (isRunning.get()) {
            return;
        }

        isRunning.set(true);
        isPaused.set(false);

        new Thread(() -> {
            try {
                runDecompression();
            } catch (Exception e) {
                if (isRunning.get()) {
                    progressCallback.onError(e);
                }
            }
        }).start();
    }

    /**
     * Pauses the decompression
     */
    public void pause() {
        isPaused.set(true);
    }

    /**
     * Resumes the decompression
     */
    public void resume() {
        isPaused.set(false);
    }

    /**
     * Cancels the decompression and cleans up
     */
    public void cancel() {
        isRunning.set(false);
        isPaused.set(true);
    }

    /**
     * @return true if decompression is running
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * @return true if decompression is paused
     */
    public boolean isPaused() {
        return isPaused.get();
    }

    private void runDecompression() throws Exception {
        // Validate input file
        if (!compressedFile.exists()) {
            throw new Exception("Compressed file not found: " + compressedFile.getAbsolutePath());
        }

        // Check if file is valid zstd
        if (!ZstdDecompressor.isValidZstdFile(compressedFile)) {
            throw new Exception("File is not a valid zstd archive: " + compressedFile.getAbsolutePath());
        }

        long compressedSize = compressedFile.length();
        decompressor.initialize(compressedSize);

        // Send initial progress
        sendProgress(0, DownloadState.DECOMPRESSING);

        // Perform decompression
        decompressor.decompressFile(compressedFile, tempDecompressedFile,
                new ZstdDecompressor.ProgressCallback() {
                    @Override
                    public void onProgress(long bytesDecompressed, long totalBytes) {
                        decompressedBytes.set(bytesDecompressed);

                        // Check for pause
                        while (isPaused.get() && isRunning.get()) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }

                        if (!isRunning.get()) {
                            return;
                        }

                        // Send progress update
                        double decompressProgress = compressedSize > 0
                                ? (double) bytesDecompressed / (double) compressedSize
                                : 0.0;

                        sendProgress(decompressProgress, DownloadState.DECOMPRESSING);
                    }
                });

        if (!isRunning.get()) {
            // Cancelled
            fileStorageManager.safeDelete(tempDecompressedFile);
            return;
        }

        // Finalize - atomic rename
        if (!fileStorageManager.atomicRename(tempDecompressedFile, outputFile)) {
            throw new Exception("Failed to rename temp file to output file");
        }

        // Delete compressed file after successful decompression
        fileStorageManager.safeDelete(compressedFile);

        // Send completion event
        ProgressEvent completionEvent = ProgressEvent.builder()
                .state(DownloadState.COMPLETED)
                .downloadProgress(1.0)
                .decompressionProgress(1.0)
                .overallProgress(1.0)
                .downloadedBytes(compressedSize)
                .totalBytes(compressedSize)
                .decompressedBytes(decompressedBytes.get())
                .totalDecompressedBytes(decompressedBytes.get())
                .statusMessage("Decompression complete")
                .build();

        progressCallback.onProgress(completionEvent);
        progressCallback.onComplete(outputFile);

        isRunning.set(false);
    }

    /**
     * Sends progress event
     * In batch mode, progress is shown sequentially based on current phase
     *
     * @param decompressProgress Decompression progress (0.0 to 1.0)
     * @param state Current download state
     */
    private void sendProgress(double decompressProgress, DownloadState state) {
        // In batch mode:
        // - Download phase: progress reflects download only (0-100%)
        // - Decompression phase: progress reflects decompression only (0-100%)

        ProgressEvent event = ProgressEvent.builder()
                .state(state)
                .downloadProgress(1.0) // Download is complete in batch mode
                .decompressionProgress(decompressProgress)
                .overallProgress(decompressProgress) // Show decompress progress as overall
                .downloadedBytes(compressedFile.length())
                .totalBytes(compressedFile.length())
                .decompressedBytes(decompressedBytes.get())
                .statusMessage(state == DownloadState.DECOMPRESSING
                        ? "Decompressing... " + (int) (decompressProgress * 100) + "%"
                        : "Processing...")
                .build();

        progressCallback.onProgress(event);
    }

    /**
     * @return Current decompressed bytes count
     */
    public long getDecompressedBytes() {
        return decompressedBytes.get();
    }

    /**
     * Calculates final output file size estimate
     *
     * @return Estimated decompressed size
     */
    public long estimateOutputSize() {
        if (!compressedFile.exists()) {
            return -1;
        }
        return ZstdDecompressor.estimateDecompressedSize(compressedFile.length());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private FileStorageManager fileStorageManager;
        private File compressedFile;
        private File outputFile;
        private File tempDecompressedFile;
        private ProgressCallback progressCallback;

        public Builder fileStorageManager(FileStorageManager manager) {
            this.fileStorageManager = manager;
            return this;
        }

        public Builder compressedFile(File file) {
            this.compressedFile = file;
            return this;
        }

        public Builder outputFile(File file) {
            this.outputFile = file;
            return this;
        }

        public Builder tempDecompressedFile(File file) {
            this.tempDecompressedFile = file;
            return this;
        }

        public Builder progressCallback(ProgressCallback callback) {
            this.progressCallback = callback;
            return this;
        }

        public BatchDecompressionHandler build() {
            if (fileStorageManager == null || compressedFile == null ||
                outputFile == null || tempDecompressedFile == null ||
                progressCallback == null) {
                throw new IllegalStateException(
                    "fileStorageManager, compressedFile, outputFile, tempDecompressedFile, and progressCallback are required");
            }
            return new BatchDecompressionHandler(this);
        }
    }
}
