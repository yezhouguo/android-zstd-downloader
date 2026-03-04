package com.zstd.downloader.decompression;

import com.github.luben.zstd.Zstd;
import com.zstd.downloader.Constants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Wrapper for zstd-jni library providing decompression functionality.
 * Supports both streaming (chunk-by-chunk) and batch decompression modes.
 */
public class ZstdDecompressor {

    private long totalCompressedBytes;
    private long totalDecompressedBytes;
    private boolean isInitialized;

    public ZstdDecompressor() {
        this.totalCompressedBytes = 0;
        this.totalDecompressedBytes = 0;
        this.isInitialized = false;
    }

    /**
     * Initializes the decompressor with the compressed file size
     * Call this before decompressing to get accurate progress information
     *
     * @param compressedSize Total size of the compressed file
     */
    public void initialize(long compressedSize) {
        this.totalCompressedBytes = compressedSize;
        this.totalDecompressedBytes = 0;
        this.isInitialized = true;
    }

    /**
     * Decompresses a single chunk of compressed data.
     * Used in streaming mode where data is decompressed as it's downloaded.
     *
     * @param compressedChunk The chunk of compressed data
     * @return Decompressed data
     * @throws IOException if decompression fails
     */
    public byte[] decompressChunk(byte[] compressedChunk) throws IOException {
        if (compressedChunk == null || compressedChunk.length == 0) {
            return new byte[0];
        }

        try {
            // Get the size of the decompressed data
            int originalSize = (int) Zstd.decompressedSize(compressedChunk);

            if (originalSize <= 0) {
                // Size not available in the chunk, need to estimate
                // For streaming zstd, we'll use a larger buffer
                originalSize = Constants.DECOMPRESSION_BUFFER_SIZE;
            }

            // Allocate buffer and decompress
            byte[] decompressed = new byte[originalSize];
            long size = Zstd.decompress(decompressed, compressedChunk);

            // Update counters
            totalCompressedBytes += compressedChunk.length;
            totalDecompressedBytes += size;

            // If actual size is different, create properly sized array
            if (size != originalSize) {
                byte[] trimmed = new byte[(int) size];
                System.arraycopy(decompressed, 0, trimmed, 0, (int) size);
                return trimmed;
            }

            return decompressed;

        } catch (Exception e) {
            throw new IOException("Decompression failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decompresses an entire file from compressed to decompressed format.
     * Used in batch mode after download completes.
     *
     * @param inputFile  The compressed input file
     * @param outputFile The decompressed output file
     * @param callback   Progress callback during decompression
     * @throws IOException if decompression fails
     */
    public void decompressFile(File inputFile, File outputFile, ProgressCallback callback) throws IOException {
        if (!inputFile.exists()) {
            throw new IOException("Input file does not exist: " + inputFile.getAbsolutePath());
        }

        long compressedSize = inputFile.length();
        initialize(compressedSize);

        // Use Zstd's streaming decompression
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
             FileOutputStream fileOut = new FileOutputStream(outputFile);
             BufferedOutputStream outputStream = new BufferedOutputStream(fileOut)) {

            // Zstd streaming decompression
            ZstdInputStreamDecompressor streamDecompressor = new ZstdInputStreamDecompressor(inputStream);
            byte[] buffer = new byte[Constants.DECOMPRESSION_BUFFER_SIZE];
            int bytesRead;
            long totalDecompressed = 0;

            while ((bytesRead = streamDecompressor.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalDecompressed += bytesRead;

                if (callback != null) {
                    callback.onProgress(totalDecompressed, -1);
                }
            }

            this.totalDecompressedBytes = totalDecompressed;

            // Ensure data is written to disk
            outputStream.flush();
            fileOut.getFD().sync();

        } catch (Exception e) {
            throw new IOException("File decompression failed: " + e.getMessage(), e);
        }
    }

    /**
     * Gets an estimate of decompression progress
     *
     * @return Progress from 0.0 to 1.0, or -1 if unknown
     */
    public double getProgress() {
        if (totalCompressedBytes <= 0) {
            return -1;
        }
        return (double) totalDecompressedBytes / (double) totalCompressedBytes;
    }

    /**
     * Gets the total number of bytes that have been decompressed
     *
     * @return Decompressed byte count
     */
    public long getDecompressedBytes() {
        return totalDecompressedBytes;
    }

    /**
     * Resets the decompressor state
     */
    public void reset() {
        this.totalCompressedBytes = 0;
        this.totalDecompressedBytes = 0;
        this.isInitialized = false;
    }

    /**
     * Checks if the decompressor is initialized
     *
     * @return true if initialize() was called
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Estimates the decompressed size based on compressed size
     * This is a rough estimate; actual size may vary
     *
     * @param compressedSize Compressed file size
     * @return Estimated decompressed size
     */
    public static long estimateDecompressedSize(long compressedSize) {
        // Zstd typically achieves 2-5x compression ratio
        // Use 3x as a conservative estimate
        return compressedSize * 3;
    }

    /**
     * Validates that a file is a valid zstd archive
     *
     * @param file The file to validate
     * @return true if the file appears to be a valid zstd archive
     */
    public static boolean isValidZstdFile(File file) {
        if (!file.exists() || file.length() < 4) {
            return false;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int read = fis.read(magic);
            if (read != 4) {
                return false;
            }

            // Zstd magic number: 0xFD2FB528 (little endian)
            // Check for common Zstd frame magic numbers
            int magicInt = ByteBuffer.wrap(magic).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

            // Zstd frame magic number: 0xFD2FB528
            // Zstd skippable frame: 0x184D2A50..0x184D2A5F
            return (magicInt == 0xFD2FB528) ||
                   ((magicInt & 0xFFFFFF00) == 0x184D2A50);

        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Callback interface for decompression progress
     */
    public interface ProgressCallback {
        void onProgress(long decompressedBytes, long totalBytes);
    }

    /**
     * Streaming Zstd input stream wrapper for efficient decompression
     */
    private static class ZstdInputStreamDecompressor {
        private final InputStream source;
        private byte[] buffer;
        private int bufferPosition;
        private int bufferLength;
        private boolean eof;

        ZstdInputStreamDecompressor(InputStream source) throws IOException {
            this.source = source;
            this.buffer = new byte[Constants.DECOMPRESSION_BUFFER_SIZE];
            this.bufferPosition = 0;
            this.bufferLength = 0;
            this.eof = false;
            fillBuffer();
        }

        private void fillBuffer() throws IOException {
            bufferPosition = 0;
            bufferLength = 0;

            if (eof) {
                return;
            }

            // Read compressed chunk
            byte[] compressedChunk = new byte[Constants.DECOMPRESSION_BUFFER_SIZE];
            int compressedRead = source.read(compressedChunk);

            if (compressedRead == -1) {
                eof = true;
                return;
            }

            // Decompress the chunk
            try {
                // Decompress into buffer
                int decompressedSize = (int) Zstd.decompress(compressedChunk, 0, compressedRead,
                                                             buffer, 0, buffer.length);
                bufferLength = decompressedSize;
            } catch (Exception e) {
                // If decompression fails, might be end of stream or multi-frame
                eof = true;
            }
        }

        int read(byte[] output) throws IOException {
            if (eof && bufferPosition >= bufferLength) {
                return -1;
            }

            int totalRead = 0;
            int remaining = output.length;

            while (remaining > 0 && !eof) {
                if (bufferPosition >= bufferLength) {
                    fillBuffer();
                    if (eof && bufferPosition >= bufferLength) {
                        break;
                    }
                }

                int available = bufferLength - bufferPosition;
                int toRead = Math.min(available, remaining);

                System.arraycopy(buffer, bufferPosition, output, totalRead, toRead);
                bufferPosition += toRead;
                totalRead += toRead;
                remaining -= toRead;
            }

            return totalRead > 0 ? totalRead : -1;
        }
    }
}
