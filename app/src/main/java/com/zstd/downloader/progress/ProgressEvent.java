package com.zstd.downloader.progress;

/**
 * Immutable data class containing progress information for download and decompression.
 * Used to communicate progress updates from the download engine to the UI layer via LiveData.
 */
public class ProgressEvent {

    private final DownloadState state;
    private final double overallProgress;    // 0.0 to 1.0
    private final double downloadProgress;   // 0.0 to 1.0
    private final double decompressionProgress; // 0.0 to 1.0
    private final long downloadedBytes;
    private final long totalBytes;
    private final long decompressedBytes;
    private final long totalDecompressedBytes;
    private final String statusMessage;
    private final Throwable error;

    private ProgressEvent(Builder builder) {
        this.state = builder.state;
        this.overallProgress = builder.overallProgress;
        this.downloadProgress = builder.downloadProgress;
        this.decompressionProgress = builder.decompressionProgress;
        this.downloadedBytes = builder.downloadedBytes;
        this.totalBytes = builder.totalBytes;
        this.decompressedBytes = builder.decompressedBytes;
        this.totalDecompressedBytes = builder.totalDecompressedBytes;
        this.statusMessage = builder.statusMessage;
        this.error = builder.error;
    }

    public DownloadState getState() {
        return state;
    }

    public double getOverallProgress() {
        return overallProgress;
    }

    public double getDownloadProgress() {
        return downloadProgress;
    }

    public double getDecompressionProgress() {
        return decompressionProgress;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getDecompressedBytes() {
        return decompressedBytes;
    }

    public long getTotalDecompressedBytes() {
        return totalDecompressedBytes;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Throwable getError() {
        return error;
    }

    /**
     * @return Overall progress as a percentage (0-100)
     */
    public int getProgressPercentage() {
        return (int) Math.round(overallProgress * 100);
    }

    /**
     * @return true if the operation completed successfully
     */
    public boolean isSuccessful() {
        return state == DownloadState.COMPLETED;
    }

    /**
     * @return true if the operation failed
     */
    public boolean isFailed() {
        return state == DownloadState.FAILED;
    }

    /**
     * @return Formatted string showing bytes progress (e.g., "45.2 MB / 128.0 MB")
     */
    public String getFormattedBytes() {
        return formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "Unknown";
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(ProgressEvent source) {
        return new Builder(source);
    }

    public static class Builder {
        private DownloadState state = DownloadState.IDLE;
        private double overallProgress = 0.0;
        private double downloadProgress = 0.0;
        private double decompressionProgress = 0.0;
        private long downloadedBytes = 0;
        private long totalBytes = -1;
        private long decompressedBytes = 0;
        private long totalDecompressedBytes = -1;
        private String statusMessage = "";
        private Throwable error = null;

        private Builder() {
        }

        private Builder(ProgressEvent source) {
            this.state = source.state;
            this.overallProgress = source.overallProgress;
            this.downloadProgress = source.downloadProgress;
            this.decompressionProgress = source.decompressionProgress;
            this.downloadedBytes = source.downloadedBytes;
            this.totalBytes = source.totalBytes;
            this.decompressedBytes = source.decompressedBytes;
            this.totalDecompressedBytes = source.totalDecompressedBytes;
            this.statusMessage = source.statusMessage;
            this.error = source.error;
        }

        public Builder state(DownloadState state) {
            this.state = state;
            return this;
        }

        public Builder overallProgress(double progress) {
            this.overallProgress = clamp(progress, 0.0, 1.0);
            return this;
        }

        public Builder downloadProgress(double progress) {
            this.downloadProgress = clamp(progress, 0.0, 1.0);
            return this;
        }

        public Builder decompressionProgress(double progress) {
            this.decompressionProgress = clamp(progress, 0.0, 1.0);
            return this;
        }

        public Builder downloadedBytes(long bytes) {
            this.downloadedBytes = Math.max(0, bytes);
            return this;
        }

        public Builder totalBytes(long bytes) {
            this.totalBytes = bytes;
            return this;
        }

        public Builder decompressedBytes(long bytes) {
            this.decompressedBytes = Math.max(0, bytes);
            return this;
        }

        public Builder totalDecompressedBytes(long bytes) {
            this.totalDecompressedBytes = bytes;
            return this;
        }

        public Builder statusMessage(String message) {
            this.statusMessage = message;
            return this;
        }

        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        public ProgressEvent build() {
            return new ProgressEvent(this);
        }

        private double clamp(double value, double min, double max) {
            return Math.min(Math.max(value, min), max);
        }
    }

    /**
     * Creates a ProgressEvent for the IDLE state
     */
    public static ProgressEvent idle() {
        return builder()
                .state(DownloadState.IDLE)
                .statusMessage("Ready to download")
                .build();
    }

    /**
     * Creates a ProgressEvent for a failed operation
     */
    public static ProgressEvent failed(String message, Throwable error) {
        return builder()
                .state(DownloadState.FAILED)
                .statusMessage(message)
                .error(error)
                .build();
    }

    /**
     * Creates a ProgressEvent for a completed operation
     */
    public static ProgressEvent completed(long decompressedBytes) {
        return builder()
                .state(DownloadState.COMPLETED)
                .overallProgress(1.0)
                .downloadProgress(1.0)
                .decompressionProgress(1.0)
                .statusMessage("Completed")
                .decompressedBytes(decompressedBytes)
                .build();
    }
}
