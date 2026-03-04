package com.zstd.downloader.progress;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ProgressCalculator
 */
public class ProgressCalculatorTest {

    private ProgressCalculator streamingCalculator;
    private ProgressCalculator batchCalculator;

    @Before
    public void setUp() {
        streamingCalculator = ProgressCalculator.builder()
                .mode(ProgressCalculator.Mode.STREAMING)
                .totalDownloadBytes(10000)
                .totalDecompressedBytes(30000)
                .build();

        batchCalculator = ProgressCalculator.builder()
                .mode(ProgressCalculator.Mode.BATCH)
                .totalDownloadBytes(10000)
                .totalDecompressedBytes(30000)
                .build();
    }

    @Test
    public void testStreamingModeProgress() {
        streamingCalculator.update(6000, 0, DownloadState.DOWNLOADING);

        // Streaming: 60% download * 0.6 = 36% overall
        double overall = streamingCalculator.calculateOverallProgress();
        assertEquals(0.36, overall, 0.01);
    }

    @Test
    public void testStreamingModeWithDecompression() {
        streamingCalculator.update(10000, 15000, DownloadState.DECOMPRESSING);

        // Download 100% * 0.6 = 0.6, Decompress 50% * 0.4 = 0.2, Total = 0.8
        double overall = streamingCalculator.calculateOverallProgress();
        assertEquals(0.8, overall, 0.01);
    }

    @Test
    public void testBatchModeDownloadPhase() {
        batchCalculator.update(6000, 0, DownloadState.DOWNLOADING);

        // Batch mode: only show download progress during download
        double overall = batchCalculator.calculateOverallProgress();
        assertEquals(0.6, overall, 0.01);
    }

    @Test
    public void testBatchModeDecompressionPhase() {
        batchCalculator.update(10000, 15000, DownloadState.DECOMPRESSING);

        // Batch mode: only show decompression progress during decompression
        double overall = batchCalculator.calculateOverallProgress();
        assertEquals(0.5, overall, 0.01);
    }

    @Test
    public void testUnknownTotalBytes() {
        ProgressCalculator unknown = ProgressCalculator.forStreaming(-1);
        unknown.update(5000, 0, DownloadState.DOWNLOADING);

        // With unknown total, progress should be 0
        assertEquals(0.0, unknown.calculateOverallProgress(), 0.001);
    }

    @Test
    public void testShouldUpdateProgress() {
        streamingCalculator.update(1000, 0, DownloadState.DOWNLOADING);
        double prevProgress = streamingCalculator.calculateOverallProgress();

        // Small change should trigger update
        streamingCalculator.update(2000, 0, DownloadState.DOWNLOADING);
        assertTrue(streamingCalculator.shouldUpdateProgress(prevProgress));

        // Very small change should NOT trigger update
        double newProgress = streamingCalculator.calculateOverallProgress();
        assertFalse(streamingCalculator.shouldUpdateProgress(newProgress));
    }

    @Test
    public void testCreateProgressEvent() {
        streamingCalculator.update(5000, 0, DownloadState.DOWNLOADING);
        ProgressEvent event = streamingCalculator.createProgressEvent();

        assertEquals(DownloadState.DOWNLOADING, event.getState());
        assertEquals(5000, event.getDownloadedBytes());
        assertEquals(10000, event.getTotalBytes());

        // Check overall progress calculation
        // 50% download * 0.6 = 30% overall
        assertEquals(30, event.getProgressPercentage());
    }

    @Test
    public void testZeroDownloadedBytes() {
        streamingCalculator.update(0, 0, DownloadState.IDLE);
        ProgressEvent event = streamingCalculator.createProgressEvent();

        assertEquals(0, event.getProgressPercentage());
    }

    @Test
    public void testCompleteState() {
        streamingCalculator.update(10000, 30000, DownloadState.COMPLETED);
        assertEquals(1.0, streamingCalculator.calculateOverallProgress(), 0.001);
    }

    @Test
    public void testStreamingModeWeightedProgress() {
        // At 50% download and 0% decompression
        streamingCalculator.update(5000, 0, DownloadState.DOWNLOADING);
        assertEquals(30, streamingCalculator.createProgressEvent().getProgressPercentage());

        // At 100% download and 0% decompression
        streamingCalculator.update(10000, 0, DownloadState.DOWNLOADING);
        assertEquals(60, streamingCalculator.createProgressEvent().getProgressPercentage());

        // At 100% download and 50% decompression
        streamingCalculator.update(10000, 15000, DownloadState.DECOMPRESSING);
        assertEquals(80, streamingCalculator.createProgressEvent().getProgressPercentage());

        // At 100% download and 100% decompression
        streamingCalculator.update(10000, 30000, DownloadState.COMPLETED);
        assertEquals(100, streamingCalculator.createProgressEvent().getProgressPercentage());
    }
}
