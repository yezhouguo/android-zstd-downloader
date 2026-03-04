# Android Zstd Downloader

An Android application that downloads and decompresses zstd-compressed files with support for pause/resume and breakpoint resume.

## Features

- **Dual Decompression Modes**:
  - **Streaming Mode**: Decompresses data during download for faster completion
  - **Batch Mode**: Downloads first, then decompresses the entire file

- **Advanced Download Management**:
  - Pause and resume downloads at any time
  - Breakpoint resume using HTTP Range requests
  - Automatic recovery after app restart
  - ETag/Last-Modified validation for server file changes

- **Progress Tracking**:
  - Unified progress display for both download and decompression
  - Weighted progress calculation in streaming mode (60% download + 40% decompression)
  - Real-time byte count and percentage updates

- **Storage Management**:
  - Atomic file operations using temporary files
  - Automatic cleanup of temporary files
  - App-specific external storage (no permissions needed on Android 10+)

## Requirements

- Android 5.0 (API 21) or higher
- HTTP server supporting Range requests for resume capability

## Architecture

The app follows the MVVM architecture pattern:

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────┐
│  MainActivity│──────▶│DownloadViewModel│──────▶│ DownloadManager │
└─────────────┘      └──────────────┘      └─────────────────┘
                                                  │
                     ┌────────────────────────────┼────────────────────────────┐
                     │                            │                            │
                     ▼                            ▼                            ▼
            ┌─────────────┐            ┌──────────────┐            ┌──────────────┐
            │DownloadTask │            │Decompression│            │FileStorage   │
            │(OkHttp)     │            │Handlers     │            │Manager       │
            └─────────────┘            └──────────────┘            └──────────────┘
```

### Key Components

- **DownloadTask**: HTTP download engine with Range request support
- **ZstdDecompressor**: Wrapper for zstd-jni native decompression
- **StreamingDecompressionHandler**: Manages decompress-during-download mode
- **BatchDecompressionHandler**: Manages download-then-decompress mode
- **DownloadManager**: Core orchestration layer
- **MetadataStorage**: SharedPreferences-based persistence for resume capability

## Building

```bash
./gradlew assembleDebug
```

## Usage

1. Enter a URL pointing to a zstd-compressed file
2. Toggle "Streaming Decompression" to choose mode:
   - **ON**: Decompress during download (faster, uses more storage during download)
   - **OFF**: Download first, then decompress (simpler, sequential progress)
3. Tap "Start Download"
4. Use "Pause" to pause, "Resume" to continue
5. If app is closed during download, it will resume automatically on next launch

## Dependencies

- OkHttp 4.12.0 - HTTP client with Range request support
- zstd-jni 1.5.5-11 - Zstandard compression/decompression
- AndroidX - Modern Android libraries
- Material Components - Material Design UI components

## License

MIT License
