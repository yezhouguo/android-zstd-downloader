package com.zstd.downloader.download;

import android.content.Context;
import android.content.SharedPreferences;

import com.zstd.downloader.Constants;
import com.zstd.downloader.storage.FileStorageManager;

import java.io.File;

/**
 * Manages persistence of download metadata using SharedPreferences.
 * Enables resume capability by storing download state across app restarts.
 */
public class MetadataStorage {

    private final SharedPreferences prefs;
    private final FileStorageManager fileStorageManager;

    public MetadataStorage(Context context, FileStorageManager fileStorageManager) {
        this.prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.fileStorageManager = fileStorageManager;
    }

    /**
     * Saves download metadata to persistent storage
     *
     * @param metadata The metadata to save
     */
    public void save(DownloadMetadata metadata) {
        prefs.edit()
                .putString(Constants.KEY_URL, metadata.getUrl())
                .putLong(Constants.KEY_DOWNLOADED_BYTES, metadata.getDownloadedBytes())
                .putLong(Constants.KEY_TOTAL_BYTES, metadata.getTotalBytes())
                .putString(Constants.KEY_ETAG, metadata.getEtag())
                .putString(Constants.KEY_LAST_MODIFIED, metadata.getLastModified())
                .putBoolean(Constants.KEY_STREAMING_MODE, metadata.isStreamingMode())
                .putString(Constants.KEY_OUTPUT_PATH, metadata.getOutputPath())
                .putString(Constants.KEY_TEMP_PATH, metadata.getTempCompressedPath())
                .putString(Constants.KEY_DECOMPRESSED_TEMP_PATH, metadata.getTempDecompressedPath())
                .apply();
    }

    /**
     * Loads download metadata from persistent storage
     *
     * @return The loaded metadata, or null if no valid metadata exists
     */
    public DownloadMetadata load() {
        if (!hasMetadata()) {
            return null;
        }

        String url = prefs.getString(Constants.KEY_URL, null);
        if (url == null || url.isEmpty()) {
            return null;
        }

        return DownloadMetadata.builder()
                .url(url)
                .downloadedBytes(prefs.getLong(Constants.KEY_DOWNLOADED_BYTES, 0))
                .totalBytes(prefs.getLong(Constants.KEY_TOTAL_BYTES, -1))
                .etag(prefs.getString(Constants.KEY_ETAG, null))
                .lastModified(prefs.getString(Constants.KEY_LAST_MODIFIED, null))
                .streamingMode(prefs.getBoolean(Constants.KEY_STREAMING_MODE, true))
                .outputPath(prefs.getString(Constants.KEY_OUTPUT_PATH, null))
                .tempCompressedPath(prefs.getString(Constants.KEY_TEMP_PATH, null))
                .tempDecompressedPath(prefs.getString(Constants.KEY_DECOMPRESSED_TEMP_PATH, null))
                .build();
    }

    /**
     * Checks if any metadata is stored
     *
     * @return true if metadata exists
     */
    public boolean hasMetadata() {
        return prefs.contains(Constants.KEY_URL);
    }

    /**
     * Checks if the stored metadata is for the same URL
     *
     * @param url The URL to check
     * @return true if stored metadata matches the URL
     */
    public boolean isSameUrl(String url) {
        String storedUrl = prefs.getString(Constants.KEY_URL, null);
        return url != null && url.equals(storedUrl);
    }

    /**
     * Validates that a resume is possible with the stored metadata
     * Checks that:
     * 1. Metadata exists
     * 2. Temp files exist with expected sizes
     * 3. ETag or Last-Modified is available for validation
     *
     * @return true if resume is possible
     */
    public boolean canResume() {
        DownloadMetadata metadata = load();
        if (metadata == null) {
            return false;
        }

        // Check if we have validation headers
        if (metadata.getEtag() == null && metadata.getLastModified() == null) {
            return false;
        }

        // Check if temp compressed file exists with expected size
        String tempPath = metadata.getTempCompressedPath();
        if (tempPath != null) {
            File tempFile = fileStorageManager.getFile(tempPath);
            if (tempFile == null || !tempFile.exists()) {
                return false;
            }
            // Verify file size matches downloaded bytes
            if (tempFile.length() != metadata.getDownloadedBytes()) {
                return false;
            }
        }

        // For streaming mode, check decompressed temp file
        if (metadata.isStreamingMode()) {
            String decompressedTempPath = metadata.getTempDecompressedPath();
            if (decompressedTempPath != null) {
                File decompressedTemp = fileStorageManager.getFile(decompressedTempPath);
                if (decompressedTemp == null || !decompressedTemp.exists()) {
                    // Decompressed temp file might not exist if we haven't started decompressing yet
                    // This is OK, we'll recreate it
                }
            }
        }

        return true;
    }

    /**
     * Clears all stored metadata
     */
    public void clear() {
        prefs.edit().clear().apply();
    }

    /**
     * Updates only the progress-related fields in metadata
     * Use this during download to avoid full writes
     *
     * @param downloadedBytes Current downloaded bytes
     */
    public void updateProgress(long downloadedBytes) {
        prefs.edit()
                .putLong(Constants.KEY_DOWNLOADED_BYTES, downloadedBytes)
                .apply();
    }

    /**
     * Updates the total bytes when received from server
     *
     * @param totalBytes Total file size
     */
    public void updateTotalBytes(long totalBytes) {
        prefs.edit()
                .putLong(Constants.KEY_TOTAL_BYTES, totalBytes)
                .apply();
    }

    /**
     * Updates ETag received from server
     *
     * @param etag The ETag header value
     */
    public void updateEtag(String etag) {
        prefs.edit()
                .putString(Constants.KEY_ETAG, etag)
                .apply();
    }

    /**
     * Gets the downloaded bytes from stored metadata
     *
     * @return Downloaded bytes, or 0 if no metadata exists
     */
    public long getDownloadedBytes() {
        return prefs.getLong(Constants.KEY_DOWNLOADED_BYTES, 0);
    }

    /**
     * Gets the total bytes from stored metadata
     *
     * @return Total bytes, or -1 if unknown
     */
    public long getTotalBytes() {
        return prefs.getLong(Constants.KEY_TOTAL_BYTES, -1);
    }

    /**
     * Gets the ETag from stored metadata
     *
     * @return ETag string, or null if not available
     */
    public String getEtag() {
        return prefs.getString(Constants.KEY_ETAG, null);
    }

    /**
     * Gets the Last-Modified header from stored metadata
     *
     * @return Last-Modified string, or null if not available
     */
    public String getLastModified() {
        return prefs.getString(Constants.KEY_LAST_MODIFIED, null);
    }

    /**
     * Gets the streaming mode preference
     *
     * @return true if streaming mode is enabled
     */
    public boolean isStreamingMode() {
        return prefs.getBoolean(Constants.KEY_STREAMING_MODE, true);
    }

    /**
     * Creates a fresh metadata entry for a new download
     *
     * @param url          The download URL
     * @param streamingMode Whether to use streaming decompression
     * @return The new DownloadMetadata
     */
    public DownloadMetadata createForNewDownload(String url, boolean streamingMode) {
        String outputPath = fileStorageManager.createOutputFile(url).getAbsolutePath();
        String tempCompressedPath = fileStorageManager.createTempCompressedFile(url).getAbsolutePath();
        String tempDecompressedPath = fileStorageManager.createTempDecompressedFile(url).getAbsolutePath();

        return DownloadMetadata.builder()
                .url(url)
                .streamingMode(streamingMode)
                .outputPath(outputPath)
                .tempCompressedPath(tempCompressedPath)
                .tempDecompressedPath(tempDecompressedPath)
                .downloadedBytes(0)
                .totalBytes(-1)
                .build();
    }

    /**
     * Loads existing metadata and updates file paths
     * Use this when recreating FileStorageManager instance
     *
     * @return Updated metadata, or null if none exists
     */
    public DownloadMetadata loadWithUpdatedPaths() {
        DownloadMetadata metadata = load();
        if (metadata == null) {
            return null;
        }

        // Update file paths using current FileStorageManager
        String outputPath = fileStorageManager.createOutputFile(metadata.getUrl()).getAbsolutePath();
        String tempCompressedPath = fileStorageManager.createTempCompressedFile(metadata.getUrl()).getAbsolutePath();
        String tempDecompressedPath = fileStorageManager.createTempDecompressedFile(metadata.getUrl()).getAbsolutePath();

        return DownloadMetadata.builder(metadata)
                .outputPath(outputPath)
                .tempCompressedPath(tempCompressedPath)
                .tempDecompressedPath(tempDecompressedPath)
                .build();
    }
}
