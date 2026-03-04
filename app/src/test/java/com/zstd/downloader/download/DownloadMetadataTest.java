package com.zstd.downloader.download;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for DownloadMetadata
 */
public class DownloadMetadataTest {

    private DownloadMetadata metadata;

    @Before
    public void setUp() {
        metadata = DownloadMetadata.builder()
                .url("https://example.com/file.zst")
                .downloadedBytes(5000)
                .totalBytes(10000)
                .etag("\"abc123\"")
                .streamingMode(true)
                .build();
    }

    @Test
    public void testBasicGetters() {
        assertEquals("https://example.com/file.zst", metadata.getUrl());
        assertEquals(5000, metadata.getDownloadedBytes());
        assertEquals(10000, metadata.getTotalBytes());
        assertEquals("\"abc123\"", metadata.getEtag());
        assertTrue(metadata.isStreamingMode());
    }

    @Test
    public void testCanResume() {
        assertTrue(metadata.canResume());
    }

    @Test
    public void testCannotResumeWithoutEtag() {
        DownloadMetadata noEtag = DownloadMetadata.builder(metadata)
                .etag(null)
                .lastModified(null)
                .build();
        assertFalse(noEtag.canResume());
    }

    @Test
    public void testCannotResumeWhenComplete() {
        DownloadMetadata complete = DownloadMetadata.builder(metadata)
                .downloadedBytes(10000)
                .build();
        assertFalse(complete.canResume());
    }

    @Test
    public void testGetRemainingBytes() {
        assertEquals(5000, metadata.getRemainingBytes());
    }

    @Test
    public void testGetDownloadProgress() {
        assertEquals(50, metadata.getDownloadProgress());
    }

    @Test
    public void testGetRangeHeaderValue() {
        assertEquals("bytes=5000-", metadata.getRangeHeaderValue());
    }

    @Test
    public void testWithDownloadedBytes() {
        DownloadMetadata updated = metadata.withDownloadedBytes(7500);
        assertEquals(7500, updated.getDownloadedBytes());
        // Original should be unchanged
        assertEquals(5000, metadata.getDownloadedBytes());
    }

    @Test
    public void testForNewDownload() {
        DownloadMetadata newDownload = DownloadMetadata.forNewDownload(
                "https://example.com/test.zst",
                false,
                "/sdcard/test.zst"
        );

        assertEquals("https://example.com/test.zst", newDownload.getUrl());
        assertEquals(0, newDownload.getDownloadedBytes());
        assertEquals(-1, newDownload.getTotalBytes());
        assertFalse(newDownload.isStreamingMode());
    }

    @Test
    public void testBuilderValidation() {
        try {
            DownloadMetadata.builder()
                    .downloadedBytes(100)
                    .build();
            fail("Should throw exception without URL");
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void testUnknownTotalSize() {
        DownloadMetadata unknown = DownloadMetadata.builder()
                .url("https://example.com/file.zst")
                .totalBytes(-1)
                .downloadedBytes(0)
                .etag("\"abc\"")
                .build();

        assertEquals(-1, unknown.getTotalBytes());
        // When total is unknown (-1), remaining bytes returns -1
        assertEquals(-1, unknown.getRemainingBytes());
        assertEquals(0, unknown.getDownloadProgress()); // 0/unknown = 0%
    }
}
