package com.zstd.downloader.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.zstd.downloader.download.DownloadManager;
import com.zstd.downloader.download.DownloadMetadata;
import com.zstd.downloader.download.MetadataStorage;
import com.zstd.downloader.progress.DownloadState;
import com.zstd.downloader.progress.ProgressEvent;
import com.zstd.downloader.storage.FileStorageManager;

import java.io.File;

/**
 * MVVM ViewModel for managing download state and UI binding.
 * Provides LiveData streams for progress updates and exposes commands for UI actions.
 */
public class DownloadViewModel extends AndroidViewModel {

    private final FileStorageManager fileStorageManager;
    private final MetadataStorage metadataStorage;
    private DownloadManager downloadManager;

    // LiveData for UI observation
    private final MutableLiveData<ProgressEvent> progressEvent;
    private final MutableLiveData<Boolean> isStreamingMode;
    private final MutableLiveData<String> urlInput;
    private final MutableLiveData<Boolean> canResume;
    private final MutableLiveData<String> errorMessage;

    private boolean isInitialized = false;

    public DownloadViewModel(@NonNull Application application) {
        super(application);
        this.fileStorageManager = new FileStorageManager(application);
        this.metadataStorage = new MetadataStorage(application, fileStorageManager);

        this.progressEvent = new MutableLiveData<>(ProgressEvent.idle());
        this.isStreamingMode = new MutableLiveData<>(true);
        this.urlInput = new MutableLiveData<>("");
        this.canResume = new MutableLiveData<>(false);
        this.errorMessage = new MutableLiveData<>(null);

        initializeDownloadManager();
        checkResumeCapability();
    }

    private void initializeDownloadManager() {
        if (isInitialized) {
            return;
        }

        downloadManager = DownloadManager.builder()
                .fileStorageManager(fileStorageManager)
                .metadataStorage(metadataStorage)
                .listener(new DownloadManager.DownloadManagerListener() {
                    @Override
                    public void onProgress(ProgressEvent event) {
                        progressEvent.postValue(event);

                        // Update resume capability based on state
                        if (event.getState() == DownloadState.PAUSED) {
                            canResume.postValue(true);
                        }
                    }

                    @Override
                    public void onComplete(File outputFile) {
                        canResume.postValue(false);
                    }

                    @Override
                    public void onError(Throwable error) {
                        errorMessage.postValue(error.getMessage());
                        canResume.postValue(false);
                    }
                })
                .build();

        isInitialized = true;
    }

    /**
     * Checks if there's a resumeable download available
     */
    private void checkResumeCapability() {
        boolean canResumeDownload = downloadManager.canResume();
        canResume.setValue(canResumeDownload);

        if (canResumeDownload) {
            DownloadMetadata metadata = metadataStorage.loadWithUpdatedPaths();
            if (metadata != null) {
                urlInput.setValue(metadata.getUrl());
                isStreamingMode.setValue(metadata.isStreamingMode());
            }
        }
    }

    // LiveData getters
    public LiveData<ProgressEvent> getProgressEvent() {
        return progressEvent;
    }

    public LiveData<Boolean> isStreamingMode() {
        return isStreamingMode;
    }

    public LiveData<String> getUrlInput() {
        return urlInput;
    }

    public LiveData<Boolean> canResume() {
        return canResume;
    }

    public LiveData<String> getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the URL input value
     */
    public void setUrlInput(String url) {
        urlInput.setValue(url);
    }

    /**
     * Sets whether streaming mode is enabled
     */
    public void setStreamingMode(boolean streaming) {
        isStreamingMode.setValue(streaming);
    }

    /**
     * Starts a new download with the current URL and mode settings
     *
     * @return true if download started successfully
     */
    public boolean startDownload() {
        String url = urlInput.getValue();
        if (url == null || url.trim().isEmpty()) {
            errorMessage.setValue("Please enter a URL");
            return false;
        }

        if (!isValidUrl(url)) {
            errorMessage.setValue("Invalid URL format");
            return false;
        }

        Boolean streaming = isStreamingMode.getValue();
        boolean streamingMode = streaming != null ? streaming : true;

        try {
            downloadManager.startDownload(url.trim(), streamingMode);
            clearErrorMessage();
            return true;
        } catch (Exception e) {
            errorMessage.setValue("Failed to start download: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resumes a paused download
     *
     * @return true if resume started successfully
     */
    public boolean resumeDownload() {
        try {
            downloadManager.resumeDownload();
            clearErrorMessage();
            return true;
        } catch (Exception e) {
            errorMessage.setValue("Failed to resume: " + e.getMessage());
            return false;
        }
    }

    /**
     * Pauses the current download
     */
    public void pauseDownload() {
        downloadManager.pauseDownload();
    }

    /**
     * Cancels the current download and cleans up
     */
    public void cancelDownload() {
        downloadManager.cancelDownload();
        canResume.setValue(false);
        resetProgress();
    }

    /**
     * Gets the current download state
     *
     * @return Current state
     */
    public DownloadState getCurrentState() {
        return downloadManager.getState();
    }

    /**
     * Gets the current download metadata if available
     *
     * @return Current metadata or null
     */
    public DownloadMetadata getMetadata() {
        return downloadManager.getMetadata();
    }

    /**
     * Clears the error message
     */
    public void clearErrorMessage() {
        errorMessage.setValue(null);
    }

    /**
     * Resets the progress to idle state
     */
    private void resetProgress() {
        progressEvent.setValue(ProgressEvent.idle());
    }

    /**
     * Validates that a string is a valid URL
     *
     * @param url The URL string to validate
     * @return true if valid URL
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String trimmed = url.trim().toLowerCase();

        // Check for protocol
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false;
        }

        // Basic URL format validation
        try {
            new android.net.Uri.Builder()
                    .scheme(trimmed.startsWith("https://") ? "https" : "http")
                    .build();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if download can be paused
     *
     * @return true if current state can be paused
     */
    public boolean canPause() {
        DownloadState state = getCurrentState();
        return state == DownloadState.DOWNLOADING || state == DownloadState.DECOMPRESSING;
    }

    /**
     * Checks if download can be started
     *
     * @return true if a new download can be started
     */
    public boolean canStart() {
        DownloadState state = getCurrentState();
        String url = urlInput.getValue();
        return state.canStart() && url != null && !url.trim().isEmpty();
    }

    /**
     * Gets formatted status message for UI
     *
     * @return Formatted status string
     */
    public String getFormattedStatus() {
        ProgressEvent event = progressEvent.getValue();
        if (event == null) {
            return "";
        }

        switch (event.getState()) {
            case IDLE:
                return "Ready to download";
            case DOWNLOADING:
                if (event.getDecompressionProgress() > 0) {
                    return "Downloading and decompressing...";
                }
                return "Downloading...";
            case DECOMPRESSING:
                return "Decompressing...";
            case PAUSED:
                return "Paused";
            case COMPLETED:
                return "Completed";
            case FAILED:
                return "Failed: " + (event.getError() != null ? event.getError().getMessage() : "Unknown error");
            default:
                return "";
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Clean up when ViewModel is destroyed
        if (downloadManager != null) {
            DownloadState state = downloadManager.getState();
            if (state == DownloadState.DOWNLOADING || state == DownloadState.DECOMPRESSING) {
                // Don't cancel ongoing downloads when screen rotates
                // Just release references
            }
        }
    }

    /**
     * Factory class for creating DownloadViewModel instances
     */
    public static class Factory extends androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory {

        private final Application application;

        public Factory(@NonNull Application application) {
            super(application);
            this.application = application;
        }

        @NonNull
        @Override
        public <T extends androidx.lifecycle.ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(DownloadViewModel.class)) {
                return (T) new DownloadViewModel(application);
            }
            throw new IllegalArgumentException("Unknown ViewModel class");
        }
    }
}
