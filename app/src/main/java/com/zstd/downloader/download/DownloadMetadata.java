package com.zstd.downloader.download;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Immutable metadata about a download operation.
 * Used for persisting download state across app restarts and for resume capability.
 */
public class DownloadMetadata implements Parcelable {

    private final String url;
    private final long downloadedBytes;
    private final long totalBytes;
    private final String etag;
    private final String lastModified;
    private final boolean streamingMode;
    private final String outputPath;
    private final String tempCompressedPath;
    private final String tempDecompressedPath;
    private final long timestamp;

    private DownloadMetadata(Builder builder) {
        this.url = builder.url;
        this.downloadedBytes = builder.downloadedBytes;
        this.totalBytes = builder.totalBytes;
        this.etag = builder.etag;
        this.lastModified = builder.lastModified;
        this.streamingMode = builder.streamingMode;
        this.outputPath = builder.outputPath;
        this.tempCompressedPath = builder.tempCompressedPath;
        this.tempDecompressedPath = builder.tempDecompressedPath;
        this.timestamp = builder.timestamp;
    }

    protected DownloadMetadata(Parcel in) {
        this.url = in.readString();
        this.downloadedBytes = in.readLong();
        this.totalBytes = in.readLong();
        this.etag = in.readString();
        this.lastModified = in.readString();
        this.streamingMode = in.readByte() != 0;
        this.outputPath = in.readString();
        this.tempCompressedPath = in.readString();
        this.tempDecompressedPath = in.readString();
        this.timestamp = in.readLong();
    }

    public static final Creator<DownloadMetadata> CREATOR = new Creator<DownloadMetadata>() {
        @Override
        public DownloadMetadata createFromParcel(Parcel in) {
            return new DownloadMetadata(in);
        }

        @Override
        public DownloadMetadata[] newArray(int size) {
            return new DownloadMetadata[size];
        }
    };

    public String getUrl() {
        return url;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public boolean isStreamingMode() {
        return streamingMode;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public String getTempCompressedPath() {
        return tempCompressedPath;
    }

    public String getTempDecompressedPath() {
        return tempDecompressedPath;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * @return true if this download can be resumed
     */
    public boolean canResume() {
        return downloadedBytes > 0
                && totalBytes > 0
                && downloadedBytes < totalBytes
                && (etag != null || lastModified != null);
    }

    /**
     * @return true if the download was completed
     */
    public boolean isComplete() {
        return totalBytes > 0 && downloadedBytes >= totalBytes;
    }

    /**
     * @return remaining bytes to download
     */
    public long getRemainingBytes() {
        if (totalBytes < 0) {
            return -1;
        }
        return Math.max(0, totalBytes - downloadedBytes);
    }

    /**
     * @return download progress as a percentage (0-100)
     */
    public int getDownloadProgress() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) ((downloadedBytes * 100) / totalBytes);
    }

    /**
     * @return Range header value for HTTP resume request
     */
    public String getRangeHeaderValue() {
        return "bytes=" + downloadedBytes + "-";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(url);
        dest.writeLong(downloadedBytes);
        dest.writeLong(totalBytes);
        dest.writeString(etag);
        dest.writeString(lastModified);
        dest.writeByte((byte) (streamingMode ? 1 : 0));
        dest.writeString(outputPath);
        dest.writeString(tempCompressedPath);
        dest.writeString(tempDecompressedPath);
        dest.writeLong(timestamp);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(DownloadMetadata source) {
        return new Builder(source);
    }

    public static class Builder {
        private String url;
        private long downloadedBytes = 0;
        private long totalBytes = -1;
        private String etag;
        private String lastModified;
        private boolean streamingMode = true;
        private String outputPath;
        private String tempCompressedPath;
        private String tempDecompressedPath;
        private long timestamp = System.currentTimeMillis();

        private Builder() {
        }

        private Builder(DownloadMetadata source) {
            this.url = source.url;
            this.downloadedBytes = source.downloadedBytes;
            this.totalBytes = source.totalBytes;
            this.etag = source.etag;
            this.lastModified = source.lastModified;
            this.streamingMode = source.streamingMode;
            this.outputPath = source.outputPath;
            this.tempCompressedPath = source.tempCompressedPath;
            this.tempDecompressedPath = source.tempDecompressedPath;
            this.timestamp = source.timestamp;
        }

        public Builder url(String url) {
            this.url = url;
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

        public Builder etag(String etag) {
            this.etag = etag;
            return this;
        }

        public Builder lastModified(String lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public Builder streamingMode(boolean streaming) {
            this.streamingMode = streaming;
            return this;
        }

        public Builder outputPath(String path) {
            this.outputPath = path;
            return this;
        }

        public Builder tempCompressedPath(String path) {
            this.tempCompressedPath = path;
            return this;
        }

        public Builder tempDecompressedPath(String path) {
            this.tempDecompressedPath = path;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Validates the metadata before building
         */
        public boolean isValid() {
            return url != null && !url.isEmpty()
                    && downloadedBytes >= 0
                    && (totalBytes == -1 || totalBytes >= downloadedBytes);
        }

        public DownloadMetadata build() {
            if (!isValid()) {
                throw new IllegalStateException("Invalid DownloadMetadata: URL is required and downloadedBytes must be non-negative");
            }
            return new DownloadMetadata(this);
        }
    }

    /**
     * Creates a new metadata with updated downloaded bytes
     */
    public DownloadMetadata withDownloadedBytes(long newBytes) {
        return builder(this)
                .downloadedBytes(newBytes)
                .build();
    }

    /**
     * Creates a new metadata for a fresh download
     */
    public static DownloadMetadata forNewDownload(String url, boolean streamingMode, String outputPath) {
        return builder()
                .url(url)
                .streamingMode(streamingMode)
                .outputPath(outputPath)
                .downloadedBytes(0)
                .totalBytes(-1)
                .build();
    }
}
