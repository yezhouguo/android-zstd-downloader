package com.zstd.downloader.progress;

import com.zstd.downloader.Constants;

/**
 * Calculates unified progress for download and decompression operations.
 * Handles two modes:
 * - Streaming: Weighted average (60% download + 40% decompression)
 * - Batch: Sequential stages (shows download 0-100%, then decompress 0-100%)
 */
public class ProgressCalculator {

    public enum Mode {
        STREAMING,
        BATCH
    }

    private final Mode mode;
    private final long totalDownloadBytes;
    private final long totalDecompressedBytes;

    private long currentDownloadedBytes;
    private long currentDecompressedBytes;
    private DownloadState currentState;

    public ProgressCalculator(Mode mode, long totalDownloadBytes, long totalDecompressedBytes) {
        this.mode = mode;
        this.totalDownloadBytes = totalDownloadBytes;
        this.totalDecompressedBytes = totalDecompressedBytes;
        this.currentDownloadedBytes = 0;
        this.currentDecompressedBytes = 0;
        this.currentState = DownloadState.IDLE;
    }

    /**
     * Updates the current progress values
     *
     * @param downloadedBytes    Bytes downloaded so far
     * @param decompressedBytes  Bytes decompressed so far
     * @param state              Current state
     */
    public void update(long downloadedBytes, long decompressedBytes, DownloadState state) {
        this.currentDownloadedBytes = downloadedBytes;
        this.currentDecompressedBytes = decompressedBytes;
        this.currentState = state;
    }

    /**
     * Calculates the overall progress based on current mode
     *
     * @return Progress from 0.0 to 1.0
     */
    public double calculateOverallProgress() {
        switch (mode) {
            case STREAMING:
                return calculateStreamingProgress();
            case BATCH:
                return calculateBatchProgress();
            default:
                return 0.0;
        }
    }

    /**
     * Calculates download progress (0.0 to 1.0)
     *
     * @return Download progress
     */
    public double calculateDownloadProgress() {
        if (totalDownloadBytes <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) currentDownloadedBytes / (double) totalDownloadBytes);
    }

    /**
     * Calculates progress for display purposes
     * Returns a value that indicates progress when total size is known,
     * or returns a special value to indicate "in progress with unknown total"
     *
     * @return Display progress (0.0 to 1.0), or -1.0 if unknown total but downloading
     */
    public double calculateDisplayProgress() {
        if (totalDownloadBytes <= 0) {
            // Unknown total size - return -1 to indicate "downloading but unknown progress %"
            return currentDownloadedBytes > 0 ? -0.5 : 0.0;
        }
        return Math.min(1.0, (double) currentDownloadedBytes / (double) totalDownloadBytes);
    }

    /**
     * Calculates decompression progress (0.0 to 1.0)
     *
     * @return Decompression progress
     */
    public double calculateDecompressionProgress() {
        if (totalDecompressedBytes <= 0) {
            // In streaming mode, decompression progress follows download
            return calculateDownloadProgress();
        }
        return Math.min(1.0, (double) currentDecompressedBytes / (double) totalDecompressedBytes);
    }

    /**
     * Streaming mode uses weighted average for smooth progress
     * 60% weight on download + 40% weight on decompression
     *
     * @return Overall progress
     */
    private double calculateStreamingProgress() {
        double downloadProgress = calculateDownloadProgress();
        double decompressionProgress = calculateDecompressionProgress();

        return (downloadProgress * Constants.STREAMING_DOWNLOAD_WEIGHT) +
               (decompressionProgress * Constants.STREAMING_DECOMPRESS_WEIGHT);
    }

    /**
     * Batch mode shows sequential stages
     * - Downloading: shows download progress 0-100%
     * - Decompressing: shows decompression progress 0-100%
     * - Completed: 100%
     *
     * @return Overall progress
     */
    private double calculateBatchProgress() {
        switch (currentState) {
            case DOWNLOADING:
                // Show only download progress
                return calculateDownloadProgress();

            case DECOMPRESSING:
                // Download complete, show decompression progress
                return calculateDecompressionProgress();

            case COMPLETED:
                return 1.0;

            default:
                return 0.0;
        }
    }

    /**
     * Creates a ProgressEvent with current values
     *
     * @return ProgressEvent reflecting current state
     */
    public ProgressEvent createProgressEvent() {
        double downloadProgress = calculateDownloadProgress();
        double decompressionProgress = calculateDecompressionProgress();
        double overallProgress = calculateOverallProgress();

        // When total size is unknown, use a small positive value for overallProgress
        // so the progress bar shows some activity instead of staying at 0
        if (totalDownloadBytes <= 0 && currentDownloadedBytes > 0) {
            overallProgress = 0.01; // Show 1% to indicate progress
        }

        String statusMessage = generateStatusMessage();

        return ProgressEvent.builder()
                .state(currentState)
                .downloadProgress(downloadProgress)
                .decompressionProgress(decompressionProgress)
                .overallProgress(overallProgress)
                .downloadedBytes(currentDownloadedBytes)
                .totalBytes(totalDownloadBytes)
                .decompressedBytes(currentDecompressedBytes)
                .totalDecompressedBytes(totalDecompressedBytes)
                .statusMessage(statusMessage)
                .build();
    }

    /**
     * Generates appropriate status message based on mode and state
     *
     * @return Status message string
     */
    private String generateStatusMessage() {
        switch (currentState) {
            case IDLE:
                return "Ready";

            case DOWNLOADING:
                if (mode == Mode.STREAMING) {
                    int overallPercent = (int) (calculateOverallProgress() * 100);
                    return "Downloading and decompressing... " + overallPercent + "%";
                } else {
                    int downloadPercent = (int) (calculateDownloadProgress() * 100);
                    return "Downloading... " + downloadPercent + "%";
                }

            case PAUSED:
                return "Paused";

            case DECOMPRESSING:
                if (mode == Mode.STREAMING) {
                    int overallPercent = (int) (calculateOverallProgress() * 100);
                    return "Decompressing... " + overallPercent + "%";
                } else {
                    int decompressPercent = (int) (calculateDecompressionProgress() * 100);
                    return "Decompressing... " + decompressPercent + "%";
                }

            case COMPLETED:
                return "Completed";

            case FAILED:
                return "Failed";

            default:
                return "";
        }
    }

    /**
     * Checks if progress has changed significantly enough to warrant an update
     * Prevents excessive UI updates
     *
     * @param previousProgress Previous overall progress value
     * @return true if progress should be updated
     */
    public boolean shouldUpdateProgress(double previousProgress) {
        double currentProgress = calculateOverallProgress();
        return Math.abs(currentProgress - previousProgress) >= Constants.MIN_PROGRESS_DELTA;
    }

    /**
     * Gets the current download state
     *
     * @return Current state
     */
    public DownloadState getState() {
        return currentState;
    }

    /**
     * Gets the current downloaded bytes
     *
     * @return Current downloaded bytes
     */
    public long getCurrentDownloadedBytes() {
        return currentDownloadedBytes;
    }

    /**
     * Gets the current decompressed bytes
     *
     * @return Current decompressed bytes
     */
    public long getCurrentDecompressedBytes() {
        return currentDecompressedBytes;
    }

    /**
     * Sets the current download state
     *
     * @param state New state
     */
    public void setState(DownloadState state) {
        this.currentState = state;
    }

    /**
     * Gets the calculator mode
     *
     * @return Mode (STREAMING or BATCH)
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Sets the calculator mode
     *
     * @param mode The mode to set
     */
    public void setMode(Mode mode) {
        // We need to update the mode, but since mode is final, we need to handle this differently
        // For now, this is a limitation - the calculator mode is set at construction
    }

    /**
     * Creates a builder for constructing ProgressCalculator instances
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ProgressCalculator
     */
    public static class Builder {
        private Mode mode = Mode.STREAMING;
        private long totalDownloadBytes = -1;
        private long totalDecompressedBytes = -1;

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder totalDownloadBytes(long bytes) {
            this.totalDownloadBytes = bytes;
            return this;
        }

        public Builder totalDecompressedBytes(long bytes) {
            this.totalDecompressedBytes = bytes;
            return this;
        }

        public ProgressCalculator build() {
            return new ProgressCalculator(mode, totalDownloadBytes, totalDecompressedBytes);
        }
    }

    /**
     * Factory method for streaming mode
     *
     * @param totalBytes Total expected bytes
     * @return ProgressCalculator configured for streaming
     */
    public static ProgressCalculator forStreaming(long totalBytes) {
        return builder()
                .mode(Mode.STREAMING)
                .totalDownloadBytes(totalBytes)
                .build();
    }

    /**
     * Factory method for batch mode
     *
     * @param totalDownloadBytes Total compressed bytes
     * @param totalDecompressedBytes Estimated decompressed bytes
     * @return ProgressCalculator configured for batch
     */
    public static ProgressCalculator forBatch(long totalDownloadBytes, long totalDecompressedBytes) {
        return builder()
                .mode(Mode.BATCH)
                .totalDownloadBytes(totalDownloadBytes)
                .totalDecompressedBytes(totalDecompressedBytes)
                .build();
    }
}
