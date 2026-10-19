/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javafx.scene.image.Image;

/**
 * Tests the behaviour of {@link FramePipeline} with synthetic frames and stand-in effects. No video file, window or
 * JavaFX toolkit is needed. The stand-in effects only record what happened to each frame, so these tests check the
 * threading rules (order, thread confinement, back pressure, shutdown, error reporting) and not the pictures.
 */
class FramePipelineTest {
    private static final long TIMEOUT_MS = 10_000;
    private static final long FRAME_US = 41_708;
    private static final Function<Frame, Image> NO_IMAGE = frame -> null;

    private final List<FramePipeline> started = new ArrayList<>();
    private final AtomicReference<Exception> unexpectedError = new AtomicReference<>();

    @AfterEach
    void stopEveryPipelineAndCheckForErrors() {
        for (FramePipeline pipeline : started) {
            pipeline.stop();
        }
        started.clear();
        assertNull(unexpectedError.get(), "A pipeline thread reported an error nobody expected");
    }

    // ---- Order and completion ----

    @Test
    void framesStayInOrderAndEffectsRunInCommandLineOrder() throws Exception {
        for (int stages : new int[] { 1, 2, 3, 4, 10 }) {
            ConcurrentHashMap<Integer, StringBuffer> log = new ConcurrentHashMap<>();
            List<FrameProcessor> effects = List.of(
                    new TagEffect("A", log), new TagEffect("B", log), new TagEffect("C", log), new TagEffect("D", log));

            FramePipeline pipeline = start(finiteSource(0, 200), effects, stages);
            List<PipelineFrame> frames = drainToEnd(pipeline);
            pipeline.stop();

            assertEquals(200, frames.size(), "frame count with " + stages + " stages");
            for (int i = 0; i < frames.size(); i++) {
                assertEquals(i, frames.get(i).frameNumber(), "frame order with " + stages + " stages");
                assertEquals(i * FRAME_US, frames.get(i).mediaTimestampUs());
                assertEquals("ABCD", log.get(i).toString(), "effect order on frame " + i);
            }
        }
    }

    @Test
    void endOfStreamArrivesOnceAndNothingFollowsIt() throws Exception {
        FramePipeline pipeline = start(finiteSource(0, 20), List.of(new TagEffect("A", new ConcurrentHashMap<>())), 1);
        drainToEnd(pipeline);

        Thread.sleep(50);
        assertNull(pipeline.pollReady(), "nothing may arrive after the end of the stream");
    }

    @Test
    void worksWithNoEffects() throws Exception {
        FramePipeline pipeline = start(finiteSource(0, 50), List.of(), FramePipeline.defaultStageCount(0));
        List<PipelineFrame> frames = drainToEnd(pipeline);

        assertEquals(50, frames.size());
        assertEquals(49, frames.get(49).frameNumber());
    }

    @Test
    void aNewPipelineStartsWhereTheSourceSaysAfterAnOldOneIsStopped() throws Exception {
        // This is what a seek does: stop the old pipeline, then build a new one from the new position.
        AtomicInteger produced = new AtomicInteger();
        FramePipeline first = start(endlessSource(produced), List.of(new TagEffect("A", new ConcurrentHashMap<>())), 1);
        awaitFrame(first);
        first.stop();

        FramePipeline second = start(finiteSource(100, 20), List.of(new TagEffect("A", new ConcurrentHashMap<>())), 1);
        List<PipelineFrame> frames = drainToEnd(second);

        assertEquals(20, frames.size());
        assertEquals(100, frames.get(0).frameNumber());
        assertEquals(119, frames.get(19).frameNumber());
    }

    // ---- Threading rules ----

    @Test
    void defaultStageCountLeavesCoresFreeForRendering() {
        int cores = Runtime.getRuntime().availableProcessors();
        int expectedCap = Math.max(1, cores - FramePipeline.RESERVED_CORES);

        assertEquals(0, FramePipeline.defaultStageCount(0));
        assertEquals(1, FramePipeline.defaultStageCount(1));
        assertEquals(expectedCap, FramePipeline.defaultStageCount(1000));
        assertTrue(FramePipeline.defaultStageCount(1000) < cores || cores == 1,
                "at least one core must be left for decoding and rendering");
    }

    @Test
    void everyStageRunsOnItsOwnThreadAndEachEffectOnlyOnOne() throws Exception {
        ConcurrentHashMap<Integer, StringBuffer> log = new ConcurrentHashMap<>();
        List<TagEffect> effects = List.of(
                new TagEffect("A", log), new TagEffect("B", log), new TagEffect("C", log), new TagEffect("D", log));
        Set<String> decoderThreads = ConcurrentHashMap.newKeySet();
        Set<String> converterThreads = ConcurrentHashMap.newKeySet();

        FramePipeline.FrameSource inner = finiteSource(0, 100);
        FramePipeline.FrameSource source = () -> {
            decoderThreads.add(Thread.currentThread().getName());
            return inner.next();
        };
        Function<Frame, Image> converter = frame -> {
            converterThreads.add(Thread.currentThread().getName());
            return null;
        };

        FramePipeline pipeline = start(source, new ArrayList<FrameProcessor>(effects), converter, 4, unexpectedError::set);
        drainToEnd(pipeline);
        pipeline.stop();

        assertEquals(Set.of("frame-decoder"), decoderThreads);
        assertEquals(Set.of("frame-converter"), converterThreads);

        Set<String> allEffectThreads = ConcurrentHashMap.newKeySet();
        for (TagEffect effect : effects) {
            assertEquals(1, effect.threadNames.size(), "an effect instance must only ever run on one thread");
            String name = effect.threadNames.iterator().next();
            assertTrue(name.startsWith("effects-"), name);
            allEffectThreads.add(name);
        }
        assertEquals(4, allEffectThreads.size(), "four stages means four different effect threads");
        assertFalse(allEffectThreads.contains(Thread.currentThread().getName()));
    }

    @Test
    void backPressureBoundsTheFramesInFlightAndWorkResumesWhenTheDisplayCatchesUp() throws Exception {
        int stages = 2;
        AtomicInteger produced = new AtomicInteger();
        FramePipeline pipeline = start(endlessSource(produced),
                List.of(new TagEffect("A", new ConcurrentHashMap<>()), new TagEffect("B", new ConcurrentHashMap<>())),
                stages);

        // Nobody is taking frames from the output, as when playback is paused or the display is behind.
        int settled = settledCount(produced);

        // Frames in flight: the decoder, each stage and the converter hold one each, there is one queue slot before
        // every stage and one before the converter, and the output holds two.
        int bound = 2 * stages + 5;
        assertTrue(settled >= 1, "the pipeline should have started producing");
        assertTrue(settled <= bound, "decoded " + settled + " frames with nobody consuming, bound is " + bound);

        // Taking one frame must let exactly the blocked threads move again.
        awaitFrame(pipeline);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (produced.get() <= settled && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(produced.get() > settled, "production should resume once the output has room");
    }

    // ---- Shutdown ----

    @Test
    void stopFinishesPromptlyEvenWhenEveryThreadIsBlockedAndLeavesNoThreadsRunning() throws Exception {
        AtomicInteger produced = new AtomicInteger();
        FramePipeline pipeline = start(endlessSource(produced), fourEffects(), 4);
        settledCount(produced); // Every thread is now blocked on a full queue.

        Thread stopper = new Thread(pipeline::stop);
        stopper.start();
        stopper.join(5_000);

        assertFalse(stopper.isAlive(), "stop() must not hang when threads are blocked");
        assertEquals(0, livePipelineThreads());
    }

    @Test
    void stopCanBeCalledTwice() throws Exception {
        FramePipeline pipeline = start(finiteSource(0, 10), fourEffects(), 2);
        pipeline.stop();
        pipeline.stop();

        assertEquals(0, livePipelineThreads());
    }

    // ---- Errors ----

    @Test
    void aFailingEffectIsReportedAndThePipelineCanStillBeStopped() throws Exception {
        AtomicReference<Exception> reported = new AtomicReference<>();
        CountDownLatch gotError = new CountDownLatch(1);
        Consumer<Exception> handler = error -> {
            reported.set(error);
            gotError.countDown();
        };

        List<FrameProcessor> effects = List.of(new TagEffect("A", new ConcurrentHashMap<>()), new ThrowingEffect(3));
        FramePipeline pipeline = start(finiteSource(0, 1000), effects, NO_IMAGE, 2, handler);
        awaitWhileDraining(pipeline, gotError);

        assertEquals(IllegalStateException.class, reported.get().getClass());
        assertEquals("boom", reported.get().getMessage());

        Thread stopper = new Thread(pipeline::stop);
        stopper.start();
        stopper.join(5_000);
        assertFalse(stopper.isAlive(), "stop() must work after a thread has failed");
        assertEquals(0, livePipelineThreads());
    }

    @Test
    void aFailingSourceIsReported() throws Exception {
        AtomicReference<Exception> reported = new AtomicReference<>();
        CountDownLatch gotError = new CountDownLatch(1);
        Consumer<Exception> handler = error -> {
            reported.set(error);
            gotError.countDown();
        };

        AtomicInteger calls = new AtomicInteger();
        FramePipeline.FrameSource source = () -> {
            int n = calls.getAndIncrement();
            if (n == 3) {
                throw new IOException("disk gone");
            }
            return frame(n);
        };

        FramePipeline pipeline = start(source, fourEffects(), NO_IMAGE, 2, handler);
        awaitWhileDraining(pipeline, gotError);

        assertEquals(IOException.class, reported.get().getClass());
        assertEquals("disk gone", reported.get().getMessage());
    }

    // ---- Helpers ----

    private FramePipeline start(FramePipeline.FrameSource source, List<FrameProcessor> effects, int stages) {
        return start(source, effects, NO_IMAGE, stages, unexpectedError::set);
    }

    private FramePipeline start(
            FramePipeline.FrameSource source,
            List<FrameProcessor> effects,
            Function<Frame, Image> converter,
            int stages,
            Consumer<Exception> onError) {
        FramePipeline pipeline = new FramePipeline(source, effects, converter, stages);
        started.add(pipeline);
        pipeline.start(onError);
        return pipeline;
    }

    private static List<FrameProcessor> fourEffects() {
        ConcurrentHashMap<Integer, StringBuffer> log = new ConcurrentHashMap<>();
        return List.of(new TagEffect("A", log), new TagEffect("B", log), new TagEffect("C", log), new TagEffect("D", log));
    }

    private static PipelineFrame frame(int n) {
        return PipelineFrame.decoded(new Frame(), n, n * FRAME_US);
    }

    private static FramePipeline.FrameSource finiteSource(int firstFrame, int count) {
        AtomicInteger next = new AtomicInteger(firstFrame);
        int end = firstFrame + count;
        return () -> {
            int n = next.getAndIncrement();
            return n >= end ? PipelineFrame.END_OF_STREAM : frame(n);
        };
    }

    private static FramePipeline.FrameSource endlessSource(AtomicInteger produced) {
        return () -> frame(produced.getAndIncrement());
    }

    private static List<PipelineFrame> drainToEnd(FramePipeline pipeline) throws InterruptedException {
        List<PipelineFrame> frames = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            PipelineFrame next = pipeline.pollReady();
            if (next == null) {
                Thread.sleep(1);
            } else if (next.isEndOfStream()) {
                return frames;
            } else {
                frames.add(next);
            }
        }
        fail("Timed out waiting for the end of the stream, saw " + frames.size() + " frames");
        return frames;
    }

    private static void awaitFrame(FramePipeline pipeline) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (pipeline.pollReady() != null) {
                return;
            }
            Thread.sleep(1);
        }
        fail("Timed out waiting for a frame");
    }

    private static void awaitWhileDraining(FramePipeline pipeline, CountDownLatch latch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (latch.await(2, TimeUnit.MILLISECONDS)) {
                return;
            }
            pipeline.pollReady(); // Keep frames moving so the failing frame is reached.
        }
        fail("Timed out waiting for the error report");
    }

    // Waits until the counter has stopped changing for a while, which means every thread has blocked.
    private static int settledCount(AtomicInteger counter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        int last = -1;
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int current = counter.get();
            if (current != last) {
                last = current;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(300)) {
                return current;
            }
            Thread.sleep(10);
        }
        fail("The pipeline never settled");
        return last;
    }

    private static long livePipelineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.equals("frame-decoder") || name.equals("frame-converter") || name.startsWith("effects-"))
                .count();
    }

    // ---- Stand-in effects ----

    /** Records which effects ran on each frame, in order, and which threads it ran on. */
    private static final class TagEffect extends FrameProcessor {
        private final String tag;
        private final ConcurrentHashMap<Integer, StringBuffer> log;
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();

        TagEffect(String tag, ConcurrentHashMap<Integer, StringBuffer> log) {
            this.tag = tag;
            this.log = log;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            threadNames.add(Thread.currentThread().getName());
            log.computeIfAbsent(info.frameNumber(), key -> new StringBuffer()).append(tag);
        }
    }

    private static final class ThrowingEffect extends FrameProcessor {
        private final int failOnFrame;

        ThrowingEffect(int failOnFrame) {
            this.failOnFrame = failOnFrame;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            if (info.frameNumber() == failOnFrame) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
