# COMP3011 Assignment 2: Multi-threaded Video Player

Author: Duy Do (a1921156)

A JavaFX and JavaCV video player that applies a chain of film-damage effects to each frame. Part 1 added
command line selection of the effects. Part 2 replaces the single-threaded playback loop with a pipeline that runs
the effects on several threads while keeping playback in order, in time with the audio, and responsive.

Starter code (the effects, `AudioPlayer`, `InfoVideo` and the original player) was supplied by Simon Ratcliffe for
Adelaide University COMP3011.

## Building and running

```
mvn clean verify                                            # compile and run the unit tests
mvn javafx:run "-Djavafx.args=movie.mp4 -sfwyvdjmbp --number-frames"
mvn javafx:run "-Djavafx.args=--help"                       # lists every option
```

Effects are applied in the order given on the command line, and an option may be repeated.
Long, short and stacked short forms are equivalent, for example `-sfw` and `-s -f -w`.

Keys: Left and Right seek, Home restarts, Esc closes, and pause and resume are as in the starter code.

## Part 1: command line

`FrameEffectOption` is an enum holding each effect's short name, long name, description, aliases and a factory
(`Supplier<FrameProcessor>`). `CommandLineController` parses against that enum and also generates the `--help`
text from it, so adding an effect is one new enum constant. `getFrameProcessors()` returns the ordered list, which
is passed through `VideoPlayerController` to `VideoPlayerModel`.

`CommandLineControllerTest` has 17 JUnit 5 tests covering effect order, repeats, long, short and stacked forms,
error messages and exit codes, and the full help text. They run under `mvn verify`.

## Part 2: threading architecture

```
decoder thread -> effects-1 -> ... -> effects-N -> converter thread -> output queue -> JavaFX thread
                  (bounded queues between every pair of threads)
```

| Thread | Work |
| --- | --- |
| `frame-decoder` | Reads the next frame from the grabber, queues its audio, takes a private copy of the frame. |
| `effects-1` to `effects-N` | Each owns a contiguous group of the effects and applies them to every frame. |
| `frame-converter` | Turns the finished JavaCV frame into a JavaFX `Image`. |
| JavaFX thread | On each animation pulse: writes due audio, then shows the frame at the head of the output queue if its time has come. It never blocks. |

Several frames are in flight at once, each at a different stage, so the effects run on different cores at the same
time.

Design choices:

- **Stage count.** `FramePipeline.defaultStageCount` is `min(effect count, cores - 2)`. Two cores are reserved for
  the decoder, converter, JavaFX rendering and audio. Without the reserve, all cores were taken by effect stages
  and rendering was starved (see Results).
- **Contiguous groups.** Effects are split into groups of consecutive effects with nearly equal counts, so the
  command line order is preserved when the stages are chained.
- **Bounded queues.** Between stages the capacity is 1 and the output queue holds 2, because each frame is several
  megabytes. When playback is paused or the display falls behind, the queues fill and every upstream thread blocks.
  Pause therefore needs no special code, and memory use stays bounded.
- **Thread priority.** Pipeline threads run one level below normal priority so that, when every core is busy, the
  operating system favours drawing the picture.
- **Daemon threads.** A forgotten pipeline can never keep the JVM alive after the window closes.

## Why it is correct

1. **Order.** Every stage is one thread reading a first-in first-out queue, so a frame can never overtake the one
   ahead of it. Frames reach the screen in decode order and every effect sees every frame in command line order.
2. **Effects are confined to one thread.** Each `FrameProcessor` instance belongs to exactly one stage, so no two
   frames are ever inside the same instance at once. This matters because three effects keep scratch state that
   is rewritten for every frame:

   | Effect | Per-instance state rewritten on every frame |
   | --- | --- |
   | `FrameJitterer` | `sourcePixels` copy of the whole frame, and `blackRow` |
   | `FrameBleeder` | `brightMask`, `horizontalBlurMask`, `blurredMask` |
   | `FrameMottler` | cached damage masks, noise scratch and per-block scale indices |

   If two threads shared one of these instances, one frame's pixels could be copied into the other's output.
   The other effects were reviewed as well. Confinement means none of them has to be thread safe, and none of the
   effect classes was changed.
3. **Frame ownership.** One thread holds a frame at a time. It hands the frame on by queueing it and never touches
   it again. The queue hand-off also makes the previous stage's pixel writes visible to the next stage
   (happens-before). The decoder takes a private copy of each frame because the grabber may reuse its own frame
   for the next grab.
4. **Shutdown.** `FramePipeline.stop()` interrupts every thread and waits for all of them to finish before
   returning, so afterwards nothing is using the grabber, converter or effects. All blocking in the pipeline is in
   queue operations, which respond to interrupts. The end of the video is a marker (`END_OF_STREAM`) that every
   stage forwards before finishing.
5. **Seek, restart and close.** A pipeline is single use. A seek stops and joins the old one, repositions the
   grabber, resets the clock, flushes audio and builds a new pipeline. Closing playback stops the pipeline first.
6. **Errors.** A failing pipeline thread reports to a handler, which calls `Platform.runLater`. The handler checks
   that the pipeline is still the current one, so a late report from a stopped pipeline is ignored.

### Shared state and critical regions

| State | Protection |
| --- | --- |
| Frames between stages | Hand-off through bounded `ArrayBlockingQueue`s, one owner at a time. |
| Pending audio (written by the decoder, drained by the JavaFX thread) | `ConcurrentLinkedQueue`. |
| `audioOutputEnabled` (set on the JavaFX thread, read on the decoder thread) | `volatile`. |
| `AudioPlayer` | `copySamples` uses only its private buffer on the decoder thread. `write`, `flush` and `close` use only the sound line on the JavaFX thread. |
| Playback clock, pause state and `pipeline` field | JavaFX thread only. |
| Effects' static `nextInstanceIndex` seeding counter | Not thread safe, but all effects are constructed once on the main thread before any pipeline thread starts. |

## Results

Measured with the supplied trailer, 1920x800 at 23.98fps, on a laptop and then on TITAN (8 cores).

| Build | Result |
| --- | --- |
| Part 1 (single thread) | Stops keeping real time at about 11 effects. TITAN run at 11 effects: 21.913fps against the reference's 22.151fps, 2.75 cores in use. |
| Decoder on its own thread only | Held about 16 effects locally. |
| Full pipeline, laptop | Held 44 effects in real time, about four times the single-thread limit. Audio, pause and resume checks were clean. 66 effects fell behind. |
| Full pipeline, one stage per core, TITAN | Real time at 21 effects. At 41 effects it kept 23.9fps but missed 30 frames on screen, and at 81 effects it dropped too many frames and the run stopped. About 7 of 8 cores were busy, which starved rendering. |
| Full pipeline, `cores - 2` stages, TITAN | No missed frames at any load. At 41 effects it fell behind gradually (19.7fps) instead of dropping frames. TITAN's final summary: performance 258.29%, startup speed 98%, cores in use 5.25 of 8. |

Reading the console trace `Frame headroom is Xus out of 41708us spare`: it is the time between a frame being ready
and the moment it is due. Because the output queue is small it is capped at about three frame times (about 125,000us).
Values near the cap mean the pipeline is comfortably ahead. A value sliding toward zero or below means the pipeline
is about to fall behind.

## Known limitations

- Effects are grouped by count, not by cost. A heavy effect such as bleed or mottle can make one stage the
  bottleneck while another is idle. Weighting the groups by measured cost per effect would balance the stages better.
- Throughput is limited by the slowest stage, so a long run of the same expensive effect gains less than a mixed
  chain.
- A seek waits briefly while the old pipeline stops and joins, then refills the queues before the picture updates.
- Each frame in flight is several megabytes. Memory is bounded by the queue sizes but grows with the stage count.
- The pipeline is tuned for machines with at least four cores. With fewer than three cores it uses one effect stage
  and behaves much like the single-threaded player, plus the decoder and converter threads.
