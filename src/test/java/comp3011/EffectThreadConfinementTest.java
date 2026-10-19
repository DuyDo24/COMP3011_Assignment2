/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Shows why each effect instance has to stay on one thread, and that the pipeline keeps it there.
 *
 * <p>
 * {@link FrameJitterer}, {@link FrameBleeder} and {@link FrameMottler} keep scratch arrays inside the instance that
 * are rewritten on every frame. These tests run the real effects on small synthetic frames. The sharing tests feed
 * one instance from several threads at once and compare each output with the output from feeding the frames in one
 * at a time. The pipeline test runs the three effects through {@link FramePipeline} and requires the output to match
 * the sequential result exactly.
 * </p>
 *
 * <p>
 * The sharing tests demonstrate a hazard, and a data race is by nature timing dependent: if a machine happens to
 * never interleave the threads, the test is skipped and not failed. Output that differs is evidence of the race, but
 * identical output on one run is not proof that sharing is safe.
 * </p>
 */
class EffectThreadConfinementTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int CHANNELS = 3;
    private static final int STRIDE = WIDTH * CHANNELS;
    private static final long FRAME_US = 41_708;
    private static final int CANDIDATE_FRAMES = 60;
    private static final int MAX_FRAMES_USED = 24;
    private static final int ROUNDS = 4;

    // ---- Sharing one instance across threads corrupts output ----

    @Test
    void sharingAJittererBetweenThreadsCorruptsFrames() throws Exception {
        assertSharingIsVisible(new FrameJitterer());
    }

    @Test
    void sharingABleederBetweenThreadsCorruptsFrames() throws Exception {
        assertSharingIsVisible(new FrameBleeder());
    }

    @Test
    void sharingAMottlerBetweenThreadsCorruptsFrames() throws Exception {
        assertSharingIsVisible(new FrameMottler());
    }

    private static void assertSharingIsVisible(FrameProcessor effect) throws Exception {
        effect.initialise(videoInfo());
        int cores = Runtime.getRuntime().availableProcessors();
        assumeTrue(cores >= 2, "needs at least two cores for two threads to really run at once");

        // Only frames the effect really changes can show corruption, and the effect is the same function of the
        // frame number each time, so the one-at-a-time result is the reference.
        List<Integer> frameNumbers = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < CANDIDATE_FRAMES && frameNumbers.size() < MAX_FRAMES_USED; i++) {
            int frameNumber = i * 7;
            Frame frame = makeFrame(frameNumber);
            int before = fingerprint(frame);
            effect.process(frame, new InfoFrame(frameNumber, frameNumber * FRAME_US));
            int after = fingerprint(frame);
            if (after != before) {
                frameNumbers.add(frameNumber);
                expected.add(after);
            }
        }
        assertTrue(frameNumbers.size() >= 4, "the effect should change enough of the test frames to be tested");

        int threadCount = Math.max(2, Math.min(4, cores));
        AtomicInteger wrong = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        for (int i = 0; i < frameNumbers.size(); i++) {
                            int frameNumber = frameNumbers.get(i);
                            total.incrementAndGet();
                            try {
                                Frame frame = makeFrame(frameNumber);
                                effect.process(frame, new InfoFrame(frameNumber, frameNumber * FRAME_US));
                                if (fingerprint(frame) != expected.get(i)) {
                                    wrong.incrementAndGet();
                                }
                            } catch (Throwable crash) {
                                wrong.incrementAndGet(); // A crash from shared scratch state is also corruption.
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "sharing-" + t);
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(60));
        }

        System.out.printf("%s shared by %d threads: %d of %d frames came out different from the sequential result%n",
                effect.getClass().getSimpleName(), threadCount, wrong.get(), total.get());
        assumeTrue(wrong.get() > 0, "no interference was observed on this run, so the hazard was not demonstrated");
        assertTrue(wrong.get() > 0);
    }

    // ---- The pipeline keeps each instance on one thread and gives the sequential result ----

    @Test
    void theSameEffectsThroughThePipelineGiveExactlyTheSequentialResult() throws Exception {
        FrameJitterer jitterer = new FrameJitterer();
        FrameBleeder bleeder = new FrameBleeder();
        FrameMottler mottler = new FrameMottler();
        InfoVideo info = videoInfo();
        jitterer.initialise(info);
        bleeder.initialise(info);
        mottler.initialise(info);
        List<FrameProcessor> chain = List.of(jitterer, bleeder, mottler);

        int count = 30;
        int[] expected = new int[count];
        for (int n = 0; n < count; n++) {
            Frame frame = makeFrame(n);
            InfoFrame frameInfo = new InfoFrame(n, n * FRAME_US);
            for (FrameProcessor effect : chain) {
                effect.process(frame, frameInfo);
            }
            expected[n] = fingerprint(frame);
        }

        for (int stages : new int[] { 1, 2, 3 }) {
            List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger next = new AtomicInteger();
            FramePipeline.FrameSource source = () -> {
                int n = next.getAndIncrement();
                return n >= count ? PipelineFrame.END_OF_STREAM : PipelineFrame.decoded(makeFrame(n), n, n * FRAME_US);
            };
            AtomicInteger errors = new AtomicInteger();
            FramePipeline pipeline = new FramePipeline(source, chain, frame -> {
                seen.add(fingerprint(frame));
                return null;
            }, stages);
            pipeline.start(error -> errors.incrementAndGet());

            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                boolean ended = false;
                while (!ended && System.nanoTime() < deadline) {
                    PipelineFrame frame = pipeline.pollReady();
                    if (frame == null) {
                        Thread.sleep(1);
                    } else if (frame.isEndOfStream()) {
                        ended = true;
                    }
                }
                assertTrue(ended, "the pipeline never finished with " + stages + " stages");
            } finally {
                pipeline.stop();
            }

            assertEquals(0, errors.get(), "pipeline error with " + stages + " stages");
            assertEquals(count, seen.size(), "frame count with " + stages + " stages");
            for (int n = 0; n < count; n++) {
                assertEquals(expected[n], seen.get(n), "frame " + n + " differs with " + stages + " stages");
            }
        }
    }

    // ---- Helpers ----

    private static InfoVideo videoInfo() {
        return new InfoVideo(
                "confinement-test.mp4",
                1000,
                WIDTH,
                HEIGHT,
                Frame.DEPTH_UBYTE,
                CHANNELS,
                STRIDE,
                24.0,
                24,
                (int) FRAME_US,
                3);
    }

    // A 320x240 BGR frame whose pixels are a fixed function of the frame number.
    private static Frame makeFrame(int frameNumber) {
        byte[] pixels = new byte[STRIDE * HEIGHT];
        new Random(7_000L + frameNumber).nextBytes(pixels);

        Frame frame = new Frame();
        frame.imageWidth = WIDTH;
        frame.imageHeight = HEIGHT;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = CHANNELS;
        frame.imageStride = STRIDE;
        frame.image = new Buffer[] { ByteBuffer.wrap(pixels) };
        return frame;
    }

    private static int fingerprint(Frame frame) {
        return Arrays.hashCode(((ByteBuffer) frame.image[0]).array());
    }
}
