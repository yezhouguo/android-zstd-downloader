package com.zstd.downloader;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.zstd.downloader.progress.DownloadState;
import com.zstd.downloader.progress.ProgressEvent;
import com.zstd.downloader.viewmodel.DownloadViewModel;

import java.io.File;

/**
 * Main activity for the Zstd Downloader app.
 * Manages UI interactions and observes ViewModel LiveData for progress updates.
 */
public class MainActivity extends AppCompatActivity {

    // UI Components
    private TextInputEditText urlEditText;
    private MaterialSwitch streamingModeSwitch;
    private View startButton;
    private View resumeButton;
    private MaterialCardView progressCard;
    private MaterialCardView infoCard;
    private MaterialTextView statusTextView;
    private View progressBar;
    private MaterialTextView progressTextView;
    private MaterialTextView bytesTextView;
    private View pauseResumeButton;
    private View cancelButton;

    // ViewModel
    private DownloadViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        initializeViewModel();
        setupObservers();
        setupClickListeners();
    }

    private void initializeViews() {
        urlEditText = findViewById(R.id.urlEditText);
        streamingModeSwitch = findViewById(R.id.streamingModeSwitch);
        startButton = findViewById(R.id.startButton);
        resumeButton = findViewById(R.id.resumeButton);
        progressCard = findViewById(R.id.progressCard);
        infoCard = findViewById(R.id.infoCard);
        statusTextView = findViewById(R.id.statusTextView);
        progressBar = findViewById(R.id.progressBar);
        progressTextView = findViewById(R.id.progressTextView);
        bytesTextView = findViewById(R.id.bytesTextView);
        pauseResumeButton = findViewById(R.id.pauseResumeButton);
        cancelButton = findViewById(R.id.cancelButton);
    }

    private void initializeViewModel() {
        ViewModelProvider.Factory factory = new DownloadViewModel.Factory(getApplication());
        viewModel = new ViewModelProvider(this, factory).get(DownloadViewModel.class);
    }

    private void setupObservers() {
        // Observe progress events
        viewModel.getProgressEvent().observe(this, this::onProgressEvent);

        // Observe streaming mode
        viewModel.isStreamingMode().observe(this, streaming -> {
            if (streamingModeSwitch.isChecked() != streaming) {
                streamingModeSwitch.setChecked(streaming);
            }
        });

        // Observe URL input
        viewModel.getUrlInput().observe(this, url -> {
            if (url != null && !url.equals(urlEditText.getText().toString())) {
                urlEditText.setText(url);
            }
        });

        // Observe resume capability
        viewModel.canResume().observe(this, canResume -> {
            resumeButton.setVisibility(canResume ? View.VISIBLE : View.GONE);
            updateButtonStates();
        });

        // Observe error messages
        viewModel.getErrorMessage().observe(this, errorMessage -> {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                showError(errorMessage);
            }
        });
    }

    private void setupClickListeners() {
        // Start button
        startButton.setOnClickListener(v -> {
            if (viewModel.startDownload()) {
                // Download started successfully
            }
        });

        // Resume button
        resumeButton.setOnClickListener(v -> {
            if (viewModel.resumeDownload()) {
                // Resume started successfully
            }
        });

        // Pause/Resume button in progress card
        pauseResumeButton.setOnClickListener(v -> {
            DownloadState state = viewModel.getCurrentState();
            if (state == DownloadState.DOWNLOADING || state == DownloadState.DECOMPRESSING) {
                viewModel.pauseDownload();
            } else if (state == DownloadState.PAUSED) {
                viewModel.resumeDownload();
            }
        });

        // Cancel button
        cancelButton.setOnClickListener(v -> viewModel.cancelDownload());

        // URL input text watcher
        urlEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setUrlInput(s.toString());
                updateButtonStates();
            }
        });

        // Streaming mode switch
        streamingModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setStreamingMode(isChecked);
        });
    }

    private void onProgressEvent(ProgressEvent event) {
        if (event == null) {
            return;
        }

        DownloadState state = event.getState();

        // Update visibility based on state
        switch (state) {
            case IDLE:
                progressCard.setVisibility(View.GONE);
                infoCard.setVisibility(View.VISIBLE);
                updateButtonStates();
                break;

            case DOWNLOADING:
            case DECOMPRESSING:
                progressCard.setVisibility(View.VISIBLE);
                infoCard.setVisibility(View.GONE);
                updateProgressUI(event);
                updateButtonStates();
                break;

            case PAUSED:
                progressCard.setVisibility(View.VISIBLE);
                infoCard.setVisibility(View.GONE);
                updateProgressUI(event);
                updateButtonStates();
                break;

            case COMPLETED:
                progressCard.setVisibility(View.VISIBLE);
                infoCard.setVisibility(View.GONE);
                updateProgressUI(event);
                showCompletionMessage();
                updateButtonStates();
                break;

            case FAILED:
                progressCard.setVisibility(View.VISIBLE);
                infoCard.setVisibility(View.VISIBLE);
                updateProgressUI(event);
                updateButtonStates();
                break;
        }
    }

    private void updateProgressUI(ProgressEvent event) {
        // Update status text
        statusTextView.setText(event.getStatusMessage());

        // Update progress bar
        if (progressBar instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar pb = (android.widget.ProgressBar) progressBar;
            pb.setMax(100);
            pb.setProgress(event.getProgressPercentage());
        }

        // Update percentage text
        progressTextView.setText(event.getProgressPercentage() + "%");

        // Update bytes text
        if (event.getTotalBytes() > 0) {
            bytesTextView.setText(event.getFormattedBytes());
        } else {
            bytesTextView.setText(formatBytes(event.getDownloadedBytes()));
        }

        // Update pause/resume button text
        DownloadState state = event.getState();
        if (state == DownloadState.PAUSED) {
            pauseResumeButton.setEnabled(true);
            if (pauseResumeButton instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) pauseResumeButton).setText(R.string.resume);
            }
        } else if (state == DownloadState.DOWNLOADING || state == DownloadState.DECOMPRESSING) {
            pauseResumeButton.setEnabled(true);
            if (pauseResumeButton instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) pauseResumeButton).setText(R.string.pause);
            }
        } else {
            pauseResumeButton.setEnabled(false);
        }

        // Cancel button state
        cancelButton.setEnabled(state != DownloadState.COMPLETED && state != DownloadState.FAILED);
    }

    private void updateButtonStates() {
        DownloadState state = viewModel.getCurrentState();
        boolean canStart = viewModel.canStart();
        boolean canPause = viewModel.canPause();

        startButton.setEnabled(canStart);
        pauseResumeButton.setEnabled(canPause);

        // Update URL input enabled state
        urlEditText.setEnabled(state == DownloadState.IDLE || state == DownloadState.COMPLETED || state == DownloadState.FAILED);
        streamingModeSwitch.setEnabled(state == DownloadState.IDLE || state == DownloadState.COMPLETED || state == DownloadState.FAILED);
    }

    private void showCompletionMessage() {
        String message = "Download and decompression completed successfully!";
        Snackbar.make(progressCard, message, Snackbar.LENGTH_LONG)
                .setAction("View", v -> {
                    // Could open the downloaded file
                    ProgressEvent event = viewModel.getProgressEvent().getValue();
                    if (event != null) {
                        // File is in the downloads directory
                    }
                })
                .show();
    }

    private void showError(String message) {
        View rootView = findViewById(android.R.id.content);
        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                .setAction("Dismiss", v -> viewModel.clearErrorMessage())
                .show();
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "Unknown";
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check for resume capability when activity resumes
        // (e.g., after returning from another app)
        viewModel.canResume().hasActiveObservers();
    }
}
