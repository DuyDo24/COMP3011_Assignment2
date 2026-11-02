/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Duy Do a1921156
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The controller part of the video player model-view-controller architecture.
 * 
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {
    private final VideoPlayerModel model;
    private final VideoPlayerView view;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;

    // True only when --subtitle-frames was given. Without it there is no subtitle effect in the pipeline, so there is
    // nothing for the toggle to switch and the button stays greyed out.
    private final boolean subtitlesAvailable;

    public VideoPlayerController(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler) {
        this.view = new VideoPlayerView();
        this.videoSizeChangedHandler = videoSizeChangedHandler;

        subtitlesAvailable = frameProcessors.stream().anyMatch(processor -> processor instanceof FrameSubtitler);
        SubtitleStore.instance().setEnabled(subtitlesAvailable); // Begins on if -S was given, off if not.
        view.showSubtitlesEnabled(subtitlesAvailable);
        view.getSubtitleButton().setOnAction(_ -> toggleSubtitles());

        model = new VideoPlayerModel(
                audioEnabled,
                frameProcessors,
                this::onVideoSizeChanged,
                this::onFrameReady,
                this::onStatusChanged,
                this::onPlaybackStateChanged,
                this::onAudioOutputStateChanged);
    }

    public void installKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stopAndExit();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePause();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                backFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                forwardFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                startOver();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                toggleAudioOutput();
                event.consume();
            } else if (event.getCode() == KeyCode.S) {
                toggleSubtitles();
                event.consume();
            }
        });
    }

    public VideoPlayerView getView() {
        return view;
    }

    public void play(File file) {
        view.setPlaybackControlsDisabled(false);
        if (!subtitlesAvailable) {
            view.getSubtitleButton().setDisable(true);
        }
        model.play(file);
    }

    public void shutdown() {
        model.shutdown();
    }

    private void startOver() {
        model.startOver();
    }

    private void backFiveSeconds() {
        model.backFiveSeconds();
    }

    private void togglePause() {
        model.togglePause();
    }

    private void forwardFiveSeconds() {
        model.forwardFiveSeconds();
    }

    private void toggleAudioOutput() {
        model.toggleAudioOutput();
    }

    // Only flips the store's volatile switch and the icon. The pipeline reads the switch on its next frame, so
    // playback and audio are never touched.
    private void toggleSubtitles() {
        if (!subtitlesAvailable) {
            return;
        }
        boolean enabled = !SubtitleStore.instance().isEnabled();
        SubtitleStore.instance().setEnabled(enabled);
        view.showSubtitlesEnabled(enabled);
    }

    private void stopAndExit() {
        model.stopPlayback();
        javafx.application.Platform.exit();
    }

    private void onVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void onFrameReady(Image image) {
        view.setImage(image);
    }

    private void onStatusChanged(String status) {
        view.setStatus(status);
    }

    private void onPlaybackStateChanged(boolean playing, boolean paused) {
        if (playing && !paused) {
            view.showPauseAction();
        } else {
            view.showPlayAction();
        }
    }

    private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
        view.showAudioEnabled(audioOutputEnabled);
    }
}