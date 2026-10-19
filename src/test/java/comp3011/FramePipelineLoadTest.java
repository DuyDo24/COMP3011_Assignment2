/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * A repeatable load comparison of the old design against the pipeline, run by {@code mvn verify}.
 *
 * <p>
 * The "old design" is every effect applied one after another on a single thread, which is how the single-threaded
 * player worked. The pipeline is {@link FramePipeline} with the default stage count. Both run the same chain of
 * stand-in effects that each burn a fixed amount of CPU, on synthetic frames, so the test needs no video, window or
 * JavaFX toolkit and the result depends on the machine's cores and not on the effects' pictures.
 * </p>
 *
 * <p>
 * What this does and does not show: it shows that the pipeline structure turns spare cores into throughput for
 * CPU-bound work and that one effect stage adds little overhead. It does not measure real decoding, the real effects,
 * conversion or rendering. Those are covered by the real-time runs described in the README. The speed-up check only
 * runs where there are enough cores for the pipeline to use; elsewhere the test prints the numbers and is skipped.
 * </p>
 */
class FramePipelineLoadTest {
    private static final int EFFECTS = 8;
    private static final int FRAMES = 40;
    private static final int RUNS = 3;
    private static final double WORK_MS_PER_EFFECT = 2.0;
    private static final double REQUIRED_SPEEDUP = 1.5;
    private static final double ALLOWED_ONE_STAGE_SLOWDOWN = 1.5;
    // With cores - 2 effect stages, six cores gives four stages and a speed-up of about three, so the required
    // 1.5x leaves a wide margin for a noisy machine. Fewer cores have too little margin to assert on.
    private static final int MIN_CORES_FOR_SPEEDUP_CHECK = 6;
    private static final long FRAME_US = 41_708;

    // Written to so the compiler cannot decide the burning loop has no effect.
    private static volatile long blackhole;

    @FunctionalInterface
    private interface TimedRun {
        long runNanos() throws Exception;
    }

    @Test
    void pipelineBeatsSequentialPlaybackWhenCoresAreAvailable() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        long iterations = calibrate();
        List<FrameProcessor> effects = new ArrayList<>();
        for (int i = 0; i < EFFECTS; i++) {
            effects.add(new BusyEffect(iterations));
        }
        int stages = FramePipeline.defaultStageCount(EFFECTS);

        // Warm up both paths so the first timed run is not paying for class loading and compilation.
        runSequential(effects);
        runPipeline(effects, stages);

        long sequentialNs = best(() -> runSequential(effects));
        long oneStageNs = best(() -> runPipeline(effects, 1));
        long pipelineNs = best(() -> runPipeline(effects, stages));

        double speedup = (double) sequentialNs / pipelineNs;
        System.out.printf("Load test: %d effects of about %.1f ms each on %d frames, %d cores%n",
                EFFECTS, WORK_MS_PER_EFFECT, FRAMES, cores);
        report("sequential (old design)", sequentialNs);
        report("pipeline, 1 effect stage", oneStageNs);
        report("pipeline, " + stages + (stages == 1 ? " effect stage" : " effect stages") + " (default)", pipelineNs);
        System.out.printf("  speed-up over sequential: %.2fx%n", speedup);

        // One stage does the same work as the old design plus two hand-offs per frame, so it must stay close to it.
        assertTrue(oneStageNs <= sequentialNs * ALLOWED_ONE_STAGE_SLOWDOWN,
                "a one-stage pipeline should cost about the same as sequential playback");

        assumeTrue(cores >= MIN_CORES_FOR_SPEEDUP_CHECK,
                "needs at least " + MIN_CORES_FOR_SPEEDUP_CHECK + " cores to check the speed-up, found " + cores);
        assertTrue(speedup >= REQUIRED_SPEEDUP,
                String.format("expected at least %.1fx speed-up on %d cores, measured %.2fx",
                        REQUIRED_SPEEDUP, cores, speedup));
    }

    // ---- Runs ----

    private static long runSequential(List<FrameProcessor> effects) throws Exception {
        long start = System.nanoTime();
        for (int n = 0; n < FRAMES; n++) {
            Frame frame = new Frame();
            InfoFrame info = new InfoFrame(n, n * FRAME_US);
            for (FrameProcessor effect : effects) {
                effect.process(frame, info);
            }
        }
        return System.nanoTime() - start;
    }

    private static long runPipeline(List<FrameProcessor> effects, int stages) throws Exception {
        AtomicInteger next = new AtomicInteger();
        FramePipeline.FrameSource source = () -> {
            int n = next.getAndIncrement();
            return n >= FRAMES ? PipelineFrame.END_OF_STREAM : PipelineFrame.decoded(new Frame(), n, n * FRAME_US);
        };
        AtomicInteger failures = new AtomicInteger();
        FramePipeline pipeline = new FramePipeline(source, effects, frame -> null, stages);

        long start = System.nanoTime();
        pipeline.start(error -> failures.incrementAndGet());
        try {
            int received = 0;
            long deadline = start + TimeUnit.SECONDS.toNanos(60);
            boolean ended = false;
            while (!ended && System.nanoTime() < deadline) {
                PipelineFrame frame = pipeline.pollReady();
                if (frame == null) {
                    Thread.onSpinWait();
                } else if (frame.isEndOfStream()) {
                    ended = true;
                } else {
                    assertEquals(received, frame.frameNumber(), "frames must arrive in order");
                    received++;
                }
            }
            long elapsed = System.nanoTime() - start;
            assertTrue(ended, "the pipeline never reached the end of the stream");
            assertEquals(FRAMES, received);
            assertEquals(0, failures.get(), "the pipeline reported an error");
            return elapsed;
        } finally {
            pipeline.stop();
        }
    }

    private static long best(TimedRun run) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < RUNS; i++) {
            best = Math.min(best, run.runNanos());
        }
        if (best == Long.MAX_VALUE) {
            fail("no timed runs");
        }
        return best;
    }

    private static void report(String label, long nanos) {
        double millis = nanos / 1e6;
        System.out.printf("  %-28s %7.0f ms  %6.1f frames/s%n", label, millis, FRAMES / (nanos / 1e9));
    }

    // ---- Stand-in work ----

    // Picks an iteration count for burn() that takes about WORK_MS_PER_EFFECT on this machine.
    private static long calibrate() {
        long sink = 0;
        for (int i = 0; i < 5; i++) {
            sink ^= burn(2_000_000);
        }
        long iterations = 2_000_000;
        long start = System.nanoTime();
        sink ^= burn(iterations);
        long elapsed = Math.max(1, System.nanoTime() - start);
        blackhole = sink;
        return Math.max(1, (long) (iterations * (WORK_MS_PER_EFFECT * 1e6) / elapsed));
    }

    private static long burn(long iterations) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    /** CPU-bound stand-in for a real effect. It touches no shared state, so it is safe on any thread. */
    private static final class BusyEffect extends FrameProcessor {
        private final long iterations;

        BusyEffect(long iterations) {
            this.iterations = iterations;
        }

        @Override
        public void process(Frame frame, InfoFrame info) {
            blackhole = burn(iterations);
        }
    }
}
