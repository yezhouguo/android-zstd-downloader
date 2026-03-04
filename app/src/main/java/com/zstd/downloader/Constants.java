package com.zstd.downloader;

/**
 * Application-wide constants for the Zstd downloader.
 */
public final class Constants {

    private Constants() {
        // Prevent instantiation
    }

    // File extensions and prefixes
    public static final String TEMP_FILE_EXTENSION = ".tmp";
    public static final String ZSTD_FILE_EXTENSION = ".zst";
    public static final String COMPRESSED_TEMP_SUFFIX = ZSTD_FILE_EXTENSION + TEMP_FILE_EXTENSION;

    // Buffer sizes for I/O operations
    public static final int DOWNLOAD_BUFFER_SIZE = 8192;      // 8KB for download chunks
    public static final int DECOMPRESSION_BUFFER_SIZE = 16384; // 16KB for decompression

    // Progress weights for streaming mode
    public static final double STREAMING_DOWNLOAD_WEIGHT = 0.6;  // 60% for download
    public static final double STREAMING_DECOMPRESS_WEIGHT = 0.4; // 40% for decompression

    // SharedPreferences keys for metadata persistence
    public static final String PREFS_NAME = "zstd_downloader_prefs";
    public static final String KEY_URL = "url";
    public static final String KEY_DOWNLOADED_BYTES = "downloaded_bytes";
    public static final String KEY_TOTAL_BYTES = "total_bytes";
    public static final String KEY_ETAG = "etag";
    public static final String KEY_LAST_MODIFIED = "last_modified";
    public static final String KEY_STREAMING_MODE = "streaming_mode";
    public static final String KEY_OUTPUT_PATH = "output_path";
    public static final String KEY_TEMP_PATH = "temp_path";
    public static final String KEY_DECOMPRESSED_TEMP_PATH = "decompressed_temp_path";

    // Progress update thresholds
    public static final int MIN_PROGRESS_UPDATE_INTERVAL_MS = 100; // Update UI at most every 100ms
    public static final double MIN_PROGRESS_DELTA = 0.01; // 1% minimum change to trigger update

    // Network timeouts
    public static final int CONNECT_TIMEOUT_MS = 15000;    // 15 seconds
    public static final int READ_TIMEOUT_MS = 30000;       // 30 seconds
    public static final int WRITE_TIMEOUT_MS = 30000;      // 30 seconds

    // Retry configuration
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final int RETRY_DELAY_MS = 2000;         // 2 seconds
}
