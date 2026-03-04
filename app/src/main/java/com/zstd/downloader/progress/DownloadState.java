package com.zstd.downloader.progress;

/**
 * Represents the current state of a download operation.
 * Follows the state machine: IDLE → DOWNLOADING → PAUSED → DOWNLOADING → DECOMPRESSING → COMPLETED
 *                                              ↓
                                           FAILED
 */
public enum DownloadState {
    /**
     * Initial state - no download is active
     */
    IDLE(false),

    /**
     * Currently downloading data from the server
     */
    DOWNLOADING(false),

    /**
     * Download is paused by the user, can be resumed
     */
    PAUSED(false),

    /**
     * Download complete, currently decompressing the data
     */
    DECOMPRESSING(false),

    /**
     * Both download and decompression complete successfully
     */
    COMPLETED(true),

    /**
     * An error occurred during download or decompression
     */
    FAILED(true);

    private final boolean isTerminal;

    DownloadState(boolean isTerminal) {
        this.isTerminal = isTerminal;
    }

    /**
     * @return true if this is a terminal state (no further transitions possible)
     */
    public boolean isTerminal() {
        return isTerminal;
    }

    /**
     * @return true if the download can be paused from this state
     */
    public boolean canPause() {
        return this == DOWNLOADING;
    }

    /**
     * @return true if the download can be resumed from this state
     */
    public boolean canResume() {
        return this == PAUSED;
    }

    /**
     * @return true if a new download can be started from this state
     */
    public boolean canStart() {
        return this == IDLE || this == COMPLETED || this == FAILED;
    }
}
