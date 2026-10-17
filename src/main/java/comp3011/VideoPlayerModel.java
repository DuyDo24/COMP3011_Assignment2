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
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling
 * and frame processing. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 *
 * <h2>Threading model</h2>
 * <p>
 * Two threads cooperate through one bounded queue of finished frames:
 * </p>
 * <ul>
 * <li><b>Decoder thread</b> ("frame-decoder"): owns the FFmpeg grabber, the
 * frame converter and every {@link FrameProcessor} while it runs. It decodes,
 * queues audio, applies the effects, converts the frame to an {@link Image}
 * and puts it on {@code readyFrames}. It blocks when that queue is full, so it
 * can never run far ahead of the display. Pausing therefore needs no special
 * handling: a paused display simply stops taking frames.</li>
 * <li><b>JavaFX thread</b>: owns the playback clock, pause state, audio output
 * and the display. On every animation heart beat it writes any audio that is
 * due and shows the frame at the head of the queue if that frame is due. It
 * never blocks.</li>
 * </ul>
 *
 * <p>
 * Critical regions and how each is protected:
 * </p>
 * <ul>
 * <li>{@code readyFrames} is a blocking queue. Only the finished
 * {@link Image} crosses threads. The decoder's mutable {@link Frame} never
 * does, and the decoder never touches a frame again once it has queued it.</li>
 * <li>{@code pendingAudio} is filled by the decoder and drained by the JavaFX
 * thread, so it is a concurrent queue.</li>
 * <li>{@code audioOutputEnabled} is volatile because the JavaFX thread toggles
 * it while the decoder reads it.</li>
 * <li>Everything the decoder owns (grabber, converter, effects) is only changed
 * by the JavaFX thread after {@code stopDecoder()} has interrupted and joined
 * the decoder. Seek, stop and restart therefore cannot race with decoding,
 * and Thread.start and Thread.join provide the visibility guarantees.</li>
 * </ul>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    // How many finished frames may wait for the display. Kept small on purpose: every queued frame is a full
    // size image, and a deep queue would only add latency to seeking and pausing.
    private static final int READY_FRAME_CAPACITY = 2;

    // Queued after the last video frame so the display side knows the stream has ended.
    private static final PreparedFrame END_OF_STREAM = new PreparedFrame(null, -1, 0, 0);

    private final List<FrameProcessor> frameProcessors;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // State shared between the two threads. Every access goes through a thread-safe type.
    private final BlockingQueue<PreparedFrame> readyFrames = new ArrayBlockingQueue<>(READY_FRAME_CAPACITY);
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private volatile boolean audioOutputEnabled;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat. It now only does
    // the display side of playback: frames are decoded on the decoder thread.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    // Owned by the decoder thread while it runs. The JavaFX thread only touches these while the decoder is stopped.
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private boolean frameProcessorsInitialised;
    private Thread decodeThread;

    // Bumped every time the decoder is started or stopped, so that a late error report from a decoder that has
    // already been replaced can be recognised and ignored.
    private int decoderGeneration;

    // Owned by the JavaFX thread. The decoder only reads the ones it needs, and only after they were set up
    // before it was started.
    private File videoFile; // Path to the media that comes from the command line
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioAvailable;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.frameProcessors = List.copyOf(frameProcessors);
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // The grabber belongs to the decoder thread, so stop that thread before moving the grabber. Stopping
            // also discards every frame queued from before the seek, so no stale frame can reach the screen.
            stopDecoder();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    // Must only be called while the decoder is stopped, because it empties pendingAudio.
    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // The first frame of a session (or of the session after a seek) defines time zero for the playback clock.
    // The clock is owned by the JavaFX thread, which is why it is started here and not by the decoder.
    private void startClockAt(long mediaTimestampUs, long now) {
        firstTimestampUs = mediaTimestampUs;
        playbackStartNs = now;
        if (pauseRequested) {
            pauseStartedNs = now;
        }
    }

    // ---- Decoder thread management (JavaFX thread only) ----

    private void startDecoder() {
        int generation = ++decoderGeneration;
        decodeThread = new Thread(() -> decodeLoop(generation), "frame-decoder");
        decodeThread.setDaemon(true); // A forgotten decoder must never keep the JVM alive after the window closes.
        decodeThread.start();
    }

    // Interrupts the decoder and waits for it to finish. Afterwards nothing is using the grabber, converter,
    // effects or queues, so the caller may change any of them. Safe to call when no decoder is running.
    private void stopDecoder() {
        decoderGeneration++;
        Thread thread = decodeThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decodeThread = null;
        }
        readyFrames.clear();
    }

    // ---- Decoder thread ----

    private void decodeLoop(int generation) {
        try {
            PreparedFrame frame;
            do {
                frame = decodeNextFrame();
                readyFrames.put(frame); // Blocks while the display is behind, which is the back pressure we want.
            } while (frame != END_OF_STREAM);
        } catch (InterruptedException e) {
            // stopDecoder() asked us to finish. This is normal shutdown, so there is nothing to report.
        } catch (Exception e) {
            // Report on the JavaFX thread. Calling handlePlaybackError directly would try to join this very thread.
            // The generation check drops the report if this decoder has already been stopped or replaced.
            Platform.runLater(() -> {
                if (generation == decoderGeneration) {
                    handlePlaybackError(e);
                }
            });
        }
    }

    private PreparedFrame decodeNextFrame() throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = grabFrame();
            if (frame == null) {
                return END_OF_STREAM;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            int frameNumber = grabber.getFrameNumber();
            processFrame(frame, new InfoFrame(frameNumber, timestampUs));

            // Only this Image leaves the decoder thread. The Frame, which the grabber reuses, stays here.
            Image image = converter.convert(frame);
            return new PreparedFrame(image, frameNumber, timestampUs, System.nanoTime());
        }

        throw new InterruptedException();
    }

    // Runs on the decoder thread. AudioPlayer.copySamples must therefore not share any state with the write and
    // flush calls that the JavaFX thread makes.
    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    // ---- JavaFX thread: display side ----

    // This is called on a heart beat by the GUI thread. It must never block and must get its work done quickly so
    // control flow returns to the JavaFX event loop for user interaction and rendering. We have two jobs: (1) keep
    // audio flowing if sound is on and (2) display the next frame if it is due. Decoding happens elsewhere, so
    // all we do with the queue is look at the frame at its head.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2)
        PreparedFrame next = readyFrames.peek();
        if (next == null) {
            return; // The decoder has not produced a frame yet.
        }

        if (next == END_OF_STREAM) {
            // Every frame queued before the marker has been shown, so playback is complete.
            if (!pauseRequested) {
                finishPlayback();
            }
            return;
        }

        boolean firstFrameOfSession = firstTimestampUs == NO_SEEK_REQUEST;
        if (firstFrameOfSession) {
            startClockAt(next.mediaTimestampUs(), now);
        }

        long relativeTimestampUs = Math.max(0, next.mediaTimestampUs() - firstTimestampUs);
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

        // While paused, only the first frame after a seek is shown, so that seeking while paused still updates
        // the picture. Every other frame waits in the queue until playback resumes.
        if (targetTimeNs <= now && (!pauseRequested || firstFrameOfSession)) {
            readyFrames.poll(); // We are the only consumer, so this returns the frame we just peeked.
            displayPreparedFrame(next, relativeTimestampUs, targetTimeNs);
        }
    }

    private void displayPreparedFrame(PreparedFrame frame, long relativeTimestampUs, long targetTimeNs) {
        currentTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        // The decoder must be stopped before anything it uses is closed or cleared.
        stopDecoder();

        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    // A finished frame on its way from the decoder thread to the display. It carries only the converted image and
    // the facts the display needs, so it is safe to hand over: the decoder never touches it again once queued.
    // The playback clock is deliberately absent: it belongs to the JavaFX thread.
    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long preparedAtNs) {
    }

    // Created by the decoder thread, then only ever used by the JavaFX thread once it has been queued.
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}