package com.zstd.downloader.storage;

import android.content.Context;
import android.os.Environment;

import com.zstd.downloader.Constants;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Manages file storage operations with atomic writes and proper cleanup.
 * All operations use temporary files with atomic rename for safety.
 */
public class FileStorageManager {

    private final Context context;
    private final File baseDirectory;

    public FileStorageManager(Context context) {
        this.context = context.getApplicationContext();
        this.baseDirectory = getOrCreateBaseDirectory();
    }

    /**
     * Gets or creates the base download directory
     */
    private File getOrCreateBaseDirectory() {
        // Use app-specific external storage directory (no permissions needed on Android 10+)
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || !dir.exists() && !dir.mkdirs()) {
            // Fallback to internal storage
            dir = new File(context.getFilesDir(), "downloads");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Cannot create download directory");
            }
        }
        return dir;
    }

    /**
     * Creates a temporary file for compressed data download
     * The file will have a .tmp extension for atomic operations
     *
     * @param url The download URL (used to generate a unique filename)
     * @return File pointing to the temporary compressed file
     */
    public File createTempCompressedFile(String url) {
        String filename = generateFilenameFromUrl(url) + Constants.COMPRESSED_TEMP_SUFFIX;
        return new File(baseDirectory, filename);
    }

    /**
     * Creates a temporary file for decompressed data
     * Used in streaming mode for atomic final output
     *
     * @param url The download URL
     * @return File pointing to the temporary decompressed file
     */
    public File createTempDecompressedFile(String url) {
        String filename = generateFilenameFromUrl(url) + Constants.TEMP_FILE_EXTENSION;
        return new File(baseDirectory, filename);
    }

    /**
     * Creates the final output file
     *
     * @param url The download URL
     * @return File pointing to the final output location
     */
    public File createOutputFile(String url) {
        String filename = generateFilenameFromUrl(url);
        return new File(baseDirectory, filename);
    }

    /**
     * Creates a new temp file with unique name
     *
     * @param prefix File name prefix
     * @param suffix File name suffix (extension)
     * @return New temp file
     * @throws IOException if file creation fails
     */
    public File createTempFile(String prefix, String suffix) throws IOException {
        return File.createTempFile(prefix, suffix, baseDirectory);
    }

    /**
     * Atomically renames a file to its final destination
     * This is the key operation for atomic file operations
     *
     * @param sourceFile The source file (typically a .tmp file)
     * @param targetFile The destination file
     * @return true if the rename succeeded
     */
    public boolean atomicRename(File sourceFile, File targetFile) {
        if (!sourceFile.exists()) {
            return false;
        }

        // Delete target if it exists
        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                return false;
            }
        }

        // Atomic rename operation
        return sourceFile.renameTo(targetFile);
    }

    /**
     * Verifies a file's integrity by checking if it exists and has expected size
     *
     * @param file The file to verify
     * @param expectedSize Expected size in bytes (-1 to skip size check)
     * @return true if file exists and matches expected size
     */
    public boolean verifyFile(File file, long expectedSize) {
        if (!file.exists()) {
            return false;
        }
        if (expectedSize >= 0 && file.length() != expectedSize) {
            return false;
        }
        return true;
    }

    /**
     * Gets the size of a file
     *
     * @param file The file to check
     * @return File size in bytes, or -1 if file doesn't exist
     */
    public long getFileSize(File file) {
        return file.exists() ? file.length() : -1;
    }

    /**
     * Deletes a file safely
     *
     * @param file The file to delete
     * @return true if deletion succeeded or file didn't exist
     */
    public boolean safeDelete(File file) {
        return file.exists() && file.delete();
    }

    /**
     * Cleans up temporary files for a given URL
     *
     * @param url The download URL
     * @return Number of files cleaned up
     */
    public int cleanupTempFiles(String url) {
        int cleaned = 0;
        String baseName = generateFilenameFromUrl(url);

        File compressedTemp = new File(baseDirectory, baseName + Constants.COMPRESSED_TEMP_SUFFIX);
        File decompressedTemp = new File(baseDirectory, baseName + Constants.TEMP_FILE_EXTENSION);

        if (safeDelete(compressedTemp)) cleaned++;
        if (safeDelete(decompressedTemp)) cleaned++;

        return cleaned;
    }

    /**
     * Cleans up all temporary files in the download directory
     *
     * @return Number of files cleaned up
     */
    public int cleanupAllTempFiles() {
        int cleaned = 0;
        File[] files = baseDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(Constants.TEMP_FILE_EXTENSION) ||
                        file.getName().endsWith(Constants.COMPRESSED_TEMP_SUFFIX)) {
                    if (safeDelete(file)) {
                        cleaned++;
                    }
                }
            }
        }
        return cleaned;
    }

    /**
     * Gets the total space used by downloads
     *
     * @return Total bytes used
     */
    public long getTotalUsedSpace() {
        long total = 0;
        File[] files = baseDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    total += file.length();
                }
            }
        }
        return total;
    }

    /**
     * Gets available space in the download directory
     *
     * @return Available bytes
     */
    public long getAvailableSpace() {
        return baseDirectory.getUsableSpace();
    }

    /**
     * Generates a unique filename from a URL
     * Uses SHA-256 hash of the URL to create a unique, filesystem-safe filename
     *
     * @param url The URL
     * @return A unique filename
     */
    private String generateFilenameFromUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            // Use first 16 characters of hash for filename
            return "zstd_" + hexString.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash if SHA-256 not available
            return "zstd_" + String.valueOf(url.hashCode());
        }
    }

    /**
     * Gets a file object from a path string
     * Handles both absolute and relative paths
     *
     * @param path The file path
     * @return File object
     */
    public File getFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(baseDirectory, path);
        }
        return file;
    }

    /**
     * Checks if a file exists
     *
     * @param path The file path
     * @return true if file exists
     */
    public boolean fileExists(String path) {
        File file = getFile(path);
        return file != null && file.exists();
    }

    /**
     * Creates parent directories for a file if they don't exist
     *
     * @param file The file whose parents need to be created
     * @return true if directories exist or were created successfully
     */
    public boolean ensureParentDirectories(File file) {
        File parent = file.getParentFile();
        return parent != null && (parent.exists() || parent.mkdirs());
    }

    /**
     * Gets the base download directory
     *
     * @return The base directory file
     */
    public File getBaseDirectory() {
        return baseDirectory;
    }

    /**
     * Gets all non-temporary files in the download directory
     *
     * @return Array of completed download files
     */
    public File[] getCompletedDownloads() {
        File[] allFiles = baseDirectory.listFiles();
        if (allFiles == null) {
            return new File[0];
        }

        java.util.List<File> completed = new java.util.ArrayList<>();
        for (File file : allFiles) {
            if (file.isFile() && !file.getName().endsWith(Constants.TEMP_FILE_EXTENSION) &&
                    !file.getName().endsWith(Constants.COMPRESSED_TEMP_SUFFIX)) {
                completed.add(file);
            }
        }
        return completed.toArray(new File[0]);
    }
}
