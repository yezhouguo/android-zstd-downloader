package com.zstd.downloader.progress;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ProgressEvent
 */
public class ProgressEventTest {

    private ProgressEvent event;

    @Before
    public void setUp() {
        event = ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .downloadProgress(0.5)
                .decompressionProgress(0.0)
                .overallProgress(0.5)  // Must set overallProgress explicitly
                .downloadedBytes(5000)
                .totalBytes(10000)
                .decompressedBytes(0)
                .statusMessage("Downloading...")
                .build();
    }

    @Test
    public void testProgressPercentage() {
        assertEquals(50, event.getProgressPercentage());
    }

    @Test
    public void testFormattedBytes() {
        String formatted = event.getFormattedBytes();
        assertTrue(formatted.contains("KB"));
    }

    @Test
    public void testIsSuccessful() {
        assertFalse(event.isSuccessful());
        ProgressEvent completed = ProgressEvent.completed(1000);
        assertTrue(completed.isSuccessful());
    }

    @Test
    public void testIsFailed() {
        assertFalse(event.isFailed());
        ProgressEvent failed = ProgressEvent.failed("Error", new Exception());
        assertTrue(failed.isFailed());
    }

    @Test
    public void testBuilderWithUnknownTotal() {
        ProgressEvent unknown = ProgressEvent.builder()
                .state(DownloadState.DOWNLOADING)
                .downloadedBytes(5000)
                .totalBytes(-1)
                .build();

        assertEquals(0, unknown.getProgressPercentage()); // Unknown total should show 0%
    }

    @Test
    public void testCompletedEvent() {
        ProgressEvent completed = ProgressEvent.completed(1000000);
        assertEquals(DownloadState.COMPLETED, completed.getState());
        assertEquals(100, completed.getProgressPercentage());
    }
}
