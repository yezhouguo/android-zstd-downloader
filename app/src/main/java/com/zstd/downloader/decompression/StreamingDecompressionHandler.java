package com.zstd.downloader.decompression;

import com.zstd.downloader.Constants;
import com.zstd.downloader.progress.DownloadState;
import com.zstd.downloader.progress.ProgressEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles decompression during download (streaming mode).
 * Each downloaded chunk is immediately decompressed and written to the output file.
 * Progress uses weighted average: 60% download + 40% decompression.
 */
public class StreamingDecompressionHandler {

    private final ZstdDecompressor decompressor;
    private final File outputFile;
    private final File tempDecompressedFile;
    private final AtomicLong decompressedBytes;
    private final long totalBytes;
    private final ProgressCallback progressCallback;

    private FileOutputStream decompressedOutput;
    private boolean isOpened;

    public interface ProgressCallback {
        void onProgress(ProgressEvent event);
        void onComplete(File outputFile);
        void onError(Throwable error);
    }

    private StreamingDecompressionHandler(Builder builder) {
        this.decompressor = new ZstdDecompressor();
        this.outputFile = builder.outputFile;
        this.tempDecompressedFile = builder.tempDecompressedFile;
        this.totalBytes = builder.totalBytes;
        this.progressCallback = builder.progressCallback;
        this.decompressedBytes = new AtomicLong(0);
        this.isOpened = false;
    }

    /**
     * Opens the output file for writing
     *
     * @throws IOException if file cannot be opened
     */
    public void open() throws IOException {
        if (isOpened) {
            return;
        }

        // Create parent directories if needed
        File parent = tempDecompressedFile.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Cannot create parent directories");
            }
        }

        // Open output stream
        decompressedOutput = new FileOutputStream(tempDecompressedFile);
        isOpened = true;

        // Initialize decompressor
        decompressor.initialize(totalBytes);
    }

    /**
     * Processes a downloaded chunk - decompresses and writes to output
     * This is called for each chunk during download
     *
     * @param compressedChunk    The compressed data chunk
     * @param downloadedBytes    Total bytes downloaded so far
     * @throws IOException if decompression or write fails
     */
    public void processChunk(byte[] compressedChunk, long downloadedBytes) throws IOException {
        if (!isOpened) {
            throw new IllegalStateException("Handler not opened. Call open() first.");
        }

        try {
            // Decompress the chunk immediately
            byte[] decompressedData = decompressor.decompressChunk(compressedChunk);

            // Write decompressed data to output
            if (decompressedData.length > 0) {
                decompressedOutput.write(decompressedData);
                long newDecompressed = decompressedBytes.addAndGet(decompressedData.length);
            }

            // Calculate weighted progress
            ProgressEvent event = calculateProgress(downloadedBytes);
            progressCallback.onProgress(event);

        } catch (Exception e) {
            throw new IOException("Failed to process decompression chunk: " + e.getMessage(), e);
        }
    }

    /**
     * Appends compressed chunk to temp file for recovery purposes
     * This enables resuming if download is interrupted
     *
     * @param compressedChunk The compressed data to append
     * @param tempCompressedFile The temporary compressed file
     * @throws IOException if write fails
     */
    public void appendToTempFile(byte[] compressedChunk, File tempCompressedFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(tempCompressedFile, true)) {
            fos.write(compressedChunk);
            fos.getFD().sync();
        }
    }

    /**
     * Completes the decompression and finalizes the output file
     * Performs atomic rename from temp to final output
     *
     * @return The final output file
     * @throws IOException if finalization fails
     */
    public File complete() throws IOException {
        if (!isOpened) {
            throw new IllegalStateException("Handler not opened");
        }

        try {
            // Flush and close output stream
            if (decompressedOutput != null) {
                decompressedOutput.flush();
                decompressedOutput.getFD().sync();
                decompressedOutput.close();
            }

            // Send final progress event
            ProgressEvent finalEvent = ProgressEvent.builder()
                    .state(DownloadState.DECOMPRESSING)
                    .downloadProgress(1.0)
                    .decompressionProgress(1.0)
                    .overallProgress(1.0)
                    .downloadedBytes(totalBytes)
                    .totalBytes(totalBytes)
                    .decompressedBytes(decompressedBytes.get())
                    .statusMessage("Decompression complete")
                    .build();

            progressCallback.onProgress(finalEvent);

            // Atomic rename to final output
            if (!tempDecompressedFile.renameTo(outputFile)) {
                throw new IOException("Failed to rename temp file to output file");
            }

            progressCallback.onComplete(outputFile);
            return outputFile;

        } finally {
            isOpened = false;
        }
    }

    /**
     * Cancels the operation and cleans up temporary files
     */
    public void cancel() {
        try {
            if (decompressedOutput != null) {
                decompressedOutput.close();
            }
        } catch (IOException e) {
            // Ignore
        } finally {
            isOpened = false;

            // Clean up temp file
            if (tempDecompressedFile.exists()) {
                tempDecompressedFile.delete();
            }
        }
    }

    /**
     * Pauses the operation - keeps temp file for resume
     */
    public void pause() {
        try {
            if (decompressedOutput != null) {
                decompressedOutput.flush();
                decompressedOutput.getFD().sync();
                decompressedOutput.close();
            }
        } catch (IOException e) {
            // Ignore
        } finally {
            isOpened = false;
        }
    }

    /**
     * Resumes from paused state
     *
     * @throws IOException if resume fails
     */
    public void resume() throws IOException {
        if (isOpened) {
            return;
        }

        // Reopen in append mode
        decompressedOutput = new FileOutputStream(tempDecompressedFile, true);
        isOpened = true;
    }

    /**
     * Calculates overall progress using weighted average
     * Streaming mode: 60% download weight + 40% decompression weight
     *
     * @param downloadedBytes Current downloaded bytes
     * @return ProgressEvent with calculated progress
     */
    private ProgressEvent calculateProgress(long downloadedBytes) {
        double downloadProgress = totalBytes > 0
                ? (double) downloadedBytes / (double) totalBytes
                : 0.0;

        // For streaming, decompression progress roughly follows download progress
        // This is an approximation since we don't know the final decompressed size
        double decompressionProgress = downloadProgress;

        // Weighted average for overall progress
        double overallProgress =
                (downloadProgress * Constants.STREAMING_DOWNLOAD_WEIGHT) +
                (decompressionProgress * Constants.STREAMING_DECOMPRESS_WEIGHT);

        return ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .downloadProgress(downloadProgress)
                .decompressionProgress(decompressionProgress)
                .overallProgress(overallProgress)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .decompressedBytes(decompressedBytes.get())
                .statusMessage("Downloading and decompressing...")
                .build();
    }

    /**
     * @return Current number of decompressed bytes
     */
    public long getDecompressedBytes() {
        return decompressedBytes.get();
    }

    /**
     * @return true if the handler is open
     */
    public boolean isOpen() {
        return isOpened;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private File outputFile;
        private File tempDecompressedFile;
        private long totalBytes = -1;
        private ProgressCallback progressCallback;

        public Builder outputFile(File file) {
            this.outputFile = file;
            return this;
        }

        public Builder tempDecompressedFile(File file) {
            this.tempDecompressedFile = file;
            return this;
        }

        public Builder totalBytes(long bytes) {
            this.totalBytes = bytes;
            return this;
        }

        public Builder progressCallback(ProgressCallback callback) {
            this.progressCallback = callback;
            return this;
        }

        public StreamingDecompressionHandler build() {
            if (outputFile == null || tempDecompressedFile == null || progressCallback == null) {
                throw new IllegalStateException("outputFile, tempDecompressedFile, and progressCallback are required");
            }
            return new StreamingDecompressionHandler(this);
        }
    }
}
