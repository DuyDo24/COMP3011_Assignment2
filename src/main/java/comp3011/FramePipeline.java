/*
 * Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Duy Do a1921156
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Runs the frame effects on several threads at once while keeping playback in order.
 *
 * <p>
 * Frames flow down a chain of threads, each handing its result to the next one
 * through a small bounded queue:
 * </p>
 *
 * <pre>
 * decoder -&gt; effect stage 1 -&gt; ... -&gt; effect stage N -&gt; converter -&gt; output queue -&gt; JavaFX thread
 * </pre>
 *
 * <p>
 * The ordered list of effects is split into contiguous groups, one group per
 * effect stage. Several frames are therefore in flight at once, each at a
 * different stage, so the effects run in parallel on different cores.
 * </p>
 *
 * <h2>Why this is correct</h2>
 * <ul>
 * <li><b>Order:</b> every stage is a single thread reading a first-in
 * first-out queue, so a frame can never overtake the one ahead of it. Effects
 * are applied in command-line order because the groups are contiguous and the
 * stages are chained in that order.</li>
 * <li><b>No two threads inside one effect:</b> each {@link FrameProcessor}
 * belongs to exactly one stage. Several effects (jitter, bleed, mottle) keep
 * per-instance scratch buffers that are overwritten on every frame, so letting
 * two frames through the same instance at once would corrupt both. Confining
 * each instance to one thread removes that hazard without changing any
 * effect.</li>
 * <li><b>Frame ownership:</b> a frame is held by one thread at a time. A
 * thread passes it on by queueing it and never touches it again. The queue
 * hand-off also makes the previous stage's pixel writes visible to the
 * next.</li>
 * <li><b>Bounded memory and back pressure:</b> the queues are small because
 * every frame is several megabytes. When the display falls behind, or playback
 * is paused, the queues fill and each upstream thread simply blocks, so pause
 * needs no special code.</li>
 * <li><b>Shutdown:</b> {@link #stop()} interrupts every thread and waits for
 * all of them. Each thread only blocks in queue operations, which respond to
 * interrupts. The end of the video is signalled by
 * {@link PipelineFrame#END_OF_STREAM}, which every stage forwards before it
 * finishes. A pipeline is single use: seeking builds a new one.</li>
 * </ul>
 */
public final class FramePipeline {
    /**
     * Produces the next decoded frame, or {@link PipelineFrame#END_OF_STREAM} when
     * there are no more. Only ever called on the pipeline's decoder thread.
     */
    @FunctionalInterface
    public interface FrameSource {
        PipelineFrame next() throws Exception;
    }

    @FunctionalInterface
    private interface FrameStep {
        PipelineFrame apply(PipelineFrame frame) throws Exception;
    }

    // Between stages: tiny, because each frame is several megabytes and a stage that is blocked while handing its
    // finished frame on already holds one more.
    private static final int STAGE_QUEUE_CAPACITY = 1;

    // Final queue: just enough slack to absorb jitter between the pipeline and the display's heart beat.
    private static final int OUTPUT_QUEUE_CAPACITY = 2;

    private final List<Thread> threads = new ArrayList<>();
    private final List<BlockingQueue<PipelineFrame>> stageQueues = new ArrayList<>();
    private final BlockingQueue<PipelineFrame> output = new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
    private volatile Consumer<Exception> errorHandler;

    /**
     * Builds the threads and queues but does not start them.
     *
     * @param source     supplies decoded frames, on the decoder thread
     * @param processors the effects, in the order they must be applied
     * @param converter  turns a finished JavaCV frame into a JavaFX image
     * @param stageCount how many effect stages to split the effects across
     */
    public FramePipeline(
            FrameSource source,
            List<FrameProcessor> processors,
            Function<Frame, Image> converter,
            int stageCount) {
        BlockingQueue<PipelineFrame> firstQueue = newStageQueue();
        addThread("frame-decoder", () -> runSource(source, firstQueue));

        BlockingQueue<PipelineFrame> upstream = firstQueue;
        List<List<FrameProcessor>> groups = partition(processors, stageCount);
        for (int i = 0; i < groups.size(); i++) {
            List<FrameProcessor> group = groups.get(i);
            BlockingQueue<PipelineFrame> in = upstream;
            BlockingQueue<PipelineFrame> out = newStageQueue();
            addThread("effects-" + (i + 1), () -> runStage(in, out, frame -> applyEffects(group, frame)));
            upstream = out;
        }

        BlockingQueue<PipelineFrame> converterInput = upstream;
        addThread("frame-converter", () -> runStage(converterInput, output, frame -> convert(converter, frame)));
    }

    /**
     * One stage per effect gives the most parallelism, but more stages than cores only adds hand-off cost, so cap
     * the number of stages at the number of cores.
     */
    public static int defaultStageCount(int processorCount) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.min(processorCount, cores);
    }

    /**
     * Starts every thread. The handler is called on whichever pipeline thread fails first, so it must be safe to
     * call from any thread.
     */
    public void start(Consumer<Exception> errorHandler) {
        this.errorHandler = errorHandler;
        threads.forEach(Thread::start);
    }

    /** The finished frame at the head of the output, or null. Never blocks. For the display thread only. */
    public PipelineFrame peekReady() {
        return output.peek();
    }

    /** Removes the frame returned by {@link #peekReady()}. For the display thread only. */
    public PipelineFrame pollReady() {
        return output.poll();
    }

    /**
     * Interrupts every thread, waits for all of them to finish, and discards any frames still queued. When this
     * returns nothing is running, so the caller may safely change or close the resources the pipeline was using.
     */
    public void stop() {
        threads.forEach(Thread::interrupt);

        boolean interrupted = false;
        for (Thread thread : threads) {
            boolean finished = false;
            while (!finished) {
                try {
                    thread.join();
                    finished = true;
                } catch (InterruptedException e) {
                    // We must not return until the thread really has finished, so keep waiting
                    // and hand the interrupt back to our caller afterwards.
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        stageQueues.forEach(BlockingQueue::clear);
        output.clear();
    }

    // ---- Construction helpers ----

    private BlockingQueue<PipelineFrame> newStageQueue() {
        BlockingQueue<PipelineFrame> queue = new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY);
        stageQueues.add(queue);
        return queue;
    }

    private void addThread(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true); // A forgotten pipeline must never keep the JVM alive after the window closes.
        threads.add(thread);
    }

    // Splits the effects into at most stageCount contiguous groups of nearly equal size. Contiguous, so the
    // command-line order of the effects is preserved when the groups are chained together. It always uses every
    // effect: a non-empty list gets at least one group.
    private static List<List<FrameProcessor>> partition(List<FrameProcessor> processors, int stageCount) {
        if (processors.isEmpty()) {
            return List.of();
        }

        int groupCount = Math.max(1, Math.min(stageCount, processors.size()));
        List<List<FrameProcessor>> groups = new ArrayList<>();
        for (int group = 0; group < groupCount; group++) {
            int from = group * processors.size() / groupCount;
            int to = (group + 1) * processors.size() / groupCount;
            groups.add(List.copyOf(processors.subList(from, to)));
        }
        return groups;
    }

    // ---- The work each thread does ----

    private static PipelineFrame applyEffects(List<FrameProcessor> group, PipelineFrame frame) throws Exception {
        InfoFrame info = new InfoFrame(frame.frameNumber(), frame.mediaTimestampUs());
        for (FrameProcessor processor : group) {
            processor.process(frame.frame(), info);
        }
        return frame;
    }

    private static PipelineFrame convert(Function<Frame, Image> converter, PipelineFrame frame) {
        Image image = converter.apply(frame.frame());
        return frame.converted(image, System.nanoTime());
    }

    private void runSource(FrameSource source, BlockingQueue<PipelineFrame> out) {
        try {
            boolean last;
            do {
                PipelineFrame frame = source.next();
                last = frame.isEndOfStream();
                out.put(frame); // Blocks while the next stage is busy. That is the back pressure.
            } while (!last);
        } catch (InterruptedException e) {
            // stop() asked us to finish. This is normal shutdown, so there is nothing to report.
        } catch (Exception e) {
            errorHandler.accept(e);
        }
    }

    private void runStage(BlockingQueue<PipelineFrame> in, BlockingQueue<PipelineFrame> out, FrameStep step) {
        try {
            boolean last;
            do {
                PipelineFrame frame = in.take();
                last = frame.isEndOfStream();
                out.put(last ? frame : step.apply(frame)); // Once queued, this thread never touches the frame again.
            } while (!last);
        } catch (InterruptedException e) {
            // stop() asked us to finish. This is normal shutdown, so there is nothing to report.
        } catch (Exception e) {
            errorHandler.accept(e);
        }
    }
}
