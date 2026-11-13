/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Duy Do a1921156
 *
 * Drafted with help from Claude (Anthropic), then read and reviewed by the author.
 */
package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Fetches subtitles for a media file in the background and puts them in the {@link SubtitleStore}.
 *
 * <p>
 * The media's audio is cut into fixed 20 second chunks (chunk n covers n * 20 s up to (n + 1) * 20 s). Each chunk is
 * decoded by this class's own audio-only grabber, so the player's decoder is never touched and no video is decoded
 * while seeking. The audio is converted to 16 kHz mono 16-bit WAV and posted to the web server. That is
 * 16000 * 2 * 1 = 32000 bytes per second, so a chunk is 640000 bytes plus a 44 byte header, well under Whisper's
 * 25 MB limit.
 * </p>
 *
 * <p>
 * One thread does all the work, at slightly lower priority than the player. It looks at the playhead every 250 ms and
 * fetches the first chunk that is missing from the one being played and the next two, so seeking simply moves the
 * playhead and the fetcher follows. A chunk that fails for any reason (server down, bad key, media file gone, even an
 * Error such as running out of memory) is retried after 5 seconds, and the player never waits for any of it.
 * </p>
 *
 * <p>
 * The media duration is passed in by the player, which already knows it from its own decoder. The audio-only decoder
 * does not report a length (it has no video stream), so without this the fetcher would not know where the media ends
 * and would open a decoder for chunks past the end.
 * </p>
 *
 * <p>
 * Before a chunk is sent to the server, its audio is compared with everything the {@link SubtitleCache} remembers
 * (see there for how). If the same sound was already transcribed, whether in an earlier play, under another file name,
 * or as part of a longer media, the remembered answer is used and the server is not asked at all.
 * </p>
 */
public final class SubtitleFetcher implements AutoCloseable {
    private static final String ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final String USAGE_ENDPOINT = "http://localhost:8080/api/v1/global/usage";
    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final long CHUNK_US = 20_000_000L;
    private static final int LOOKAHEAD_CHUNKS = 2;
    private static final int MIN_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 2; // Half a second of audio.
    private static final long RETRY_DELAY_MS = 5_000L;
    private static final long POLL_MS = 250L;

    // No real file has a stream with this number. Asking the decoder for it means it finds no video stream, so
    // seeking and reading only touch the audio and the fetcher does not compete with playback for CPU.
    private static final int NO_VIDEO_STREAM = Integer.MAX_VALUE;

    // TEMPORARY, for diagnosing the TITAN runs: when a chunk fails, show the reason as a subtitle that stays up for
    // ten minutes. Set to false (or delete showOnScreen and its two uses) before the viva.
    private static final boolean DIAGNOSTIC_BANNER = true;
    private static final long BANNER_US = 600_000_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File mediaFile;
    private final SubtitleStore store;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Set<Integer> done = ConcurrentHashMap.newKeySet();
    private final Set<Integer> reported = ConcurrentHashMap.newKeySet(); // Chunks already shown in a banner.
    private final Map<Integer, Long> retryAtMs = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object(); // Makes "closed" and "add cues" mutually exclusive.
    private final Thread thread;
    private final long createdAtMs = System.currentTimeMillis();

    private volatile long playheadUs;
    private volatile long mediaDurationUs = Long.MAX_VALUE; // Passed in by the player, or learnt while fetching.
    private volatile boolean closed;

    // The audio decoder is opened once and reused for every chunk, so the fetcher does not open a new one (which is
    // the step that hung on TITAN) for each 20 seconds of media. Only the fetcher thread touches it.
    private FFmpegFrameGrabber reader;

    // What this media was last lined up with in the cache. Only the fetcher thread touches it.
    private final SubtitleCache cache = SubtitleCache.shared();
    private SubtitleCache.Placement placement;

    private SubtitleFetcher(File mediaFile, SubtitleStore store, long playheadUs, long mediaDurationUs) {
        this.mediaFile = mediaFile;
        this.store = store;
        this.playheadUs = playheadUs;
        if (mediaDurationUs > 0) {
            this.mediaDurationUs = mediaDurationUs;
        }
        this.thread = new Thread(this::run, "subtitle-fetcher");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 2); // Playback matters more than getting text early.
    }

    /** Starts fetching when the length of the media is not known. */
    public static SubtitleFetcher start(File mediaFile, SubtitleStore store, long playheadUs) {
        return start(mediaFile, store, playheadUs, 0);
    }

    /**
     * Starts fetching.
     *
     * @param mediaDurationUs the media length in microseconds, or 0 or less if the player does not know it
     */
    public static SubtitleFetcher start(File mediaFile, SubtitleStore store, long playheadUs, long mediaDurationUs) {
        SubtitleFetcher fetcher = new SubtitleFetcher(mediaFile, store, playheadUs, mediaDurationUs);
        fetcher.thread.start();
        return fetcher;
    }

    /** Tells the fetcher where playback is. Called from the JavaFX thread on every frame, so it must stay cheap. */
    public void setPlayheadUs(long playheadUs) {
        this.playheadUs = playheadUs;
    }

    /** After this returns, no more cues from this fetcher can reach the store. */
    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
        }
        thread.interrupt();
    }

    // Seconds since this fetcher was created, for console lines, so a log shows when each chunk started and ended.
    private String at() {
        return String.format(Locale.ROOT, "[+%.1fs] ", (System.currentTimeMillis() - createdAtMs) / 1000.0);
    }

    // ---- The fetching thread ----

    private void run() {
        try {
            if (!cache.isLoaded()) {
                cache.ensureLoaded(serverUsage());
            }
            runLoop();
        } finally {
            closeReader();
        }
    }

    private void runLoop() {
        while (!closed) {
            int chunk = nextChunkToFetch();
            if (chunk < 0) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            try {
                fetchChunk(chunk);
                if (closed) {
                    return;
                }
                done.add(chunk);
                retryAtMs.remove(chunk);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable problem) {
                // Throwable, not Exception: an Error must not silently end the only thread that fetches subtitles.
                closeReader(); // Start the retry from a clean decoder.
                retryAtMs.put(chunk, System.currentTimeMillis() + RETRY_DELAY_MS);
                System.out.println(at() + "Subtitles: chunk " + chunk + " failed (" + problem + "), retrying in "
                        + RETRY_DELAY_MS / 1000 + " s");
                if (DIAGNOSTIC_BANNER && reported.add(chunk)) {
                    showOnScreen("chunk " + chunk + " failed: " + problem.getClass().getSimpleName() + " "
                            + problem.getMessage());
                }
            }
        }
    }

    private void closeReader() {
        FFmpegFrameGrabber old = reader;
        reader = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
                // Being discarded; nothing useful to do.
            }
        }
    }

    // The first chunk, from the playing one onwards, that is not done, not waiting out a retry delay and not past the
    // end of the media. Returns -1 when there is nothing to do, including while the media file is missing.
    private int nextChunkToFetch() {
        if (!mediaFile.isFile()) {
            return -1;
        }
        int current = (int) (playheadUs / CHUNK_US);
        long now = System.currentTimeMillis();
        for (int i = 0; i <= LOOKAHEAD_CHUNKS; i++) {
            int chunk = current + i;
            if (chunk * CHUNK_US >= mediaDurationUs) {
                break; // This chunk and every later one start after the media ends.
            }
            if (!done.contains(chunk) && retryAtMs.getOrDefault(chunk, 0L) <= now) {
                return chunk;
            }
        }
        return -1;
    }

    private void fetchChunk(int chunk) throws Exception {
        long startUs = chunk * CHUNK_US;
        long endUs = startUs + CHUNK_US;
        long decodeStartedNs = System.nanoTime();
        System.out.printf("%sSubtitles: fetching chunk %d%n", at(), chunk);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long firstSampleUs = -1;

        boolean fresh = reader == null;
        if (fresh) {
            reader = openGrabber();
            System.out.println(at() + "Subtitles: audio decoder opened");
        }
        FFmpegFrameGrabber grabber = reader;

        long lengthUs = grabber.getLengthInTime();
        if (lengthUs > 0) {
            mediaDurationUs = lengthUs;
        }
        if (!grabber.hasAudio() || startUs >= mediaDurationUs) {
            return; // Nothing to transcribe, so the chunk counts as done.
        }
        if (startUs > 0 || !fresh) {
            grabber.setTimestamp(startUs); // A reused decoder is always moved to the start of the chunk.
        }

        Frame frame;
        while (!closed && (frame = grabber.grabSamples()) != null) {
            long timestampUs = grabber.getTimestamp();
            if (timestampUs >= endUs) {
                break;
            }
            if (timestampUs < startUs) {
                continue; // Seeking can land a little before the wanted time.
            }
            if (frame.samples == null || frame.samples.length == 0
                    || !(frame.samples[0] instanceof ShortBuffer samples)) {
                continue;
            }
            if (firstSampleUs < 0) {
                firstSampleUs = timestampUs;
            }

            ShortBuffer source = samples.duplicate();
            ByteBuffer out = ByteBuffer.allocate(source.remaining() * BYTES_PER_SAMPLE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            out.asShortBuffer().put(source);
            pcm.writeBytes(out.array());
        }
        long decodeMs = (System.nanoTime() - decodeStartedNs) / 1_000_000L;

        byte[] pcmBytes = pcm.toByteArray();
        if (closed || pcmBytes.length < MIN_PCM_BYTES) {
            if (!closed) {
                // Nothing at or after this chunk, so later chunks need not be tried.
                mediaDurationUs = Math.min(mediaDurationUs, startUs);
                System.out.printf("%sSubtitles: chunk %d had no audio, %d bytes (decode %d ms)%n",
                        at(), chunk, pcmBytes.length, decodeMs);
            }
            return; // Closed, or past the end of the media.
        }

        // The offset is where the first kept sample really is, so cue times stay right even if the seek was inexact.
        double offsetSeconds = firstSampleUs / 1_000_000.0;
        long dataStartUs = firstSampleUs;
        long dataEndUs = firstSampleUs + pcmBytes.length * 1_000_000L / (SAMPLE_RATE * BYTES_PER_SAMPLE);
        float[] envelope = SubtitleCache.envelope(pcmBytes);
        int startHop = SubtitleCache.hopIndex(firstSampleUs);

        // Has this sound been transcribed before? If so, the server is not asked.
        ArrayNode segmentNodes = null;
        try {
            placement = cache.place(envelope, startHop, placement);
            segmentNodes = cache.lookup(placement, startUs, endUs, dataStartUs, dataEndUs);
        } catch (RuntimeException problem) {
            System.out.println(at() + "Subtitles: cache lookup failed (" + problem + "), asking the server");
            placement = null;
            segmentNodes = null;
        }
        boolean fromCache = segmentNodes != null;

        long serverMs = 0;
        if (!fromCache) {
            long serverStartedNs = System.nanoTime();
            segmentNodes = transcribe(wav(pcmBytes), offsetSeconds);
            serverMs = (System.nanoTime() - serverStartedNs) / 1_000_000L;
            if (placement != null) {
                try {
                    cache.store(placement, segmentNodes, dataStartUs, dataEndUs, envelope, startHop, serverUsage());
                } catch (RuntimeException problem) {
                    System.out.println(at() + "Subtitles: could not remember chunk " + chunk + " (" + problem + ")");
                }
            }
        }
        List<SubtitleBuilder.Segment> segments = toSegments(segmentNodes);
        List<SubtitleCue> cues = SubtitleBuilder.build(segments);

        synchronized (lifecycle) {
            if (!closed) {
                store.addCues(cues);
            }
        }
        System.out.printf("%sSubtitles: chunk %d (%d s to %d s) gave %d cues (decode %d ms, %s)%n",
                at(), chunk, startUs / 1_000_000L, endUs / 1_000_000L, cues.size(), decodeMs,
                fromCache ? "from cache, no server call" : "server " + serverMs + " ms");
    }

    // TEMPORARY: puts a message on the video from the current playback position, so a failure can be read off a
    // screenshot when the console cannot be seen.
    private void showOnScreen(String message) {
        long from = playheadUs;
        synchronized (lifecycle) {
            if (!closed) {
                store.addCues(List.of(new SubtitleCue(from, from + BANNER_US, SubtitleBuilder.wrap(message))));
            }
        }
    }

    // ---- Opening the audio ----

    // Tries the audio-only decoder first. If that cannot start for any reason, falls back to normal decoding, which is
    // slower but known to work, and says so on the console.
    private FFmpegFrameGrabber openGrabber() throws Exception {
        try {
            return startGrabber(true);
        } catch (Exception audioOnlyFailed) {
            if (!mediaFile.isFile()) {
                throw audioOnlyFailed;
            }
            System.out.println(at() + "Subtitles: audio-only decoding failed (" + audioOnlyFailed
                    + "), using normal decoding");
            return startGrabber(false);
        }
    }

    private FFmpegFrameGrabber startGrabber(boolean audioOnly) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.setAudioChannels(1);
        grabber.setSampleRate(SAMPLE_RATE);
        if (audioOnly) {
            grabber.setVideoStream(NO_VIDEO_STREAM);
        }
        try {
            grabber.start();
        } catch (Exception e) {
            try {
                grabber.close();
            } catch (Exception ignored) {
                // Already failing; the original error is the useful one.
            }
            throw e;
        }
        return grabber;
    }

    // ---- Talking to the web server ----

    // The server's usage counter, or -1 if it cannot be read. Reading it is free; the cache uses it to tell whether the
    // server has been restarted since the cache was written.
    private double serverUsage() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(USAGE_ENDPOINT))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body()).path("usage").asDouble(-1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Unknown is fine: the cache then trusts what it has.
        }
        return -1;
    }

    private static List<SubtitleBuilder.Segment> toSegments(JsonNode segmentNodes) {
        List<SubtitleBuilder.Segment> segments = new ArrayList<>();
        for (JsonNode s : segmentNodes) {
            List<SubtitleBuilder.Word> words = new ArrayList<>();
            for (JsonNode w : s.path("words")) {
                words.add(new SubtitleBuilder.Word(
                        w.path("text").asText(), w.path("startSeconds").asDouble(), w.path("endSeconds").asDouble()));
            }
            segments.add(new SubtitleBuilder.Segment(
                    s.path("text").asText(),
                    s.path("startSeconds").asDouble(),
                    s.path("endSeconds").asDouble(),
                    s.path("avgLogProb").asDouble(),
                    s.path("noSpeechProb").asDouble(),
                    words));
        }
        return segments;
    }

    // Returns the server's segments as plain JSON, in the media's own timeline, so they can be cached as they are.
    private ArrayNode transcribe(byte[] wavBytes, double offsetSeconds) throws Exception {
        String boundary = "----subtitles" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeText(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"offsetSeconds\"\r\n\r\n"
                + String.format(Locale.ROOT, "%.3f", offsetSeconds) + "\r\n");
        writeText(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        body.writeBytes(wavBytes);
        writeText(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("server replied " + response.statusCode() + ": " + response.body());
        }

        ArrayNode segments = MAPPER.createArrayNode();
        for (JsonNode s : MAPPER.readTree(response.body()).path("segments")) {
            segments.add(s);
        }
        return segments;
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    // A 44 byte WAV header followed by the raw samples: mono, 16 bit, little endian.
    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1); // PCM
        b.putShort((short) 1); // Channels
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE); // Bytes per second: 16000 * 2 * 1 = 32000
        b.putShort((short) BYTES_PER_SAMPLE); // Bytes per sample frame
        b.putShort((short) 16); // Bits per sample
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        b.put(pcm);
        return b.array();
    }
}